package com.thetrendlab.customer

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var downloadId: Long = -1L
    private var pendingInstallUri: Uri? = null
    private var pendingDownloadUrl: String? = null
    private var pendingExpectedSha256: String? = null
    private var updateDialogVisible = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            pendingInstallUri = manager.getUriForDownloadedFile(downloadId)
            if (pendingInstallUri == null) {
                Toast.makeText(this@MainActivity, "Update download failed. Please try again.", Toast.LENGTH_LONG).show()
                return
            }
            if (!verifyDownloadedApk(pendingInstallUri!!, pendingExpectedSha256)) {
                manager.remove(downloadId)
                pendingInstallUri = null
                Toast.makeText(this@MainActivity, "Update file verification failed. APK blocked.", Toast.LENGTH_LONG).show()
                return
            }
            installDownloadedUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                topInset = insets.systemWindowInsetTop
                bottomInset = insets.systemWindowInsetBottom
            }
            view.setPadding(0, topInset, 0, bottomInset)
            insets
        }
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = try { Uri.parse(url ?: return true) } catch (_: Exception) { return true }
                return !(uri.scheme == "https" && uri.host == "the-trend-lab.thetrendlab24.workers.dev")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return !(uri.scheme == "https" && uri.host == "the-trend-lab.thetrendlab24.workers.dev")
            }
        }
        webView.addJavascriptInterface(UpdateBridge(), "Android")
        webView.webChromeClient = WebChromeClient()
        setContentView(webView)
        webView.requestApplyInsets()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }

        if (savedInstanceState == null) {
            webView.loadUrl("https://the-trend-lab.thetrendlab24.workers.dev/customer.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
        checkForMandatoryUpdate(false)
    }

    private inner class UpdateBridge {
        @JavascriptInterface
        fun checkForUpdate() {
            checkForMandatoryUpdate(true)
        }
    }

    private fun checkForMandatoryUpdate(showLatestMessage: Boolean) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://raw.githubusercontent.com/thetrendlab24-prog/the-trend-lab-updates/fresh-customer-app-cloud-build/app-update.json").openConnection() as HttpURLConnection)
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Update server returned HTTP $code")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val update = JSONObject(body)
                val latestCode = update.optInt("versionCode", 0)
                val currentInfo = packageManager.getPackageInfo(packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= 28) currentInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") currentInfo.versionCode
                if (latestCode <= 0) throw IllegalStateException("Invalid update version information")
                if (latestCode > currentCode) {
                    val name = update.optString("versionName", "New version")
                    val notes = update.optString("releaseNotes", "Important improvements and fixes.")
                    val apkUrl = update.optString("apkUrl")
                    val apkSha256 = update.optString("apkSha256").lowercase(Locale.ROOT)
                    val forced = update.optBoolean("forceUpdate", false)
                    runOnUiThread { showUpdateDialog(name, notes, apkUrl, apkSha256, forced) }
                } else if (showLatestMessage) {
                    runOnUiThread { Toast.makeText(this, "You're using the latest version.", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                if (showLatestMessage) {
                    val message = e.message ?: "Please check your internet connection."
                    runOnUiThread { Toast.makeText(this, "Update check failed: $message", Toast.LENGTH_LONG).show() }
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showUpdateDialog(version: String, notes: String, apkUrl: String, apkSha256: String, forced: Boolean) {
        if (isFinishing || updateDialogVisible || apkUrl.isBlank()) return
        updateDialogVisible = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update required — The Trend Lab")
            .setMessage("Version $version is available.\n\n$notes\n\nPlease update to continue using the app.")
            .setPositiveButton("Update Now") { _, _ -> startUpdateDownload(apkUrl, apkSha256) }
            .setCancelable(!forced)
            .create()
        if (!forced) dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Later") { _, _ -> updateDialogVisible = false }
        dialog.setOnCancelListener { updateDialogVisible = false }
        dialog.setOnDismissListener { if (!forced) updateDialogVisible = false }
        dialog.show()
        if (forced) dialog.setCanceledOnTouchOutside(false)
    }

    private fun startUpdateDownload(apkUrl: String, expectedSha256: String) {
        val parsed = try { Uri.parse(apkUrl) } catch (_: Exception) { null }
        if (parsed == null || parsed.scheme != "https" || parsed.host !in listOf("github.com", "objects.githubusercontent.com") || !expectedSha256.matches(Regex("^[a-f0-9]{64}$")) ) {
            Toast.makeText(this, "The update download link is invalid.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingDownloadUrl = apkUrl
            pendingExpectedSha256 = expectedSha256
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Allow installs from this app, then return here to continue.", Toast.LENGTH_LONG).show()
            return
        }
        enqueueUpdateDownload(apkUrl, expectedSha256)
    }

    private fun enqueueUpdateDownload(apkUrl: String, expectedSha256: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("The Trend Lab update")
                .setDescription("Downloading the latest app version")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "TheTrendLab-update.apk")
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            pendingExpectedSha256 = expectedSha256
            downloadId = manager.enqueue(request)
            Toast.makeText(this, "Update downloading. Installer will open when complete.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not start update download. Please try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun verifyDownloadedApk(uri: Uri, expected: String?): Boolean {
        if (expected.isNullOrBlank() || !expected.matches(Regex("^[a-f0-9]{64}$"))) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            } ?: return false
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }.equals(expected, true)
        } catch (_: Exception) { false }
    }

    private fun installDownloadedUpdate() {
        val uri = pendingInstallUri ?: return
        try {
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(install)
        } catch (_: Exception) {
            Toast.makeText(this, "Open the downloaded update from your notification.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
            val url = pendingDownloadUrl
            if (!url.isNullOrBlank()) {
                pendingDownloadUrl = null
                enqueueUpdateDownload(url, pendingExpectedSha256 ?: "")
                pendingExpectedSha256 = null
            } else if (pendingInstallUri != null) {
                installDownloadedUpdate()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) { }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
