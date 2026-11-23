// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.main.PortalPage
import com.metallic.chiaki.main.PortalUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class GalleryViewState: ViewModel() {
    var result: GalleryScan? = null
    var endpoint = ""
    val selected = linkedSetOf<String>()
    var page = 0
    var pendingTransfer = emptyList<GalleryCapture>()
    val thumbnails = object: LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    val missingThumbnails = linkedSetOf<String>()

    fun clearThumbnails() { thumbnails.evictAll(); missingThumbnails.clear() }
    fun rememberMissingThumbnail(key: String) {
        missingThumbnails.add(key)
        while(missingThumbnails.size > 256) missingThumbnails.remove(missingThumbnails.first())
    }
    override fun onCleared() { clearThumbnails() }
}

class GalleryActivity: AppCompatActivity() {
    private lateinit var preferences: PortalPreferences
    private lateinit var state: GalleryViewState
    private lateinit var page: PortalPage
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<GalleryFtpClient, Boolean>())
    private val pendingPreviews = ConcurrentHashMap<Int, File>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var work: Job? = null
    @Volatile private var operation = 0
    private var progressOverlay: View? = null
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var preview: Dialog? = null
    private var previewFile: File? = null
    private var previewVideo: VideoView? = null
    private var columns = 1
    private val thumbnailBindings = mutableListOf<ThumbnailBinding>()
    private val thumbnailLoads = mutableMapOf<String, ThumbnailLoad>()
    private val thumbnailSlots = Semaphore(2)
    private val thumbnailVisibleRect = Rect()
    private var thumbnailForeground = false
    @Volatile private var thumbnailGeneration = 0
    private var thumbnailPassPosted = false
    private val thumbnailPass = Runnable { thumbnailPassPosted = false; loadVisibleThumbnails() }
    private val thumbnailScrollListener = ViewTreeObserver.OnScrollChangedListener { requestThumbnailPass() }
    @Volatile private var completedTransfers = 0
    private val journal by lazy { getSharedPreferences("gallery_incomplete_transfers", MODE_PRIVATE) }

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri == null) {
            state.pendingTransfer = emptyList()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            preferences.galleryTree = uri
            render()
            val pending = state.pendingTransfer
            state.pendingTransfer = emptyList()
            if(pending.isNotEmpty()) transfer(pending)
        } catch(error: Exception) {
            message("Folder unavailable", error.message ?: "The selected folder does not allow saved write access.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = PortalPreferences(this)
        state = ViewModelProvider(this)[GalleryViewState::class.java]
        if(state.endpoint != endpoint()) {
            state.result = null; state.selected.clear(); state.pendingTransfer = emptyList()
            state.clearThumbnails()
            state.endpoint = endpoint()
        }
        page = PortalPage(this, "Media Gallery") { finish() }
        setContentView(page.root)
        PortalUi.fullscreen(this)
        page.scroll.viewTreeObserver.addOnScrollChangedListener(thumbnailScrollListener)
        page.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val next = ((right - left) / resources.displayMetrics.density / 250f).toInt().coerceIn(1, 5)
            if(columns != next) { columns = next; render() } else requestThumbnailPass()
        }
        render()
        if(preferences.mode == PortalPreferences.Mode.JB && state.result == null) refresh()
    }

    override fun onResume() {
        super.onResume()
        thumbnailForeground = true
        PortalUi.fullscreen(this)
        if(preferences.mode != PortalPreferences.Mode.JB) {
            cancelWork(false)
            render()
        } else if(state.endpoint != endpoint()) {
            state.result = null; state.selected.clear(); state.pendingTransfer = emptyList()
            state.clearThumbnails()
            state.endpoint = endpoint()
            render()
            refresh()
        }
        requestThumbnailPass()
    }

    override fun onStop() {
        thumbnailForeground = false
        cancelThumbnails()
        thumbnailBindings.forEach { showThumbnail(it, null) }
        super.onStop()
    }

    private fun endpoint() = "${preferences.jbHost}:${preferences.ftpPort}${preferences.galleryRoot}"

    private fun render(resetScroll: Boolean = false) {
        cancelThumbnails()
        thumbnailBindings.clear()
        val scrollPosition = if(resetScroll) 0 else page.scroll.scrollY
        page.clear("Media Gallery")
        if(preferences.mode != PortalPreferences.Mode.JB) {
            page.note("Media Gallery is available in Remote Play JB. Choose that mode in Settings to browse your PS5 captures.")
            return
        }
        val captures = state.result?.captures.orEmpty()
        page.add(PortalUi.row(this, "Refresh gallery",
            "PS5 · ${preferences.jbHost}:${preferences.ftpPort}",
            "${captures.size} captures", PortalUi.Icon.GALLERY) { if(work?.isActive != true) refresh() })
        page.add(PortalUi.row(this, "Save to device",
            preferences.galleryTree?.let { folderLabel(it) } ?: "Choose the Relay folder on this device",
            icon = PortalUi.Icon.SYSTEM) { chooseDestination(emptyList()) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(PortalUi.primary(this,
            if(captures.isNotEmpty() && state.selected.size == captures.size) "Clear selection" else "Select all") {
            if(captures.isNotEmpty() && state.selected.size == captures.size) state.selected.clear()
            else { state.selected.clear(); state.selected.addAll(captures.map { it.path }) }
            render()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = page.dp(10) })
        actions.addView(PortalUi.primary(this, "Transfer (${state.selected.size})") {
            val selected = captures.filter { it.path in state.selected }
            if(selected.isEmpty()) message("Select captures", "Select one or more captures, or choose Select all.")
            else transfer(selected)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.add(actions)
        state.result?.let { result ->
            if(result.warnings.isNotEmpty())
                page.note("Some folders could not be read. ${result.warnings.take(2).joinToString("\n")}")
            if(result.limited)
                page.note("This scan reached its safety limit. Showing the captures found so far; the gallery may contain more.")
        }
        if(captures.isEmpty()) {
            page.note(if(state.result == null) "Connect to the PS5 FTP service to load your gallery."
                else "No completed screenshots or video clips were found in ${preferences.galleryRoot}/photo or /video.")
            return
        }

        val pages = (captures.size + PAGE_SIZE - 1) / PAGE_SIZE
        state.page = state.page.coerceIn(0, pages - 1)
        val visible = captures.drop(state.page * PAGE_SIZE).take(PAGE_SIZE)
        page.note("${state.selected.size} selected · Page ${state.page + 1} of $pages")
        val grid = GridLayout(this).apply { columnCount = columns; alignmentMode = GridLayout.ALIGN_BOUNDS }
        for((index, capture) in visible.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = PortalUi.surface(this@GalleryActivity)
                setPadding(page.dp(14), page.dp(14), page.dp(14), page.dp(14))
                isSelected = capture.path in state.selected
                PortalUi.focus(this)
                isClickable = true
                setOnClickListener { captureActions(capture) }
                setOnLongClickListener { toggle(capture); true }
                contentDescription = "${capture.name}, ${sizeLabel(capture.size)}, " +
                    if(isSelected) "selected" else "not selected"
            }
            val thumbnail = ThumbnailFrame(this).apply {
                background = GradientDrawable().apply {
                    setColor(0xff100f17.toInt()); cornerRadius = page.dp(8).toFloat()
                }
                clipToOutline = true
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            thumbnail.addView(image, FrameLayout.LayoutParams(-1, -1))
            val placeholder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            }
            placeholder.addView(ImageView(this).apply {
                setImageDrawable(PortalUi.Glyph(if(capture.kind == CaptureKind.VIDEO)
                    PortalUi.Icon.RECORD else PortalUi.Icon.GALLERY))
                alpha = 0.45f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(page.dp(36), page.dp(36)))
            val label = PortalUi.text(this, "Loading preview…", 12f, true).apply {
                gravity = Gravity.CENTER; setPadding(page.dp(8), page.dp(8), page.dp(8), 0)
            }
            placeholder.addView(label)
            thumbnail.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            if(capture.kind == CaptureKind.VIDEO) thumbnail.addView(PortalUi.text(this, "▶  Video", 12f).apply {
                setPadding(page.dp(8), page.dp(4), page.dp(8), page.dp(4))
                background = GradientDrawable().apply {
                    setColor(0xb3000000.toInt()); cornerRadius = page.dp(6).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(page.dp(8), page.dp(8), page.dp(8), page.dp(8))
            })
            thumbnail.addView(CheckBox(this).apply {
                isChecked = capture.path in state.selected
                contentDescription = "Select ${capture.name}"
                background = GradientDrawable().apply {
                    setColor(0xaa000000.toInt()); cornerRadius = page.dp(6).toFloat()
                }
                setOnCheckedChangeListener { _, checked ->
                    if(checked) state.selected.add(capture.path) else state.selected.remove(capture.path)
                    card.isSelected = checked
                    mainHandler.post { if(!isFinishing && !isDestroyed) render() }
                }
            }, FrameLayout.LayoutParams(page.dp(44), page.dp(44), Gravity.TOP or Gravity.END).apply {
                setMargins(page.dp(6), page.dp(6), page.dp(6), page.dp(6))
            })
            card.addView(thumbnail, LinearLayout.LayoutParams(-1, -2))
            val binding = ThumbnailBinding(capture, thumbnailKey(capture), thumbnail, image, placeholder, label)
            thumbnailBindings.add(binding)
            state.thumbnails.get(binding.key)?.let { showThumbnail(binding, it) }
            card.addView(PortalUi.text(this, capture.name, 15f).apply {
                maxLines = 2; minLines = 2; setPadding(0, page.dp(9), 0, 0)
            })
            card.addView(PortalUi.text(this, "${capture.game} · ${sizeLabel(capture.size)}", 12f, true))
            capture.modified?.let {
                card.addView(PortalUi.text(this, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(it)), 12f, true).apply { setPadding(0, page.dp(4), 0, 0) })
            }
            grid.addView(card, GridLayout.LayoutParams(
                GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f)).apply {
                width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(page.dp(4), page.dp(4), page.dp(4), page.dp(4))
            })
        }
        page.add(grid)
        if(pages > 1) {
            val navigation = LinearLayout(this)
            navigation.addView(PortalUi.primary(this, "Previous") {
                if(state.page > 0) { state.page--; render(true) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = page.dp(10) })
            navigation.addView(PortalUi.primary(this, "Next") {
                if(state.page + 1 < pages) { state.page++; render(true) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            page.add(navigation)
        }
        page.scroll.post { page.scroll.scrollTo(0, scrollPosition); requestThumbnailPass() }
    }

    private class ThumbnailFrame(context: Context): FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec((width * 9 / 16).coerceAtLeast(1), View.MeasureSpec.EXACTLY))
        }
    }

    private data class ThumbnailBinding(
        val capture: GalleryCapture, val key: String, val frame: View,
        val image: ImageView, val placeholder: View, val label: TextView,
        var displayed: Boolean = false
    )

    private class ThumbnailLoad {
        @Volatile var client: GalleryFtpClient? = null
        @Volatile var cancelled = false
        var job: Job? = null
        fun cancel() { cancelled = true; client?.close(); job?.cancel() }
    }

    private fun thumbnailKey(capture: GalleryCapture) =
        "${state.endpoint}\n${capture.path}\n${capture.size}\n${capture.modified}"

    private fun showThumbnail(binding: ThumbnailBinding, bitmap: Bitmap?) {
        binding.image.setImageBitmap(bitmap)
        binding.displayed = bitmap != null
        binding.placeholder.visibility = if(bitmap == null) View.VISIBLE else View.GONE
        binding.label.text = if(binding.key in state.missingThumbnails) "Preview unavailable" else "Loading preview…"
    }

    private fun requestThumbnailPass() {
        if(!thumbnailForeground || thumbnailPassPosted || isFinishing || isDestroyed) return
        thumbnailPassPosted = true
        mainHandler.post(thumbnailPass)
    }

    private fun cancelThumbnails() {
        thumbnailGeneration++
        mainHandler.removeCallbacks(thumbnailPass)
        thumbnailPassPosted = false
        thumbnailLoads.values.toList().forEach { it.cancel() }
        thumbnailLoads.clear()
    }

    private fun loadVisibleThumbnails() {
        if(!thumbnailForeground || preferences.mode != PortalPreferences.Mode.JB ||
            work?.isActive == true || preview != null || isFinishing || isDestroyed) return
        val visible = thumbnailBindings.filter {
            it.frame.width > 0 && it.frame.height > 0 && it.frame.getLocalVisibleRect(thumbnailVisibleRect)
        }
        val keys = visible.mapTo(hashSetOf()) { it.key }
        thumbnailLoads.keys.toList().filter { it !in keys }.forEach { key -> thumbnailLoads.remove(key)?.cancel() }
        thumbnailBindings.filter { it.key !in keys && it.displayed }.forEach { showThumbnail(it, null) }
        for(binding in visible) {
            state.thumbnails.get(binding.key)?.let { showThumbnail(binding, it) }
            if(binding.displayed) continue
            if(binding.key in state.missingThumbnails) { showThumbnail(binding, null); continue }
            if(binding.key in thumbnailLoads) continue
            val generation = thumbnailGeneration
            val host = preferences.jbHost
            val port = preferences.ftpPort
            val root = preferences.galleryRoot
            val load = ThumbnailLoad()
            thumbnailLoads[binding.key] = load
            load.job = lifecycleScope.launch {
                try {
                    val bitmap = thumbnailSlots.withPermit {
                        withContext(Dispatchers.IO) {
                            val client = GalleryFtpClient(host, port, root, 6000)
                            load.client = client
                            try {
                                coroutineContext.ensureActive()
                                if(load.cancelled || generation != thumbnailGeneration)
                                    throw CancellationException("Thumbnail no longer visible.")
                                client.thumbnailImage(binding.capture) { bytes ->
                                    coroutineContext.ensureActive()
                                    GalleryImageDecoder.decode(bytes, 512)
                                }
                            } finally { client.close(); load.client = null }
                        }
                    }
                    if(generation == thumbnailGeneration && thumbnailForeground && !load.cancelled) {
                        state.thumbnails.put(binding.key, bitmap)
                        if(binding in thumbnailBindings && binding.frame.getLocalVisibleRect(thumbnailVisibleRect))
                            showThumbnail(binding, bitmap)
                    }
                } catch(cancelled: CancellationException) {
                    throw cancelled
                } catch(error: Exception) {
                    if(generation == thumbnailGeneration && thumbnailForeground && !load.cancelled) {
                        state.rememberMissingThumbnail(binding.key)
                        if(binding in thumbnailBindings) showThumbnail(binding, null)
                    }
                } finally {
                    if(thumbnailLoads[binding.key] === load) thumbnailLoads.remove(binding.key)
                }
            }
        }
    }

    private fun toggle(capture: GalleryCapture) {
        if(!state.selected.add(capture.path)) state.selected.remove(capture.path)
        render()
    }

    private fun captureActions(capture: GalleryCapture) {
        if(work?.isActive == true) return
        MaterialAlertDialogBuilder(this).setTitle(capture.name)
            .setItems(arrayOf("Preview", "Transfer to device",
                if(capture.path in state.selected) "Deselect" else "Select")) { _, index ->
                when(index) {
                    0 -> preview(capture)
                    1 -> transfer(listOf(capture))
                    else -> toggle(capture)
                }
            }.setNegativeButton("Back", null).show()
    }

    private fun refresh() = beginWork("Loading gallery") { id ->
        state.missingThumbnails.clear()
        val result = withContext(Dispatchers.IO) {
            cleanupIncomplete()
            withClient(id) { client ->
                client.scan { found -> publish(id, "$found captures found", null) }
            }
        }
        state.result = result
        val existing = result.captures.mapTo(hashSetOf()) { it.path }
        state.selected.retainAll(existing)
        render()
    }

    private fun <T> withClient(id: Int, block: (GalleryFtpClient) -> T): T {
        if(operation != id) throw CancellationException("Operation cancelled.")
        val client = GalleryFtpClient(preferences.jbHost, preferences.ftpPort, preferences.galleryRoot)
        clients.add(client)
        return try {
            if(operation != id) throw CancellationException("Operation cancelled.")
            block(client)
        } finally { clients.remove(client); client.close() }
    }

    private fun beginWork(title: String, task: suspend (Int) -> Unit) {
        cancelThumbnails()
        cancelWork(false)
        val id = ++operation
        showProgress(title)
        work = lifecycleScope.launch {
            try { task(id) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                if(!isFinishing && !isDestroyed) message(title,
                    error.message ?: "The operation could not be completed.")
            } finally {
                if(id == operation) {
                    progressOverlay?.let { page.root.removeView(it) }
                    progressOverlay = null; progressText = null; progressBar = null
                    work = null
                    requestThumbnailPass()
                }
            }
        }
    }

    private fun showProgress(title: String) {
        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(0xcc000000.toInt())
            isClickable = true; isFocusable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = PortalUi.surface(this@GalleryActivity)
            setPadding(page.dp(24), page.dp(24), page.dp(24), page.dp(24))
        }
        panel.addView(PortalUi.text(this, title, 22f))
        progressText = PortalUi.text(this, "Connecting to PS5…", 15f, true).apply {
            setPadding(0, page.dp(16), 0, page.dp(14))
        }.also { panel.addView(it) }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 1000
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, page.dp(8))) }
        panel.addView(PortalUi.primary(this, "Cancel") { cancelWork(true) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = page.dp(20) })
        backdrop.addView(panel, FrameLayout.LayoutParams(
            min(page.dp(560), (resources.displayMetrics.widthPixels * 0.9f).toInt()), -2, Gravity.CENTER))
        page.root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        progressOverlay = backdrop
        panel.getChildAt(panel.childCount - 1).requestFocus()
    }

    private var lastProgressAt = 0L
    private fun publish(id: Int, title: String, fraction: Float?) {
        if(operation != id) return
        val now = SystemClock.elapsedRealtime()
        if(now - lastProgressAt < 100 && fraction != 1f) return
        lastProgressAt = now
        mainHandler.post {
            if(operation == id && !isFinishing && !isDestroyed) {
                progressText?.text = title
                progressBar?.isIndeterminate = fraction == null
                if(fraction != null) progressBar?.progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
            }
        }
    }

    private fun cancelWork(announce: Boolean) {
        cancelThumbnails()
        operation++
        clients.toList().forEach { it.close() }
        clients.clear()
        pendingPreviews.values.toList().forEach { it.delete() }
        pendingPreviews.clear()
        work?.cancel(); work = null
        if(preview == null) { previewFile?.delete(); previewFile = null }
        progressOverlay?.let { page.root.removeView(it) }
        progressOverlay = null; progressText = null; progressBar = null
        if(announce) message("Cancelled", if(completedTransfers > 0)
            "$completedTransfers captures finished transferring. The current partial copy is being removed."
            else "The operation has been cancelled. Any partial copy is being removed.")
        if(announce) requestThumbnailPass()
    }

    private fun chooseDestination(pending: List<GalleryCapture>) {
        state.pendingTransfer = pending
        val initial = preferences.galleryTree ?: DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Relay")
        chooseFolder.launch(initial)
    }

    private fun transfer(captures: List<GalleryCapture>) {
        val tree = preferences.galleryTree
        if(tree == null || contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            chooseDestination(captures)
            return
        }
        completedTransfers = 0
        beginWork("Transferring captures") { id ->
            val errors = withContext(Dispatchers.IO) {
                cleanupIncomplete()
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val existing = children(tree)
                val names = existing.values.toMutableSet()
                val documentIds = existing.keys.toMutableSet()
                val failures = mutableListOf<String>()
                for((index, capture) in captures.withIndex()) {
                    coroutineContext.ensureActive()
                    val name = GalleryFtpProtocol.uniqueName(capture.name, names)
                    var created: Uri? = null
                    var owned = false
                    try {
                        val document = DocumentsContract.createDocument(contentResolver, parent, capture.mimeType, name)
                            ?: throw IOException("The destination could not create $name.")
                        created = document
                        val documentId = DocumentsContract.getDocumentId(document)
                        if(documentId in documentIds) throw IOException("The destination returned an existing file; it was left unchanged.")
                        owned = true
                        documentIds.add(documentId)
                        addIncomplete(document)
                        var receipt: GalleryDownload? = null
                        var lastFailure: Exception? = null
                        repeat(2) { attempt ->
                            if(receipt == null) {
                                coroutineContext.ensureActive()
                                try {
                                    receipt = withClient(id) { client ->
                                        contentResolver.openOutputStream(document, "wt")?.use { stream ->
                                            client.download(capture, stream) { copied, expected ->
                                                publish(id, "${index + 1} / ${captures.size} · ${capture.name}\n" +
                                                    "${sizeLabel(copied)} / ${sizeLabel(expected)}" +
                                                    if(attempt > 0) "\nReconnected" else "",
                                                    if(expected > 0) copied.toFloat() / expected else 1f)
                                            }
                                        } ?: throw IOException("The destination file could not be opened.")
                                    }
                                } catch(error: Exception) {
                                    coroutineContext.ensureActive()
                                    lastFailure = error
                                }
                            }
                        }
                        val finished = receipt ?: throw (lastFailure ?: IOException("Transfer failed."))
                        val savedSize = documentSize(document)
                        if(savedSize != null && savedSize != finished.bytes)
                            throw IOException("The destination reported ${sizeLabel(savedSize)} after ${sizeLabel(finished.bytes)} was copied.")
                        if(!removeIncomplete(document))
                            throw IOException("The completed transfer could not be recorded safely.")
                        names.add(documentName(document) ?: name)
                        completedTransfers++
                    } catch(error: Exception) {
                        if(owned && created != null) deleteIncomplete(created)
                        coroutineContext.ensureActive()
                        failures.add("${capture.name}: ${error.message ?: "Transfer failed"}")
                    }
                }
                failures
            }
            message("Transfer complete", "$completedTransfers of ${captures.size} captures saved to ${folderLabel(tree)}." +
                if(errors.isEmpty()) "" else "\n\n${errors.take(8).joinToString("\n")}" +
                    if(errors.size > 8) "\n${errors.size - 8} more files could not be transferred." else "")
        }
    }

    private fun preview(capture: GalleryCapture) {
        completedTransfers = 0
        beginWork("Opening preview") { id ->
            if(capture.kind == CaptureKind.PHOTO) {
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = withClient(id) { it.imagePreview(capture) }
                    GalleryImageDecoder.decode(bytes, 2048)
                }
                showImagePreview(capture, bitmap)
            } else {
                val cached = withContext(Dispatchers.IO) {
                    val available = cacheDir.usableSpace - 64L * 1024 * 1024
                    if(capture.size != null && capture.size > available)
                        throw IOException("There is not enough temporary storage for this video. Transfer it to your chosen folder instead.")
                    val target = File.createTempFile("relay-preview-", "." + capture.name.substringAfterLast('.'), cacheDir)
                    pendingPreviews[id] = target
                    try {
                        withClient(id) { client ->
                            target.outputStream().use { stream ->
                                client.download(capture, stream) { copied, expected ->
                                    if(expected > available) throw IOException("There is not enough temporary storage for this preview.")
                                    publish(id, "Loading ${capture.name}\n${sizeLabel(copied)} / ${sizeLabel(expected)}",
                                        if(expected > 0) copied.toFloat() / expected else 1f)
                                }
                            }
                        }
                        target
                    } catch(error: Exception) {
                        pendingPreviews.remove(id); target.delete(); throw error
                    }
                }
                pendingPreviews.remove(id)
                previewFile = cached
                showVideoPreview(capture, cached)
            }
        }
    }

    private fun showImagePreview(capture: GalleryCapture, bitmap: Bitmap) {
        val image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER }
        showPreview(capture, image) { image.setImageDrawable(null); bitmap.recycle() }
    }

    private fun showVideoPreview(capture: GalleryCapture, file: File) {
        val video = VideoView(this)
        showPreview(capture, video) {
            video.stopPlayback(); previewVideo = null
            file.delete(); previewFile = null
            com.metallic.chiaki.main.PortalMenuAudio.suppressMusic(this,false)
        }
        // Dismiss the previous preview before it can clear the new video's suppression.
        previewVideo = video
        com.metallic.chiaki.main.PortalMenuAudio.suppressMusic(this,true)
        video.setMediaController(MediaController(this).apply { setAnchorView(video) })
        video.setOnErrorListener { _, _, _ ->
            message("Preview unavailable", "Android could not play this capture. The original can still be transferred.")
            true
        }
        video.setVideoPath(file.absolutePath)
        video.setOnPreparedListener { video.start() }
    }

    private fun showPreview(capture: GalleryCapture, media: View, onClose: () -> Unit) {
        cancelThumbnails()
        preview?.dismiss()
        fun immersive(previewWindow: Window) {
            previewWindow.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                attributes = attributes.apply {
                    screenBrightness = this@GalleryActivity.window.attributes.screenBrightness
                    dimAmount = 0f
                    if(Build.VERSION.SDK_INT >= 28)
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                decorView.setPadding(0, 0, 0, 0)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                WindowCompat.getInsetsController(this, decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        val dialog = object: AppCompatDialog(this, R.style.PortalGalleryPreviewWindow) {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                super.onWindowFocusChanged(hasFocus)
                if(hasFocus) window?.let(::immersive)
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                when(event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                        if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) dismiss()
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                            currentFocus?.performClick()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        val previewPage = PortalPage(this, capture.name) { dialog.dismiss() }
        previewPage.heading.maxLines = 1
        previewPage.heading.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        val mediaFrame = object: FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // ScrollView first measures with unlimited height. Suppress the
                // image's intrinsic height in that pass so fillViewport can give
                // this weighted child the real remainder after the footer.
                val boundedHeight = if(View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY) else heightMeasureSpec
                super.onMeasure(widthMeasureSpec, boundedHeight)
            }
        }.apply {
            setBackgroundColor(0xff000000.toInt())
            addView(media, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        // PortalPage's fill-viewport ScrollView measures the body against its
        // actual inset-aware space. Weight gives media only the remainder after
        // the footer; compact windows can scroll the footer instead of clipping it.
        previewPage.body.addView(mediaFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val transferButton = PortalUi.primary(this, "Transfer original") {
            dialog.dismiss(); transfer(listOf(capture))
        }
        previewPage.add(transferButton)
        dialog.setContentView(previewPage.root)
        dialog.setOnDismissListener {
            onClose()
            if(preview == dialog) preview = null
            if(!isFinishing && !isDestroyed) PortalUi.fullscreen(this)
            requestThumbnailPass()
        }
        dialog.window?.let(::immersive)
        dialog.show()
        dialog.window?.let(::immersive)
        ViewCompat.requestApplyInsets(previewPage.root)
        if(dialog.currentFocus == null) transferButton.requestFocus()
        preview = dialog
    }

    private fun children(tree: Uri): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = linkedMapOf<String, String>()
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while(cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
        } ?: throw IOException("The selected destination folder cannot be read.")
        return result
    }

    private fun documentSize(uri: Uri): Long? =
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if(it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { size -> size >= 0 } else null
        }

    private fun documentName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        }

    private fun addIncomplete(uri: Uri) = synchronized(journal) {
        val files = journal.getStringSet("files", emptySet()).orEmpty().toMutableSet()
        files.add(uri.toString())
        if(!journal.edit().putStringSet("files", files).commit())
            throw IOException("Cannot track this transfer safely.")
    }

    private fun removeIncomplete(uri: Uri): Boolean = synchronized(journal) {
        val files = journal.getStringSet("files", emptySet()).orEmpty().toMutableSet()
        files.remove(uri.toString())
        journal.edit().putStringSet("files", files).commit()
    }

    private fun deleteIncomplete(uri: Uri) {
        if(runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false))
            removeIncomplete(uri)
    }

    private fun cleanupIncomplete() {
        journal.getStringSet("files", emptySet()).orEmpty().toList().forEach { value ->
            runCatching { deleteIncomplete(Uri.parse(value)) }
        }
    }

    private fun folderLabel(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri).let {
            if(it.startsWith("primary:")) "/storage/emulated/0/" + it.removePrefix("primary:") else it
        }
    }.getOrDefault("Selected device folder")

    private fun message(title: String, text: String) {
        if(!isFinishing && !isDestroyed)
            MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton("Done", null).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if(work?.isActive == true) cancelWork(true) else finish()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        thumbnailForeground = false
        cancelThumbnails()
        if(page.scroll.viewTreeObserver.isAlive)
            page.scroll.viewTreeObserver.removeOnScrollChangedListener(thumbnailScrollListener)
        cancelWork(false)
        preview?.dismiss()
        previewVideo?.stopPlayback()
        previewFile?.delete()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val PAGE_SIZE = 60
        fun sizeLabel(bytes: Long?): String {
            if(bytes == null) return "Size unknown"
            if(bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var index = -1
            do { value /= 1024; index++ } while(value >= 1024 && index < units.lastIndex)
            return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
        }
    }
}

