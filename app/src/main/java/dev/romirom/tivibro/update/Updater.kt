package dev.romirom.tivibro.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.romirom.tivibro.BuildConfig
import dev.romirom.tivibro.R
import dev.romirom.tivibro.data.HOUR
import dev.romirom.tivibro.data.now
import dev.romirom.tivibro.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Updates from GitHub Releases: the latest release of [OWNER]/[REPO] is checked on start and at most every 12 h; a
 * newer tag with an `.apk` asset becomes [available]. [install] downloads it to the cache and hands it to the system
 * through a PackageInstaller session (the user confirms on the system screen). Offline, a rate limit or a missing repo
 * are not errors worth showing: an automatic check stays quiet, a manual one says it couldn't check.
 */
class Updater(private val app: Context, http: OkHttpClient, private val scope: CoroutineScope) {
    data class Release(val version: Version, val tag: String, val apkUrl: String, val size: Long)

    private val http = http.newBuilder().followRedirects(true).build()
    private val sp = app.getSharedPreferences("tivibro", Context.MODE_PRIVATE)

    /** A release newer than this build, or null. */
    var available by mutableStateOf<Release?>(restore()); private set
    /** Download progress 0..100 while [install] runs, else null. */
    var progress by mutableStateOf<Int?>(null); private set
    var checking by mutableStateOf(false); private set

    /** What to tell the user; set by the view model to its toast. */
    var say: (String) -> Unit = {}

    private var job: Job? = null

    private fun restore(): Release? {
        val tag = sp.getString(K_TAG, null) ?: return null
        val url = sp.getString(K_URL, null) ?: return null
        val v = Version.parse(tag)
        if (v == null || !Version.isNewer(tag, BuildConfig.VERSION_NAME)) { forget(); return null }
        return Release(v, tag, url, sp.getLong(K_SIZE, 0))
    }

    private fun forget() {
        sp.edit().remove(K_TAG).remove(K_URL).remove(K_SIZE).apply()
    }

    /** On start: a check unless one ran in the last 12 hours. */
    fun checkIfDue() {
        if (now() - sp.getLong(K_CHECKED, 0) < 12 * HOUR) return
        check(manual = false)
    }

    /** «Перевірити оновлення». */
    fun check(manual: Boolean) {
        if (checking || progress != null) return
        checking = true
        sp.edit().putLong(K_CHECKED, now()).apply()
        job = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            checking = false
            r.onFailure { Log.i(TAG, "update check: ${it.message}") }
            val rel = r.getOrNull()
            when {
                rel != null -> {
                    val isNew = available?.tag != rel.tag
                    available = rel
                    sp.edit().putString(K_TAG, rel.tag).putString(K_URL, rel.apkUrl).putLong(K_SIZE, rel.size).apply()
                    if (isNew || manual) say(str(R.string.update_available, rel.version.toString()))
                }
                r.isSuccess -> { // the latest release is this one or older (or has no APK)
                    available = null
                    forget()
                    if (manual) say(str(R.string.update_latest))
                }
                manual -> say(str(R.string.update_check_failed))
            }
        }
    }

    /** The latest release if it is newer than this build and has an APK; null if not. Throws when it can't tell
     *  (offline, rate-limited, no such repo yet). */
    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(latestUrl())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "TiViBro/${BuildConfig.VERSION_NAME}")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("empty body")
        }
        val js = JSONObject(body)
        val tag = js.optString("tag_name")
        if (js.optBoolean("draft") || js.optBoolean("prerelease")) return null
        if (!Version.isNewer(tag, BuildConfig.VERSION_NAME)) return null
        val assets = js.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val url = a.optString("browser_download_url").takeIf { it.startsWith("https://") || (BuildConfig.DEBUG && it.startsWith("http")) }
                ?: continue
            return Release(Version.parse(tag)!!, tag, url, a.optLong("size"))
        }
        return null
    }

    private fun latestUrl(): String {
        if (BuildConfig.DEBUG) sp.getString(K_DEBUG_URL, null)?.let { return it }
        return "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    }

    /** Debug builds: read releases from [url] (a JSON shaped like GitHub's) instead of the GitHub API; null resets. */
    fun setDebugUrl(url: String?) {
        if (!BuildConfig.DEBUG) return
        sp.edit().putString(K_DEBUG_URL, url).remove(K_CHECKED).apply()
    }

    /** «Оновити до X.Y.Z»: allowed to install? download, then the system's install dialog. */
    fun install() {
        val rel = available ?: return
        if (progress != null) return
        if (!canInstall()) { askInstallPermission(); return }
        progress = 0
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { download(rel) } }
            r.onSuccess { apk ->
                runCatching { withContext(Dispatchers.IO) { commit(apk) } }.onFailure { e ->
                    Log.w(TAG, "install failed", e)
                    say(str(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                }
            }.onFailure { e ->
                Log.w(TAG, "download failed", e)
                say(str(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            }
            progress = null
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /** Android 8+: «Install unknown apps» is off for TiViBro. Explain, then open that switch. */
    private fun askInstallPermission() {
        val text = str(R.string.update_allow)
        say(text)
        Toast.makeText(app, text, Toast.LENGTH_LONG).show() // stays visible over the system screen
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "no unknown-sources screen", it) }
    }

    private fun download(rel: Release): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "TiViBro-${rel.version}.apk")
        val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "TiViBro/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != shown) { shown = pct; scope.launch { if (progress != null) progress = pct } }
                    }
                }
            }
            if (rel.size > 0 && done != rel.size) throw IOException("incomplete download")
        }
        return out
    }

    /** Hands the APK to the system; [InstallResultReceiver] shows the confirmation the system asks for. */
    private fun commit(apk: File) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
            s.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
    }

    companion object {
        const val OWNER = "romirom11"
        const val REPO = "TiViBro"
        private const val TAG = "tivibro"
        private const val K_CHECKED = "updCheckedAt"
        private const val K_TAG = "updTag"
        private const val K_URL = "updUrl"
        private const val K_SIZE = "updSize"
        private const val K_DEBUG_URL = "updDebugUrl"

        /** Where a failed install reports; set while the view model lives. */
        @Volatile var onInstallError: ((String) -> Unit)? = null
    }
}

/** The PackageInstaller session's result: the system's confirmation screen to show, or why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure { Log.w("tivibro", "install confirm", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is being replaced
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the user said no
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("tivibro", "install failed: $msg")
                Updater.onInstallError?.invoke(msg)
            }
        }
    }
}
