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
    private const val KEY_PROMPTED = "promptedBuild"
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

    /** Screens of this app currently in front (main thread). Silent installs wait while > 0. */
    @Volatile var resumedScreens = 0

    /** Imports running in the app (main thread). Silent installs wait while > 0. */
    @Volatile var busyImports = 0

    enum class Mode {
        /** "今すぐ確認": report everything, install now (even while playing). */
        MANUAL,

        /** App just opened: only look, and say if a new version is ready. Never installs. */
        LOOK,

        /** Background job: install only when nobody is using the app or listening. */
        BACKGROUND,
    }

    /** Checks GitHub and, depending on [mode], installs a newer build. [onStatus] runs on a worker thread. */
    fun check(ctx: Context, mode: Mode, onStatus: (String) -> Unit = {}, onDone: () -> Unit = {}) {
        val manual = mode == Mode.MANUAL
        val app = ctx.applicationContext
        worker.execute {
            try {
                fun report(s: String) {
                    setStatus(app, s)
                    onStatus(s)
                }
                val latest = when (val r = latest()) {
                    is Lookup.Found -> r.release
                    Lookup.RateLimited -> return@execute report(app.getString(R.string.update_rate_limited))
                    Lookup.Failed -> return@execute report(app.getString(R.string.update_check_failed))
                }
                val current = currentBuild(app)
                if (latest.build <= current) {
                    report(app.getString(R.string.update_latest, current))
                    return@execute
                }
                if (mode == Mode.LOOK) {
                    report(app.getString(R.string.update_available, latest.build))
                    return@execute
                }
                if (mode == Mode.BACKGROUND &&
                    (PlaybackService.isPlaying || resumedScreens > 0 || busyImports > 0)
                ) {
                    Log.i(TAG, "update ${latest.build} waits: in use")
                    return@execute // next check
                }
                // In the background, ask (permission / confirmation) only once per new build.
                val firstAsk = prefs(app).getLong(KEY_PROMPTED, -1L) != latest.build
                if (!app.packageManager.canRequestPackageInstalls()) {
                    report(app.getString(R.string.update_need_permission))
                    if (manual || firstAsk) askInstallPermission(app, manual)
                    prefs(app).edit().putLong(KEY_PROMPTED, latest.build).apply()
                    return@execute
                }
                if (!manual && !firstAsk && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    return@execute // would only ask again; "今すぐ確認" installs it
                }
                prefs(app).edit().putLong(KEY_PROMPTED, latest.build).apply()
                report(app.getString(R.string.update_downloading, latest.build))
                val apk = download(app, latest) ?: run {
                    report(app.getString(R.string.update_download_failed))
                    return@execute
                }
                report(app.getString(R.string.update_installing, latest.build))
                install(app, apk)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                val s = app.getString(R.string.update_check_failed)
                setStatus(app, s)
                onStatus(s)
            } finally {
                onDone()
            }
        }
    }

    private sealed class Lookup {
        class Found(val release: Release) : Lookup()
        object RateLimited : Lookup()
        object Failed : Lookup()
    }

    private fun latest(): Lookup {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            when (conn.responseCode) {
                200 -> {}
                403, 429 -> return Lookup.RateLimited // GitHub allows ~60 checks an hour per network
                else -> return Lookup.Failed
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return Lookup.Failed
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == APK_NAME) {
                    return Lookup.Found(Release(build, a.getString("browser_download_url"), a.optLong("size", -1)))
                }
            }
            Lookup.Failed
        } catch (e: Exception) {
            Log.w(TAG, "cannot read latest release", e)
            Lookup.Failed
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
        // One update at a time: drop sessions left by an earlier, unconfirmed attempt.
        for (old in installer.mySessions) {
            try {
                installer.abandonSession(old.sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "cannot abandon old session", e)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                apk.delete()
                val result = PendingIntent.getBroadcast(
                    ctx,
                    id,
                    Intent(ctx, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // installer adds extras
                )
                session.commit(result.intentSender)
            } catch (e: Exception) {
                session.abandon()
                apk.delete()
                throw e
            }
        }
    }

    /** The system wants the user to confirm: open it now if we can, and leave a notification. */
    internal fun confirm(ctx: Context, confirmIntent: Intent) {
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Only over our own screen; never pop the system dialog up in the middle of another app.
        if (resumedScreens > 0) {
            try {
                ctx.startActivity(confirmIntent)
            } catch (e: Exception) {
                Log.i(TAG, "confirmation needs a tap", e)
            }
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

    /** Runs in the new version right after an update. */
    internal fun onUpdated(ctx: Context) {
        setStatus(ctx, ctx.getString(R.string.update_done, currentBuild(ctx)))
        schedule(ctx)
        RecordWidget.refresh(ctx)
    }
}

/** Result of the install session. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) Updater.confirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {} // this process is about to be replaced
            else -> Updater.installFailed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** The app was just updated (the update itself ended the old process). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.onUpdated(context)
    }
}

/** Periodic background check (scheduled by [Updater.schedule]). */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.check(this, Updater.Mode.BACKGROUND, onDone = { jobFinished(params, false) })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
