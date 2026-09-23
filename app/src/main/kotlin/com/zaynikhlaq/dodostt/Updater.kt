package com.zaynikhlaq.dodostt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps Dodo on the latest GitHub release without a trip to the browser.
 *
 * Every push to main publishes a signed APK whose tag ends in the build number, and the build
 * number is the versionCode, so "is there something newer" is one integer comparison. A newer APK is
 * downloaded in the background and installed through PackageInstaller the next time Dodo is idle —
 * never mid-dictation, because installing kills the process the bar is running in.
 *
 * From Android 12 an app updating itself can skip the confirmation dialog once the user has let it
 * install apps, so after that one grant updates simply happen. Where Android still insists on asking,
 * the installer's own dialog is offered instead. The APK's signature is checked by the platform: an
 * update not signed with the release key is refused outright.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/ZaynIkhlaq/Dodo-STT/releases/latest"
    private const val ASSET = "dodo-stt.apk"
    private const val JOB_ID = 1
    private const val CHECK_EVERY_MS = 60 * 60 * 1000L
    /** Starting a dictation also checks, but not more often than this. */
    private const val MIN_GAP_MS = 15 * 60 * 1000L
    /** Hopping between fields makes the bar come and go; wait this long before trusting it is idle. */
    private const val SETTLE_MS = 10_000L
    /** A flaky connection gets this many goes before the check gives up until the next one. */
    private const val ATTEMPTS = 4
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    const val ACTION_STATUS = "com.zaynikhlaq.dodostt.INSTALL_STATUS"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** elapsedRealtime of the last check this process made; 0 before the first. */
    @Volatile private var lastCheckAt = 0L
    /** Downloaded, verified, and waiting for a quiet moment to install. */
    @Volatile private var ready: File? = null

    // Main thread only.
    private var dictating = false
    private var checking = false
    /** Newest version seen on GitHub, or 0 before the first successful check. */
    var latest = 0L
        private set
    /** Wall-clock time of the last successful check, or 0. */
    var checkedAt = 0L
        private set
    var lastError: String? = null
        private set

    fun installedVersion(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    fun installedName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()

    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    val isChecking: Boolean get() = checking

    /** A version that is downloaded and will install at the next quiet moment, or null. */
    val waiting: Long? get() = ready?.let { latest.takeIf { it > 0 } }

    /** Registers the hourly background check. Cheap to call repeatedly. */
    fun schedule(c: Context) {
        runCatching {
            val jobs = c.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(CHECK_EVERY_MS)
                    .setPersisted(true)
                    .build()
            )
        }
    }

    /** For the button appearing: checks unless one ran recently. */
    fun maybeCheck(c: Context) {
        if (lastCheckAt != 0L && SystemClock.elapsedRealtime() - lastCheckAt < MIN_GAP_MS) return
        checkNow(c)
    }

    /** Checks GitHub, downloads anything newer, and installs it if nothing is being dictated. */
    fun checkNow(c: Context, done: (() -> Unit)? = null) {
        val app = c.applicationContext
        if (checking) {
            main.post { done?.invoke() }
            return
        }
        checking = true
        lastCheckAt = SystemClock.elapsedRealtime()
        executor.execute {
            val result = runCatching { fetch(app) }
            main.post {
                checking = false
                result.onSuccess {
                    latest = it
                    checkedAt = System.currentTimeMillis()
                    lastError = null
                    installIfIdle(app)
                }.onFailure { lastError = it.message ?: it.javaClass.simpleName }
                done?.invoke()
            }
        }
    }

    /**
     * Dodo reports when a dictation is running. The moment one isn't, a waiting update goes in.
     * (Stranded text doesn't hold it up — that is already on the clipboard.)
     */
    fun setDictating(c: Context, busy: Boolean) {
        if (busy == dictating) return
        dictating = busy
        main.removeCallbacks(settled)
        if (!busy) {
            appContext = c.applicationContext
            main.postDelayed(settled, SETTLE_MS)
        }
    }

    private var appContext: Context? = null
    private val settled = Runnable { appContext?.let(::installIfIdle) }

    /** Returns the newest released version, leaving its verified APK in [ready] if it beats ours. */
    private fun fetch(c: Context): Long {
        val release = JSONObject(get(LATEST))
        val version = release.getString("tag_name").substringAfterLast('.').toLong()
        if (version <= installedVersion(c)) {
            ready = null
            sweep(c, keep = null)
            return version
        }
        val apk = File(c.cacheDir, "update-$version.apk")
        if (!verified(c, apk, version)) {
            val assets = release.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == ASSET }
                ?.getString("browser_download_url")
                ?: error("Release $version has no APK")
            download(url, apk)
            if (!verified(c, apk, version)) {
                apk.delete()
                error("The downloaded update didn't check out")
            }
        }
        sweep(c, keep = apk)
        ready = apk
        return version
    }

    /** True if [apk] parses as this app at exactly [version]. */
    private fun verified(c: Context, apk: File, version: Long): Boolean {
        if (!apk.exists()) return false
        val info = c.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        return info.packageName == c.packageName && info.longVersionCode == version
    }

    private fun sweep(c: Context, keep: File?) {
        c.cacheDir.listFiles { f -> f.name.startsWith("update-") && f != keep }?.forEach { it.delete() }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Dodo-STT")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) error("GitHub error ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads in a way that survives a connection dying part-way, which on some networks is most
     * of them: the bytes land in a .part file and every retry asks for the range that is still
     * missing, so a stall near the end costs seconds rather than the whole transfer.
     */
    private fun download(url: String, target: File) {
        val partial = File(target.path + ".part")
        var lastProblem: String? = null

        repeat(ATTEMPTS) { attempt ->
            val have = if (partial.exists()) partial.length() else 0L
            val conn = open(url).apply {
                setRequestProperty("Accept", "application/octet-stream")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
                readTimeout = 60_000
            }
            val expected = runCatching {
                val code = conn.responseCode
                if (code !in 200..299) error("GitHub error $code")
                // 206 continues where we left off; a plain 200 means the server ignored the range.
                val resuming = code == HttpURLConnection.HTTP_PARTIAL
                if (!resuming && have > 0) partial.delete()
                val alreadyHave = if (resuming) have else 0L
                FileOutputStream(partial, resuming).use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                }
                alreadyHave + conn.contentLengthLong
            }
            conn.disconnect()

            expected.onSuccess { total ->
                // A connection cut short ends the copy without an error, so measure rather than trust.
                if (total <= 0 || partial.length() >= total) {
                    if (partial.renameTo(target)) return
                    partial.delete()
                    error("Couldn't save the update")
                }
                lastProblem = "The download stopped early"
            }.onFailure { lastProblem = it.message ?: it.javaClass.simpleName }

            if (attempt < ATTEMPTS - 1) Thread.sleep(1500L * (attempt + 1))
        }
        partial.delete()
        error(lastProblem ?: "The download wouldn't finish")
    }

    private fun installIfIdle(c: Context) {
        val apk = ready ?: return
        if (dictating || !canInstall(c)) return
        ready = null
        executor.execute {
            runCatching { install(c, apk) }.onFailure { e ->
                main.post { lastError = e.message ?: e.javaClass.simpleName }
            }
        }
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite(ASSET, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable because the installer fills in the status extras.
            val status = PendingIntent.getBroadcast(
                c, id,
                Intent(c, InstallStatusReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
        // The APK stays in the cache: if this fails, the next check reuses it instead of downloading
        // again, and once it succeeds the next check sweeps it.
    }

    /** The installer's verdict. Success never arrives here — the process is replaced first. */
    fun onInstallStatus(c: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Works when Dodo's settings are on screen; otherwise Android blocks it and the
                // notification is the way in.
                runCatching { c.startActivity(confirm) }
                notifyConfirm(c, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> main.post {
                lastError = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install"
            }
        }
    }

    private fun notifyConfirm(c: Context, confirm: Intent) {
        runCatching {
            val manager = c.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, c.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val tap = PendingIntent.getActivity(c, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_dodo)
                    .setContentTitle(c.getString(R.string.notif_update_title))
                    .setContentText(c.getString(R.string.notif_update_body))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
