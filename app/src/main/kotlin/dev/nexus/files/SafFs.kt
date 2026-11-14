package dev.nexus.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.nexus.core.*
import java.io.File
import java.io.IOException

/**
 * Carpeta elegida con el selector de árbol del sistema (SAF), expuesta como [RemoteFs] para no depender de «acceso a todos los archivos».
 * Los permisos son los concedidos por el usuario y persistidos (`takePersistableUriPermission`). Las rutas son relativas a la carpeta elegida.
 */
class SafFs(ctx: Context, private val tree: Uri) : RemoteFs {
    private val cr: ContentResolver = ctx.applicationContext.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) { val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR }

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val c = try { cr.query(uri, cols, null, null, null) } catch (e: SecurityException) { throw RemoteException("Sin permiso sobre la carpeta (¿se revocó el acceso?)", e) } ?: throw RemoteException("No se pudo leer la carpeta")
        c.use { return buildList { while (it.moveToNext()) add(Child(it.getString(0), it.getString(1) ?: "", it.getString(2) ?: "", if (it.isNull(3)) 0 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4))) } }
    }

    /** Id del documento de [path] (recorriendo por nombre) o null si no existe. */
    private fun resolve(path: String): Child? {
        var id = rootId; var found: Child? = null
        for (seg in RemotePaths.normalize(path).split('/').filter { it.isNotEmpty() }) {
            found = children(id).firstOrNull { it.name == seg } ?: return null
            id = found.id
        }
        return found ?: Child(rootId, "", DocumentsContract.Document.MIME_TYPE_DIR, 0, 0)
    }

    private fun need(path: String, op: String): Child = resolve(path) ?: throw RemoteException("$op: no existe ${RemotePaths.normalize(path)}")

    override fun list(path: String): List<RemoteEntry> {
        val d = need(path, "Listar"); if (!d.isDir) throw RemoteException("Listar: no es una carpeta")
        return children(d.id).filter { RemotePaths.safeName(it.name) }.map { RemoteEntry(RemotePaths.child(path, it.name), it.name, it.isDir, if (it.isDir) 0 else it.size, it.modified) }
    }

    override fun download(path: String, dest: File, onProgress: (Long) -> Unit) {
        val d = need(path, "Descargar"); var total = 0L
        val input = cr.openInputStream(docUri(d.id)) ?: throw RemoteException("Descargar: no se pudo abrir")
        input.use { i -> atomicCopy(dest) { out -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n; onProgress(total) } } }
    }

    private fun atomicCopy(dest: File, w: (java.io.OutputStream) -> Unit) {
        dest.absoluteFile.parentFile?.mkdirs()
        val part = File(dest.path + FileOps.PART)
        try { part.outputStream().buffered().use(w); if (dest.exists()) dest.delete(); if (!part.renameTo(dest)) throw IOException("No se pudo mover a ${dest.name}") }
        catch (e: Throwable) { part.delete(); throw e }
    }

    override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) {
        val target = RemotePaths.normalize(path); val parent = need(RemotePaths.parent(target), "Subir"); val name = RemotePaths.nameOf(target)
        val existing = children(parent.id).firstOrNull { it.name == name }
        if (existing != null) { if (!overwrite) throw RemoteException("Subir: ya existe $target"); DocumentsContract.deleteDocument(cr, docUri(existing.id)) }
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase()) ?: "application/octet-stream"
        val created = DocumentsContract.createDocument(cr, docUri(parent.id), mime, name) ?: throw RemoteException("Subir: el proveedor no pudo crear el archivo")
        try {
            var sent = 0L; val buf = ByteArray(64 * 1024)
            (cr.openOutputStream(created, "wt") ?: throw RemoteException("Subir: no se pudo abrir el destino")).use { o -> src.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); sent += n; onProgress(sent) } } }
        } catch (e: Throwable) { try { DocumentsContract.deleteDocument(cr, created) } catch (_: Exception) {}; throw e }
    }

    override fun mkdir(path: String) {
        val t = RemotePaths.normalize(path); if (resolve(t) != null) throw RemoteException("Crear carpeta: ya existe $t")
        DocumentsContract.createDocument(cr, docUri(need(RemotePaths.parent(t), "Crear carpeta").id), DocumentsContract.Document.MIME_TYPE_DIR, RemotePaths.nameOf(t)) ?: throw RemoteException("Crear carpeta: el proveedor no pudo crearla")
    }

    override fun delete(path: String, isDir: Boolean) {
        if (RemotePaths.normalize(path) == "/") throw RemoteException("No se puede borrar la raíz")
        if (!DocumentsContract.deleteDocument(cr, docUri(need(path, "Borrar").id))) throw RemoteException("Borrar: el proveedor no pudo borrarlo")
    }

    override fun rename(from: String, to: String) {
        val f = RemotePaths.normalize(from); val t = RemotePaths.normalize(to)
        if (resolve(t) != null) throw RemoteException("Renombrar: ya existe $t")
        val src = need(f, "Renombrar")
        if (RemotePaths.parent(f) == RemotePaths.parent(t)) {
            DocumentsContract.renameDocument(cr, docUri(src.id), RemotePaths.nameOf(t)) ?: throw RemoteException("Renombrar: el proveedor no pudo renombrar")
            return
        }
        // otra carpeta: mover nativo si el proveedor lo permite; si no, copiar y borrar
        val srcParent = need(RemotePaths.parent(f), "Mover"); val dstParent = need(RemotePaths.parent(t), "Mover")
        if (!dstParent.isDir) throw RemoteException("Mover: el destino no es una carpeta")
        val moved = try { DocumentsContract.moveDocument(cr, docUri(src.id), docUri(srcParent.id), docUri(dstParent.id)) } catch (e: Exception) { null }
        if (moved != null) {
            if (RemotePaths.nameOf(t) != src.name) DocumentsContract.renameDocument(cr, moved, RemotePaths.nameOf(t)) ?: throw RemoteException("Mover: movido pero no se pudo renombrar")
            return
        }
        copyTree(src, dstParent.id, RemotePaths.nameOf(t))
        if (!DocumentsContract.deleteDocument(cr, docUri(src.id))) throw RemoteException("Mover: copiado pero no se pudo borrar el original")
    }

    /** Copia recursiva dentro del mismo árbol (respaldo de [rename] entre carpetas). Si algo falla, borra lo ya copiado de este elemento. */
    private fun copyTree(src: Child, dstParentId: String, name: String) {
        val created = DocumentsContract.createDocument(cr, docUri(dstParentId), if (src.isDir) DocumentsContract.Document.MIME_TYPE_DIR else src.mime.ifEmpty { "application/octet-stream" }, name)
            ?: throw RemoteException("Mover: el proveedor no pudo crear «$name»")
        try {
            if (src.isDir) { val newId = DocumentsContract.getDocumentId(created); for (c in children(src.id)) copyTree(c, newId, c.name) }
            else (cr.openInputStream(docUri(src.id)) ?: throw RemoteException("Mover: no se pudo leer ${src.name}")).use { i -> (cr.openOutputStream(created, "wt") ?: throw RemoteException("Mover: no se pudo escribir")).use { o -> i.copyTo(o) } }
        } catch (e: Throwable) { try { DocumentsContract.deleteDocument(cr, created) } catch (_: Exception) {}; throw e }
    }
}

/** Abre cualquier conexión: SAF aquí (necesita Android), el resto en :core. */
object AppRemote {
    fun open(ctx: Context, p: RemoteProfile, s: Secrets): RemoteFs = if (p.protocol == Protocol.SAF) SafFs(ctx, Uri.parse(p.host)) else RemoteFactory.open(p, s)
    fun secrets(store: ProfileStore, p: RemoteProfile): Secrets =
        if (p.protocol == Protocol.SAF) Secrets() else store.secrets(p.id) ?: throw RemoteException("No se pudieron recuperar las credenciales guardadas: edita la conexión y vuelve a escribirlas")
}
