package dev.nexus.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.nexus.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class TState { RUNNING, DONE, FAILED, CANCELLED }
data class TransferJob(val id: Int, val title: String, val state: TState = TState.RUNNING, val bytes: Long = 0, val current: String = "", val message: String = "")

/**
 * Cola de transferencias remotas. Cada transferencia abre su propia conexión (así cancelar una no deja
 * una sesión FTP a medias) y se ejecuta en un scope de proceso; [TransferService] mantiene vivo el proceso
 * con una notificación en primer plano mientras haya alguna en marcha.
 */
object Transfers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicInteger(1)
    private val handles = HashMap<Int, Job>()
    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs

    private fun update(id: Int, f: (TransferJob) -> TransferJob) = _jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    private fun start(ctx: Context, title: String, run: suspend (id: Int, cancelled: () -> Boolean, progress: (Long, String) -> Unit) -> TransferResult): Job {
        val app = ctx.applicationContext
        val id = ids.getAndIncrement()
        _jobs.update { (it + TransferJob(id, title)).takeLast(30) }
        ctx.startForegroundService(Intent(app, TransferService::class.java))
        val job = scope.launch {
            val r = try {
                run(id, { !isActive }, { b, c -> update(id) { j -> if (j.state == TState.RUNNING) j.copy(bytes = b, current = c) else j } })
            } catch (c: CancellationException) { TransferResult(0, 0, emptyList(), true) }
            catch (t: Throwable) { TransferResult(0, 0, listOf(t.message ?: t.javaClass.simpleName), false) }
            update(id) { j -> when {
                r.cancelled -> j.copy(state = TState.CANCELLED, message = "Cancelada")
                r.failed.isNotEmpty() -> j.copy(state = TState.FAILED, bytes = r.bytes, message = "${r.files} ok, ${r.failed.size} con error: ${r.failed.first()}")
                else -> j.copy(state = TState.DONE, bytes = r.bytes, message = "${r.files} archivo(s)")
            } }
            synchronized(handles) { handles.remove(id) }
        }
        synchronized(handles) { handles[id] = job }
        return job
    }

    /** Conexión con reconexión y reintentos (hasta 4 intentos, espera 2-4-8 s); el estado del reintento se ve en la lista. */
    private fun open(ctx: Context, profile: RemoteProfile, id: Int, cancelled: () -> Boolean): RemoteFs {
        val store = ProfileStore(File(ctx.filesDir, "remotes.json"), KeystoreSecretBox())
        val sec = AppRemote.secrets(store, profile)
        return RetryingFs({ AppRemote.open(ctx, profile, sec) }, RetryPolicy(4, 2_000, 30_000), cancelled,
            onRetry = { n, d, e -> update(id) { j -> j.copy(current = "Reintentando ($n/4) en ${d / 1000} s: ${e.message ?: e.javaClass.simpleName}") } })
    }

    fun download(ctx: Context, profile: RemoteProfile, e: RemoteEntry, destDir: File) =
        start(ctx, "Descargar ${e.name}") { id, cancelled, progress -> open(ctx, profile, id, cancelled).use { RemoteTransfer.download(it, e, destDir, cancelled, progress) } }

    fun upload(ctx: Context, profile: RemoteProfile, src: File, remoteDir: String, deleteAfter: Boolean = false) =
        start(ctx, "Subir ${src.name}") { id, cancelled, progress ->
            try { open(ctx, profile, id, cancelled).use { RemoteTransfer.upload(it, src, remoteDir, cancelled, progress) } } finally { if (deleteAfter) src.delete() }
        }

    /**
     * Operación local larga (copiar, mover, borrar, ZIP) dentro del servicio en primer plano: sigue con la app en segundo plano
     * y se cancela desde la notificación. [onProgress]/[onDone] devuelven el resultado al llamador.
     */
    fun local(ctx: Context, title: String, block: suspend ((Progress) -> Unit) -> Report, onProgress: (Progress) -> Unit, onDone: (Report) -> Unit): Job {
        var report = Report()
        val job = start(ctx, title) { id, _, progress ->
            report = try { block { p -> onProgress(p); progress(p.doneBytes, p.current) } } catch (c: CancellationException) { Report(cancelled = true) }
            TransferResult(report.done, 0, report.failed.map { "${it.path}: ${it.reason}" }, report.cancelled)
        }
        job.invokeOnCompletion { t -> onDone(if (t is CancellationException && report == Report()) Report(cancelled = true) else report) }
        return job
    }

    fun cancel(id: Int) { synchronized(handles) { handles[id] }?.cancel() }
    fun cancelAll() { synchronized(handles) { handles.values.toList() }.forEach { it.cancel() } }
    fun clearFinished() = _jobs.update { l -> l.filter { it.state == TState.RUNNING } }
    fun running() = _jobs.value.count { it.state == TState.RUNNING }
}

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) Transfers.cancelAll()
        nm.createNotificationChannel(NotificationChannel(CH, "Transferencias", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF, build(Transfers.jobs.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.coroutineContext.cancelChildren()
        scope.launch {
            Transfers.jobs.collect { jobs ->
                if (jobs.none { it.state == TState.RUNNING }) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collect }
                nm.notify(NOTIF, build(jobs))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(jobs: List<TransferJob>): Notification {
        val run = jobs.filter { it.state == TState.RUNNING }
        val cancel = PendingIntent.getService(this, 0, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (run.size == 1) run[0].title else "${run.size} transferencias")
            .setContentText(run.firstOrNull()?.let { "${it.current} · ${humanSize(it.bytes)}" } ?: "")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancelar", cancel).build()).build()
    }

    override fun onDestroy() { scope.cancel() }

    companion object { const val CH = "transfers"; const val NOTIF = 42; const val ACTION_CANCEL = "dev.nexus.files.CANCEL" }
}
