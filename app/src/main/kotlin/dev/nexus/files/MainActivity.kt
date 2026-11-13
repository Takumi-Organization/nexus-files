package dev.nexus.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexusTheme {
                var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager() }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                if (granted) ExplorerScreen(onShare = ::share, onOpenExternal = ::openExternal)
                else PermissionGate {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    private fun uriFor(f: java.io.File) = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun mime(f: java.io.File) = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun share(files: List<java.io.File>) {
        val files = files.filter { it.isFile }
        if (files.isEmpty()) return
        val i = Intent(if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) mime(files[0]) else "*/*"
            if (files.size == 1) putExtra(Intent.EXTRA_STREAM, uriFor(files[0]))
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map(::uriFor)))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Compartir"))
    }

    private fun openExternal(f: java.io.File): Boolean = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.isSuccess
}
