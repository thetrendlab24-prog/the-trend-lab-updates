package com.thetrendlab.customer

import android.app.Activity
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.WindowInsets
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private var offlineOverlay: View? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

        val root = FrameLayout(this)
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
            cacheMode = WebSettings.LOAD_NO_CACHE
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
        root.addView(webView)
        setContentView(root)
        webView.requestApplyInsets()
        showBrandOpening(root)
        setupConnectivityMonitor(root)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }

        if (savedInstanceState == null) {
            webView.clearCache(true)
            webView.loadUrl("https://the-trend-lab.thetrendlab24.workers.dev/customer.html?app_refresh=2100")
        } else {
            webView.restoreState(savedInstanceState)
        }
        checkForMandatoryUpdate(false)
    }

    private fun showBrandOpening(root: FrameLayout) {
        val splash = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(24, 10, 39), Color.rgb(65, 20, 75), Color.rgb(18, 12, 31))
            )
            elevation = 24f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val monogram = TextView(this).apply {
            text = "TL"
            textSize = 58f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(Color.rgb(224, 190, 105))
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
        }
        val title = TextView(this).apply {
            text = "THE TREND LAB"
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(239, 211, 145))
            gravity = Gravity.CENTER
            letterSpacing = 0.22f
        }
        val line = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(202, 163, 83))
                cornerRadius = 3f
            }
        }
        val tagline = TextView(this).apply {
            text = "F A S H I O N   &   L I F E S T Y L E"
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(205, 190, 164))
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
        }
        content.addView(monogram, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        content.addView(line, LinearLayout.LayoutParams(dp(112), dp(2)).apply {
            gravity = Gravity.CENTER
            topMargin = dp(18)
        })
        content.addView(tagline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(15) })
        splash.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(splash, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        content.alpha = 0f
        content.scaleX = 0.82f
        content.scaleY = 0.82f
        content.translationY = dp(18).toFloat()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(content, View.SCALE_X, 0.82f, 1f),
                ObjectAnimator.ofFloat(content, View.SCALE_Y, 0.82f, 1f),
                ObjectAnimator.ofFloat(content, View.TRANSLATION_Y, dp(18).toFloat(), 0f)
            )
            duration = 650
            start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            splash.animate().alpha(0f).setDuration(300).withEndAction {
                root.removeView(splash)
            }.start()
        }, 1700)
    }


    private fun setupConnectivityMonitor(root: FrameLayout) {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refreshConnectivity(root) }
            override fun onLost(network: Network) { refreshConnectivity(root) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refreshConnectivity(root) }
        }
        networkCallback = callback
        try { connectivityManager?.registerDefaultNetworkCallback(callback) } catch (_: Exception) { }
        refreshConnectivity(root)
    }

    private fun hasValidatedInternet(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun refreshConnectivity(root: FrameLayout) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (hasValidatedInternet()) {
                offlineOverlay?.let { root.removeView(it); offlineOverlay = null }
            } else if (offlineOverlay == null) showOfflinePage(root)
        }
    }

    private fun showOfflinePage(root: FrameLayout) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(19, 12, 29), Color.rgb(54, 24, 61), Color.rgb(19, 12, 29)))
        }
        val mark = TextView(this).apply {
            text = "TL"; textSize = 48f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(Color.rgb(224, 190, 105)); gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "NO INTERNET CONNECTION"; textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(239, 211, 145)); gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        val message = TextView(this).apply {
            text = "Please check your Wi-Fi or mobile data connection and try again."
            textSize = 14f; setTextColor(Color.rgb(224, 216, 230))
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(24))
        }
        val retry = TextView(this).apply {
            text = "TRY AGAIN"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(Color.rgb(29, 19, 34))
            background = GradientDrawable().apply { setColor(Color.rgb(224, 190, 105)); cornerRadius = dp(8).toFloat() }
            setPadding(dp(34), dp(14), dp(34), dp(14))
            setOnClickListener {
                if (hasValidatedInternet()) {
                    root.removeView(panel); offlineOverlay = null; webView.reload()
                } else Toast.makeText(this@MainActivity, "Still offline. Please connect to the internet.", Toast.LENGTH_SHORT).show()
            }
        }
        val settings = TextView(this).apply {
            text = "OPEN INTERNET SETTINGS"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(239, 211, 145)); setPadding(dp(20), dp(20), dp(20), dp(10))
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        }
        panel.addView(mark)
        panel.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        panel.addView(message, LinearLayout.LayoutParams(-1, -2))
        panel.addView(retry)
        panel.addView(settings)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        offlineOverlay = panel
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        if (::webView.isInitialized) (webView.parent as? FrameLayout)?.let { refreshConnectivity(it) }
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
        try { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } } catch (_: Exception) { }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
