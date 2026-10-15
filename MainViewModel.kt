package com.photocull

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    var tree by mutableStateOf<Uri?>(null); private set
    var photos by mutableStateOf<List<Photo>>(emptyList()); private set
    val results = mutableStateMapOf<Uri, Analysis>()
    var done by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var blurLimit by mutableFloatStateOf(80f)
    var filter by mutableStateOf(Filter.ALL)
    var selected by mutableStateOf<Set<Uri>>(emptySet()); private set
    var message by mutableStateOf<String?>(null)

    private var job: Job? = null

    fun flags(uri: Uri): Set<Flag> {
        val a = results[uri] ?: return emptySet()
        return buildSet {
            if (a.sharpness < blurLimit) add(Flag.BLUR)
            if (a.closedFaces > 0) add(Flag.CLOSED)
            if (a.brightness < 45f) add(Flag.DARK)
            if (a.brightness > 215f) add(Flag.BRIGHT)
            if (a.group >= 0 && !a.bestInGroup) add(Flag.SIMILAR)
        }
    }

    fun matches(p: Photo, f: Filter): Boolean {
        val fl = flags(p.uri)
        return when (f) {
            Filter.ALL -> true
            Filter.PROBLEMS -> fl.isNotEmpty()
            Filter.BLUR -> Flag.BLUR in fl
            Filter.CLOSED -> Flag.CLOSED in fl
            Filter.EXPOSURE -> Flag.DARK in fl || Flag.BRIGHT in fl
            Filter.SIMILAR -> Flag.SIMILAR in fl
            Filter.GOOD -> results[p.uri] != null && fl.isEmpty()
        }
    }

    fun count(f: Filter): Int = photos.count { matches(it, f) }

    fun toggle(uri: Uri) {
        selected = if (uri in selected) selected - uri else selected + uri
    }

    fun selectVisible() {
        selected = photos.filter { matches(it, filter) }.map { it.uri }.toSet()
    }

    fun clearSelection() { selected = emptySet() }

    fun load(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        job?.cancel()
        tree = uri
        results.clear()
        selected = emptySet()
        done = 0
        photos = emptyList()
        job = viewModelScope.launch {
            busy = true
            val list = withContext(Dispatchers.IO) { listImages(uri) }.sortedBy { it.name }
            photos = list
            val sem = Semaphore(3)
            coroutineScope {
                list.forEach { p ->
                    launch {
                        sem.withPermit {
                            ImageAnalyzer.analyze(ctx, p.uri)?.let { results[p.uri] = it }
                            done++
                        }
                    }
                }
            }
            val grouped = withContext(Dispatchers.Default) { ImageAnalyzer.group(list, results.toMap()) }
            results.putAll(grouped)
            busy = false
            message = "خلص التحليل: ${list.size} صورة"
        }
    }

    private fun listImages(tree: Uri): List<Photo> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = mutableListOf<Photo>()
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    if (f.name != REJECTED) walk(f)
                } else {
                    val t = f.type ?: ""
                    if (t.startsWith("image/")) {
                        out.add(Photo(f.uri, dir.uri, f.name ?: "photo", t, f.lastModified()))
                    }
                }
            }
        }
        walk(root)
        return out
    }

    /** بنقل الصور المحددة لفولدر _Rejected جوه نفس الفولدر (مفيش حذف نهائي). */
    fun moveSelected() {
        val t = tree ?: return
        val chosen = photos.filter { it.uri in selected }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            busy = true
            val moved = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(ctx, t) ?: return@withContext emptySet<Uri>()
                val rej = root.findFile(REJECTED) ?: root.createDirectory(REJECTED)
                    ?: return@withContext emptySet<Uri>()
                chosen.filter { moveOne(it, rej) }.map { it.uri }.toSet()
            }
            photos = photos.filter { it.uri !in moved }
            moved.forEach { results.remove(it) }
            selected = selected - moved
            busy = false
            message = "اتنقل ${moved.size} من ${chosen.size} لفولدر $REJECTED"
        }
    }

    private fun moveOne(p: Photo, rej: DocumentFile): Boolean {
        val cr = ctx.contentResolver
        val viaMove = runCatching {
            DocumentsContract.moveDocument(cr, p.uri, p.parent, rej.uri)
        }.getOrNull()
        if (viaMove != null) return true
        return runCatching {
            val dst = rej.createFile(p.mime, p.name) ?: return false
            cr.openInputStream(p.uri)!!.use { i ->
                cr.openOutputStream(dst.uri)!!.use { o -> i.copyTo(o) }
            }
            DocumentFile.fromSingleUri(ctx, p.uri)?.delete() == true
        }.getOrDefault(false)
    }

    companion object { const val REJECTED = "_Rejected" }
}
