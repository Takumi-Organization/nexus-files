package dev.nexus.core

import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Reintentos con espera exponencial: [baseMs]·2^(n-1) hasta [maxMs], como mucho [maxAttempts] intentos en total. */
data class RetryPolicy(val maxAttempts: Int = 4, val baseMs: Long = 2_000, val maxMs: Long = 30_000) {
    init { require(maxAttempts >= 1 && baseMs >= 0 && maxMs >= baseMs) }
    fun delayBefore(retry: Int): Long = minOf(maxMs, baseMs shl (retry - 1).coerceIn(0, 20))
}

object Retry {
    /** Fallos de red probablemente pasajeros. Autenticación, permisos, «ya existe», huella no confiada y cancelaciones nunca se reintentan. */
    fun isTransient(t: Throwable): Boolean {
        var c: Throwable? = t; var depth = 0
        while (c != null && depth++ < 8) {
            when (c) {
                is UntrustedHostException, is CancellationException -> return false
                is SocketTimeoutException, is ConnectException, is SocketException, is EOFException, is InterruptedIOException -> return true
                is SSLException -> return true
                is UnknownHostException -> return false
            }
            c = c.cause
        }
        return false
    }
}

/**
 * Decorador de [RemoteFs] que reconecta (con [open]) y reintenta cada operación ante fallos de red pasajeros.
 * Una descarga/subida interrumpida se repite desde el principio (sin reanudar); las operaciones fallidas no dejan parciales.
 */
class RetryingFs(
    private val open: () -> RemoteFs,
    private val policy: RetryPolicy = RetryPolicy(),
    private val cancelled: () -> Boolean = { false },
    private val sleep: (Long) -> Unit = { ms -> var left = ms; while (left > 0) { Thread.sleep(minOf(left, 100)); left -= 100 } },
    private val onRetry: (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
) : RemoteFs {
    private var cur: RemoteFs? = null

    private fun <T> op(block: (RemoteFs) -> T): T {
        var attempt = 1
        while (true) {
            if (cancelled()) throw CancellationException("cancelado")
            try { return block(cur ?: open().also { cur = it }) }
            catch (e: Throwable) {
                if (e is CancellationException) throw e
                try { cur?.close() } catch (_: Exception) {}; cur = null
                if (attempt >= policy.maxAttempts || !Retry.isTransient(e)) throw e
                val d = policy.delayBefore(attempt); onRetry(attempt + 1, d, e)
                val until = System.currentTimeMillis() + d
                while (System.currentTimeMillis() < until) { if (cancelled()) throw CancellationException("cancelado"); sleep(minOf(100, until - System.currentTimeMillis()).coerceAtLeast(1)) }
                attempt++
            }
        }
    }

    override fun list(path: String) = op { it.list(path) }
    override fun download(path: String, dest: File, onProgress: (Long) -> Unit) = op { it.download(path, dest, onProgress) }
    override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) = op { it.upload(src, path, overwrite, onProgress) }
    override fun mkdir(path: String) = op { it.mkdir(path) }
    override fun delete(path: String, isDir: Boolean) = op { it.delete(path, isDir) }
    override fun rename(from: String, to: String) = op { it.rename(from, to) }
    override fun close() { try { cur?.close() } catch (_: Exception) {}; cur = null }
}
