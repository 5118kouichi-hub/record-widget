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
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Tells the user about new versions on this project's GitHub releases (tag "build-N" =
 * versionCode N) and opens the download. It never downloads or installs APKs itself: an app
 * that installs apps by itself looks like malware to Play Protect.
 */
object Updater {
    private const val TAG = "Updater"
    private const val API = "https://api.github.com/repos/5118kouichi-hub/record-widget/releases/latest"
    const val DOWNLOAD_URL = "https://github.com/5118kouichi-hub/record-widget/releases/latest/download/RecordWidget.apk"
    private const val PREFS = "updater"
    private const val KEY_AUTO = "auto"
    private const val KEY_STATUS = "status"
    private const val KEY_NOTIFIED = "notifiedBuild"
    private const val JOB_ID = 4201
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4202
    private const val TIMEOUT_MS = 20_000

    private val worker = Executors.newSingleThreadExecutor()

    fun currentBuild(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()
        schedule(ctx)
    }

    /** Last result line shown in the app. */
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

    enum class Mode {
        /** "今すぐ確認": report everything; a new version opens the download right away. */
        MANUAL,

        /** App just opened: only look and say so in the app. */
        LOOK,

        /** Background job: one notification per new version. */
        BACKGROUND,
    }

    /** [onStatus] runs on a worker thread. */
    fun check(ctx: Context, mode: Mode, onStatus: (String) -> Unit = {}, onDone: () -> Unit = {}) {
        val app = ctx.applicationContext
        worker.execute {
            fun report(s: String) {
                setStatus(app, s)
                onStatus(s)
            }
            try {
                val latest = when (val r = latest()) {
                    is Lookup.Found -> r.build
                    Lookup.RateLimited -> return@execute report(app.getString(R.string.update_rate_limited))
                    Lookup.Failed -> return@execute report(app.getString(R.string.update_check_failed))
                }
                val current = currentBuild(app)
                if (latest <= current) {
                    report(app.getString(R.string.update_latest, current))
                    return@execute
                }
                report(app.getString(R.string.update_available, latest))
                when (mode) {
                    Mode.LOOK -> {}
                    Mode.MANUAL -> openDownload(app)
                    Mode.BACKGROUND -> {
                        if (prefs(app).getLong(KEY_NOTIFIED, -1L) != latest) {
                            prefs(app).edit().putLong(KEY_NOTIFIED, latest).apply()
                            notifyAvailable(app, latest)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                report(app.getString(R.string.update_check_failed))
            } finally {
                onDone()
            }
        }
    }

    private sealed class Lookup {
        class Found(val build: Long) : Lookup()
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
            Lookup.Found(build)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read latest release", e)
            Lookup.Failed
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadIntent() =
        Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the download in the browser (the user then installs it as usual). */
    fun openDownload(ctx: Context) {
        try {
            ctx.startActivity(downloadIntent())
        } catch (e: Exception) {
            Log.w(TAG, "no browser", e)
        }
    }

    private fun notifyAvailable(ctx: Context, build: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val text = ctx.getString(R.string.update_notif_text, build)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(ctx.getString(R.string.update_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(ctx, 1, downloadIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notifications not allowed", e)
        }
    }

    /** Runs in the new version right after the user installed it. */
    internal fun onUpdated(ctx: Context) {
        setStatus(ctx, ctx.getString(R.string.update_done, currentBuild(ctx)))
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        schedule(ctx)
        RecordWidget.refresh(ctx)
    }
}

/** The app was just updated. */
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
