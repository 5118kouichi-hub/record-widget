package com.example.recordwidget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: TrackStore
    private lateinit var adapter: TrackAdapter
    private lateinit var addButton: Button
    private lateinit var progressText: TextView
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = TrackStore(filesDir)

        addButton = findViewById(R.id.addButton)
        progressText = findViewById(R.id.progressText)
        emptyText = findViewById(R.id.emptyText)
        list = findViewById(R.id.trackList)

        adapter = TrackAdapter(::confirmRename, ::confirmDelete, ::moveTrack)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        addButton.setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
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
                    try {
                        localStore.add(Track(id, title, fileName))
                    } catch (e: IOException) {
                        out.delete()
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

    private fun finishOne() {
        pendingImports--
        updateBusy()
        if (!isFinishing && !isDestroyed) refresh()
    }

    private fun updateBusy() {
        val busy = pendingImports > 0
        addButton.isEnabled = !busy
        progressText.visibility = if (busy) View.VISIBLE else View.GONE
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
        adapter.submit(tracks)
        emptyText.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
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
