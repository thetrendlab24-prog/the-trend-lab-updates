package com.thetrendlab.admin

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.graphics.Color
import android.view.WindowInsets
import android.webkit.*
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequestCode = 7103
    private val adminUrl = "https://the-trend-lab.thetrendlab24.workers.dev/admin.html"
    private val updateManifestUrl = "https://raw.githubusercontent.com/thetrendlab24-prog/the-trend-lab-updates/admin-apk-build/admin-app-update.json"
    private var backPressedAt = 0L
    private val exitPromptWindowMs = 2000L
    private var pendingDownloadId = -1L
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != pendingDownloadId) return
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(pendingDownloadId)
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Toast.makeText(this@MainActivity, "Update download failed. Please try again.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            val apkUri = manager.getUriForDownloadedFile(pendingDownloadId) ?: return
            verifyAndInstall(apkUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerDownloadReceiver()
        // Android 15 enforces edge-to-edge for targetSdk 35. Keep WebView content
        // below the status bar and above the gesture/navigation area.
        window.statusBarColor = Color.rgb(16, 27, 42)
        window.navigationBarColor = Color.rgb(16, 27, 42)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        web = WebView(this)
        web.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                val legacyTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val legacyBottom = insets.systemWindowInsetBottom
                top = legacyTop
                bottom = legacyBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                return try {
                    // Use Android's Storage Access Framework directly. This is more reliable
                    // than WebChromeClient.createIntent() across Android 10-15 and supports
                    // both image/* uploads and CSV/document imports in the same WebView.
                    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        val accepted = fileChooserParams?.acceptTypes
                            ?.flatMap { it.split(",") }
                            ?.map { it.trim().lowercase() }
                            ?.filter { it.isNotBlank() && !it.startsWith(".") && it.contains("/") }
                            ?.distinct()
                            ?.toTypedArray()
                        if (!accepted.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, accepted)
                        }
                    }
                    startActivityForResult(Intent.createChooser(picker, "Select files"), fileChooserRequestCode)
                    true
                } catch (_: Exception) {
                    try {
                        val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        }
                        startActivityForResult(Intent.createChooser(fallback, "Select files"), fileChooserRequestCode)
                        true
                    } catch (_: Exception) {
                        this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                        this@MainActivity.fileChooserCallback = null
                        Toast.makeText(this@MainActivity, "Unable to open file picker. Please try again.", Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Android-only fixes for stock count contrast and CSV import availability.
                val patch = "(function(){const css='.stock-quick,.stock-quick *{color:#f3e5bf!important} input[placeholder*=\"Search name\"],input[placeholder*=\"SKU or barcode\"]{color:#f8fafc!important;caret-color:#f3c65b!important} input[placeholder*=\"Search name\"]::placeholder,input[placeholder*=\"SKU or barcode\"]::placeholder{color:#a99bb4!important;opacity:1!important}';function repair(){if(!document.getElementById('tl-admin-android-fixes')){const s=document.createElement('style');s.id='tl-admin-android-fixes';s.textContent=\"\"+css;(document.head||document.documentElement).appendChild(s)}document.querySelectorAll('button,input[type=button],input[type=submit]').forEach(el=>{const t=(el.innerText||el.value||el.getAttribute('aria-label')||'').trim().toLowerCase();if(t.includes('import products csv')){el.disabled=false;el.removeAttribute('disabled');el.removeAttribute('aria-disabled');el.style.opacity='1';el.style.color='#63328a';el.style.cursor='pointer'}})}repair();if(!window.__tlAdminAndroidObserver){window.__tlAdminAndroidObserver=new MutationObserver(repair);window.__tlAdminAndroidObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true})}})();"
                view?.evaluateJavascript(patch, null)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return route(uri)
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return route(Uri.parse(url ?: return true))
            }
        }
        setContentView(web)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(adminUrl)
        checkForAppUpdate()
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun checkForAppUpdate() {
        thread(name = "admin-update-check") {
            try {
                val connection = (URL(updateManifestUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val update = JSONObject(json)
                val latestCode = update.optInt("versionCode", 0)
                val currentCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                if (latestCode > currentCode) {
                    val version = update.optString("versionName", "new version")
                    val apkUrl = update.optString("apkUrl")
                    val sha256 = update.optString("apkSha256")
                    val notes = update.optString("releaseNotes", "A new version is available.")
                    if (apkUrl.startsWith("https://") && sha256.isNotBlank()) {
                        runOnUiThread { showUpdateDialog(version, notes, apkUrl, sha256) }
                    }
                }
            } catch (_: Exception) {
                // Update checks are optional; never block opening the Admin panel.
            }
        }
    }

    private fun showUpdateDialog(version: String, notes: String, apkUrl: String, expectedHash: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("The Trend Lab Admin update")
            .setMessage("Version $version is available.\n\n$notes\n\nDownload and install now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> downloadUpdate(apkUrl, expectedHash) }
            .show()
    }

    private fun downloadUpdate(apkUrl: String, expectedHash: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("The Trend Lab Admin update")
                .setDescription("Downloading update…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "the-trend-lab-admin-update.apk")
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            pendingDownloadId = manager.enqueue(request)
            getSharedPreferences("admin_update", MODE_PRIVATE).edit().putString("expected_sha256", expectedHash.lowercase()).apply()
            Toast.makeText(this, "Update downloading…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to start update download.", Toast.LENGTH_LONG).show()
        }
    }

    private fun verifyAndInstall(apkUri: Uri) {
        thread(name = "admin-update-verify") {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                contentResolver.openInputStream(apkUri)?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                } ?: throw IllegalStateException("Cannot read downloaded APK")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = getSharedPreferences("admin_update", MODE_PRIVATE).getString("expected_sha256", "") ?: ""
                if (expected.isBlank() || actual != expected) throw SecurityException("APK checksum mismatch")
                runOnUiThread {
                    try {
                        val install = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(install)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Please allow installs from this app in Android settings.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Update verification failed. Please download again.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    @Deprecated("Deprecated in Android, retained for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileChooserRequestCode) return
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK) {
            val clipData = data?.clipData
            when {
                clipData != null -> Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        callback.onReceiveValue(results)
    }

    private fun route(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "https" && uri.host?.lowercase() == "the-trend-lab.thetrendlab24.workers.dev") return false
        return try {
            when (scheme) {
                "http", "https", "tel", "mailto", "sms", "smsto", "upi", "market" -> startActivity(Intent(Intent.ACTION_VIEW, uri))
                "intent" -> startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME))
                else -> return true
            }
            true
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this link.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack(); backPressedAt = 0L; return
        }
        val now = System.currentTimeMillis()
        if (now - backPressedAt <= exitPromptWindowMs) {
            backPressedAt = 0L; finish()
        } else {
            backPressedAt = now
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}