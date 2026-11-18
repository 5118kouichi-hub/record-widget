package com.example.recordwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Updates the app from this project's GitHub releases (tag "build-N" = versionCode N).
 *
 * The first update asks the user once (the app was installed from a browser). After that
 * the app is its own installer, so on Android 12+ later updates install without asking.
 * Never installs while a record is playing: installing restarts the app and would cut the music.
 */
object Updater {
    private const val TAG = "Updater"
    private const val API = "https://api.github.com/repos/5118kouichi-hub/record-widget/releases/latest"
    private const val APK_NAME = "RecordWidget.apk"
    private const val PREFS = "updater"
    private const val KEY_AUTO = "auto"
    private const val KEY_STATUS = "status"
    private const val JOB_ID = 4201
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4202
    private const val TIMEOUT_MS = 20_000

    private val worker = Executors.newSingleThreadExecutor()

    class Release(val build: Long, val apkUrl: String, val size: Long)

    fun currentBuild(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()
        schedule(ctx)
    }

    /** Last result line shown in the app ("最新です", errors...). */
    fun status(ctx: Context): String? = prefs(ctx).getString(KEY_STATUS, null)

    private fun setStatus(ctx: Context, s: String) = prefs(ctx).edit().putString(KEY_STATUS, s).apply()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Background check about every 6 hours on any network (kept across reboots). */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!autoEnabled(ctx)) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        try {
            js.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "cannot schedule update checks", e)
        }
    }

    /**
     * Checks GitHub and installs a newer build. [manual]: started from the app's button
     * (reports every outcome; installs even while playing). [onStatus] runs on a worker thread.
     */
    fun check(ctx: Context, manual: Boolean, onStatus: (String) -> Unit = {}, onDone: () -> Unit = {}) {
        val app = ctx.applicationContext
        worker.execute {
            try {
                fun report(s: String) {
                    setStatus(app, s)
                    onStatus(s)
                }
                val latest = latest()
                if (latest == null) {
                    report(app.getString(R.string.update_check_failed))
                    return@execute
                }
                val current = currentBuild(app)
                if (latest.build <= current) {
                    report(app.getString(R.string.update_latest, current))
                    return@execute
                }
                if (!manual && PlaybackService.isRunning) {
                    Log.i(TAG, "update ${latest.build} waits: playing")
                    return@execute // next check, when nothing plays
                }
                if (!app.packageManager.canRequestPackageInstalls()) {
                    report(app.getString(R.string.update_need_permission))
                    askInstallPermission(app, manual)
                    return@execute
                }
                report(app.getString(R.string.update_downloading, latest.build))
                val apk = download(app, latest) ?: run {
                    report(app.getString(R.string.update_download_failed))
                    return@execute
                }
                report(app.getString(R.string.update_installing, latest.build))
                install(app, apk)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                onStatus(app.getString(R.string.update_check_failed))
            } finally {
                onDone()
            }
        }
    }

    private fun latest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == APK_NAME) {
                    return Release(build, a.getString("browser_download_url"), a.optLong("size", -1))
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "cannot read latest release", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun download(ctx: Context, r: Release): File? {
        val out = File(ctx.cacheDir, "update.apk")
        var url = r.apkUrl
        repeat(5) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                val code = conn.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), conn.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (code != 200) return null
                conn.inputStream.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                if (r.size > 0 && out.length() != r.size) {
                    out.delete()
                    return null
                }
                return out
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                out.delete()
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Silent when this app is its own installer (true after the first update).
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                ctx,
                id,
                Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // installer adds extras
            )
            session.commit(result.intentSender)
        }
    }

    /** The system wants the user to confirm: open it now if we can, and leave a notification. */
    internal fun confirm(ctx: Context, confirmIntent: Intent) {
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(confirmIntent) // works while the app's screen is open
        } catch (e: Exception) {
            Log.i(TAG, "confirmation needs a tap", e)
        }
        notify(
            ctx,
            ctx.getString(R.string.update_notif_title),
            ctx.getString(R.string.update_notif_confirm),
            PendingIntent.getActivity(ctx, 1, confirmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
    }

    private fun askInstallPermission(ctx: Context, manual: Boolean) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (manual) {
            try {
                ctx.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot open install permission screen", e)
            }
        }
        notify(
            ctx,
            ctx.getString(R.string.update_notif_title),
            ctx.getString(R.string.update_need_permission),
            PendingIntent.getActivity(ctx, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
    }

    private fun notify(ctx: Context, title: String, text: String, tap: PendingIntent) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notifications not allowed", e)
        }
    }

    internal fun installFailed(ctx: Context, message: String?) {
        setStatus(ctx, ctx.getString(R.string.update_install_failed, message ?: ""))
    }
}

/** Result of the install session. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) Updater.confirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {} // the new version is starting
            else -> Updater.installFailed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** Periodic background check (scheduled by [Updater.schedule]). */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.check(this, manual = false, onDone = { jobFinished(params, false) })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
