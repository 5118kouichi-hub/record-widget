package com.example.recordwidget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: TrackStore
    private lateinit var screen: MainScreen

    private val executor = Executors.newSingleThreadExecutor()
    private var pendingImports = 0 // main thread only

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) startImport(uri)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied is fine: playback works, only the notification is hidden.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(UiStyle.themeRes(this)) // One UI-like (default) or iOS-like
        super.onCreate(savedInstanceState)
        store = TrackStore(filesDir)

        screen = MainScreen(
            this,
            UiStyle.current(this),
            object : MainScreen.Callbacks {
                override fun onAddVideo() =
                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                override fun onAddUrl() = askUrl(null)
                override fun onFloatingSwitch(on: Boolean) = this@MainActivity.onFloatingSwitch(on)
                override fun onToggle() = PlaybackService.command(this@MainActivity, PlaybackService.ACTION_TOGGLE)
                override fun onPrevious() = PlaybackService.command(this@MainActivity, PlaybackService.ACTION_PREV)
                override fun onNext() = PlaybackService.command(this@MainActivity, PlaybackService.ACTION_NEXT)
                override fun onRename(t: Track) = confirmRename(t)
                override fun onDelete(t: Track) = confirmDelete(t)
                override fun onMove(from: Int, to: Int) = moveTrack(from, to)
                override fun isFavorite(t: Track) = PlaybackState.isFavorite(this@MainActivity, t.id)
                override fun onFavorite(t: Track) {
                    PlaybackState.setFavorite(this@MainActivity, t.id, !isFavorite(t))
                    changed() // list + playing queue ("favorites only")
                }
                override fun volume() = PlaybackState.volume(this@MainActivity)
                override fun onVolume(v: Float) {
                    PlaybackState.setVolume(this@MainActivity, v)
                    PlaybackService.floatingChanged(this@MainActivity) // applies it to the player
                }
                override fun autoUpdateOn() = Updater.autoEnabled(this@MainActivity)
                override fun onAutoUpdate(on: Boolean) = Updater.setAuto(this@MainActivity, on)
                override fun onCheckUpdate() {
                    screen.setUpdateStatus(getString(R.string.update_checking))
                    Updater.check(
                        this@MainActivity,
                        Updater.Mode.MANUAL,
                        onStatus = { msg -> runOnUiThread { if (!isDestroyed) screen.setUpdateStatus(msg) } },
                    )
                }
            },
        )
        screen.install()
        updateBusy()
        screen.setUpdateStatus(
            getString(R.string.update_current, Updater.currentBuild(this)) + "\n" +
                (Updater.status(this)?.let { it + "\n" } ?: "") + getString(R.string.update_footer),
        )
        Updater.schedule(this)
        // Opening the app: only look for a new version (installing would close this screen).
        if (Updater.autoEnabled(this) && savedInstanceState == null) {
            Updater.check(
                this,
                Updater.Mode.LOOK,
                onStatus = { msg -> runOnUiThread { if (!isDestroyed) screen.setUpdateStatus(msg) } },
            )
        }

        refresh()
        requestNotificationPermissionOnce()
        // On recreation the original intent is stale; it was already handled.
        if (savedInstanceState == null) handleSendIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown() // queued/running work still finishes
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("main", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleSendIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        if (i.type == "text/plain") {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            i.action = null
            i.removeExtra(Intent.EXTRA_TEXT)
            askUrl(WebLinks.firstUrl(text) ?: text?.trim())
            return
        }
        if (i.type?.startsWith("video/") != true) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        // Consume so a later onNewIntent/relaunch does not import again.
        i.action = null
        i.removeExtra(Intent.EXTRA_STREAM)
        if (uri != null) startImport(uri)
    }

    private fun startImport(uri: Uri) {
        pendingImports++
        updateBusy()
        val app = applicationContext
        try {
            executor.execute { runImport(app, uri) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pendingImports--
            updateBusy()
        }
    }

    /** Background thread. Always ends with finishOne() on the UI thread. */
    private fun runImport(app: Context, uri: Uri) {
        var message: String
        try {
            val id = UUID.randomUUID().toString()
            val fileName = "$id.m4a"
            val out = File(app.filesDir, "tracks/$fileName")
            out.parentFile?.mkdirs()
            val localStore = TrackStore(app.filesDir)
            val title = displayTitle(app, uri)
                ?: app.getString(R.string.default_track_title, localStore.all().size + 1)
            message = when (val r = extractAudio(app, uri, out)) {
                is ExtractResult.Ok -> {
                    Thumbnails.fromVideo(app, uri, localStore.thumbFor(id)) // optional
                    try {
                        localStore.add(Track(id, title, fileName))
                    } catch (e: IOException) {
                        out.delete()
                        localStore.thumbFor(id).delete()
                        throw e
                    }
                    PlaybackService.reload(app)
                    app.getString(R.string.added)
                }
                ExtractResult.NoAudio -> app.getString(R.string.no_audio)
                is ExtractResult.Unsupported -> app.getString(R.string.unsupported_format, r.mime)
                is ExtractResult.Failed -> app.getString(R.string.extract_failed, r.msg)
            }
        } catch (e: Exception) {
            message = app.getString(R.string.extract_failed, e.message ?: e.javaClass.simpleName)
        }
        runOnUiThread {
            finishOne()
            toast(message)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the permission screen (or the permission was revoked meanwhile).
        screen.setFloatingChecked(FloatingRecord.isEnabled(this) && Settings.canDrawOverlays(this))
        PlaybackService.floatingChanged(this)
        refresh() // the order may have changed from the floating record's list
    }

    /** The floating record's list may have reordered tracks while this screen stayed open. */
    /** The big player follows the service (or shows where playback will resume). */
    private val nowPlayingObserver: (PlaybackService.Companion.NowPlaying) -> Unit = { np ->
        if (!isDestroyed) showNowPlaying(np)
    }

    private var thumbFor: Pair<String, android.graphics.Bitmap?>? = null

    private fun showNowPlaying(np: PlaybackService.Companion.NowPlaying = PlaybackService.nowPlaying) {
        val tracks = PlaybackState.queueTracks(this)
        val track = tracks.firstOrNull { it.id == np.trackId } ?: PlaybackState.resumeTrack(this, tracks)
        val thumb = track?.let { t ->
            thumbFor?.takeIf { it.first == t.id }?.second ?: store.thumbFor(t.id).takeIf { it.isFile }
                ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
                .also { thumbFor = t.id to it }
        }
        screen.showNowPlaying(np.title ?: track?.title, thumb, np.turning, tracks.isNotEmpty())
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.observe(nowPlayingObserver)
    }

    override fun onStop() {
        PlaybackService.unobserve(nowPlayingObserver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    override fun onPause() {
        super.onPause()
    }

    private fun onFloatingSwitch(on: Boolean) {
        FloatingRecord.setEnabled(this, on)
        if (on && !Settings.canDrawOverlays(this)) {
            toast(getString(R.string.floating_need_permission))
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: android.content.ActivityNotFoundException) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                } catch (e2: android.content.ActivityNotFoundException) {
                    toast(getString(R.string.floating_not_supported))
                }
            }
            return // onResume finishes the job
        }
        PlaybackService.floatingChanged(this)
    }

    /** Asks for a web link (prefilled when shared from another app). */
    private fun askUrl(prefill: String?) {
        val builder = AlertDialog.Builder(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val urlEdit = EditText(builder.context).apply {
            hint = getString(R.string.url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            if (prefill != null) setText(prefill)
        }
        val titleEdit = EditText(builder.context).apply {
            hint = getString(R.string.url_title_hint)
            setSingleLine()
        }
        val box = LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(urlEdit)
            addView(titleEdit)
        }
        builder
            .setTitle(R.string.url_dialog_title)
            .setMessage(R.string.url_dialog_note)
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ ->
                startUrlImport(WebLinks.normalize(urlEdit.text.toString().trim()), titleEdit.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startUrlImport(url: String, title: String) {
        when (WebLinks.check(url)) {
            WebLinks.Check.INVALID -> return toast(getString(R.string.url_invalid))
            WebLinks.Check.UNSUPPORTED_SITE -> return toast(getString(R.string.url_unsupported_site))
            WebLinks.Check.OK -> {}
        }
        pendingImports++
        updateBusy()
        val app = applicationContext
        try {
            executor.execute { runUrlImport(app, url, title) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pendingImports--
            updateBusy()
        }
    }

    /** Background thread: checks the link can be read, saves it, then makes a thumbnail. */
    private fun runUrlImport(app: Context, url: String, typedTitle: String) {
        var message: String
        try {
            val probe = WebLinks.probe(url)
            message = if (probe == null) {
                app.getString(R.string.url_unreadable)
            } else {
                val id = UUID.randomUUID().toString()
                val localStore = TrackStore(app.filesDir)
                val title = typedTitle.ifEmpty { null } ?: WebLinks.nameFromUrl(url)
                    ?: app.getString(R.string.default_web_title, localStore.all().size + 1)
                localStore.add(Track(id, title, "", url))
                PlaybackService.reload(app)
                if (probe.hasVideo) Thumbnails.fromUrlInBackground(app, url, localStore.thumbFor(id))
                app.getString(R.string.added)
            }
        } catch (e: Exception) {
            message = app.getString(R.string.extract_failed, e.message ?: e.javaClass.simpleName)
        }
        runOnUiThread {
            finishOne()
            toast(message)
        }
    }

    private fun finishOne() {
        pendingImports--
        updateBusy()
        if (!isFinishing && !isDestroyed) refresh()
    }

    private fun updateBusy() {
        screen.setBusy(pendingImports > 0)
    }

    private fun displayTitle(ctx: Context, uri: Uri): String? {
        val name = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: return null
        val base = name.substringBeforeLast('.', name).trim()
        return base.ifEmpty { null }
    }

    private fun refresh() {
        val tracks = try {
            store.all()
        } catch (e: Exception) {
            emptyList()
        }
        screen.showTracks(tracks)
        showNowPlaying()
    }

    private fun changed() {
        refresh()
        PlaybackService.reload(this)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    private fun storageError(e: Exception) =
        toast(getString(R.string.storage_error, e.message ?: e.javaClass.simpleName))

    private fun moveTrack(from: Int, to: Int) {
        try {
            store.move(from, to)
            changed()
        } catch (e: IOException) {
            storageError(e)
            refresh()
        }
    }

    private fun confirmRename(t: Track) {
        val builder = AlertDialog.Builder(this)
        val edit = EditText(builder.context).apply {
            setText(t.title)
            setSelection(text.length)
            setSingleLine()
        }
        builder
            .setTitle(R.string.rename_title)
            .setView(edit)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newTitle = edit.text.toString().trim()
                if (newTitle.isNotEmpty() && newTitle != t.title) {
                    try {
                        store.rename(t.id, newTitle)
                        changed()
                    } catch (e: IOException) {
                        storageError(e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(t: Track) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, t.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    store.remove(t.id)
                    changed()
                } catch (e: IOException) {
                    storageError(e)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
