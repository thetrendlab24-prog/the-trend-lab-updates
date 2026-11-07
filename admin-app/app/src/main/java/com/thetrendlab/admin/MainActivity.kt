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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private var splashOverlay: View? = null

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
        // Keep the WebView inside the system bars on Android 10–16.
        // targetSdk 34 below opts out of Android 15 forced edge-to-edge,
        // avoiding status icons being drawn over the first page controls.
        window.statusBarColor = Color.rgb(16, 27, 42)
        window.navigationBarColor = Color.rgb(16, 27, 42)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        web = WebView(this)
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
                // Android-only admin layout repairs. Re-apply after dynamic product-list renders.
                val patch = """
                    (function(){
                      if(window.__tlAdminAndroidPatchInstalled) return;
                      window.__tlAdminAndroidPatchInstalled = true;
                      const css = `
                        input[placeholder*="Search name"],input[placeholder*="SKU or barcode"]{
                          color:#f8fafc!important;caret-color:#f3c65b!important;
                        }
                        input[placeholder*="Search name"]::placeholder,
                        input[placeholder*="SKU or barcode"]::placeholder{
                          color:#b9afc5!important;opacity:1!important;
                        }
                        .stock-quick,.stock-quick *,
                        [class*="stock-quick"],[class*="stock-quick"] *{
                          color:#f3e5bf!important;-webkit-text-fill-color:#f3e5bf!important;
                        }
                        button[disabled],button:disabled,
                        [role="button"][aria-disabled="true"]{
                          opacity:1!important;
                        }
                      `;
                      function repair(){
                        if(!document.getElementById('tl-admin-android-fixes')){
                          const style=document.createElement('style');
                          style.id='tl-admin-android-fixes';style.textContent=css;
                          (document.head||document.documentElement).appendChild(style);
                        }
                        document.querySelectorAll('button,input[type=button],input[type=submit],label,[role=button]').forEach(el=>{
                          const text=(el.innerText||el.value||el.getAttribute('aria-label')||'').trim().toLowerCase();
                          if(text.includes('import products csv')){
                            el.disabled=false;el.removeAttribute('disabled');
                            el.removeAttribute('aria-disabled');
                            el.style.setProperty('opacity','1','important');
                            el.style.setProperty('color','#63328a','important');
                            el.style.setProperty('-webkit-text-fill-color','#63328a','important');
                            el.style.setProperty('pointer-events','auto','important');
                            el.style.setProperty('cursor','pointer','important');
                          }
                        });
                        // The quantity between the minus/plus controls is often a plain text node.
                        document.querySelectorAll('button').forEach(btn=>{
                          const t=(btn.innerText||'').trim();
                          if(t==='+'||t==='-'||t==='−'){
                            const row=btn.parentElement;
                            if(row){
                              row.style.setProperty('color','#f3e5bf','important');
                              row.querySelectorAll('*').forEach(child=>{
                                child.style.setProperty('color','#f3e5bf','important');
                                child.style.setProperty('-webkit-text-fill-color','#f3e5bf','important');
                              });
                            }
                          }
                        });
                      }
                      repair();
                      new MutationObserver(repair).observe(document.documentElement,{childList:true,subtree:true});
                    })();
                """.trimIndent()
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
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        showTwoSecondSplash(root)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(adminUrl)
        checkForAppUpdate()
    }

    private fun showTwoSecondSplash(root: FrameLayout) {
        val overlay = FrameLayout(this)
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(8,17,37), Color.rgb(16,31,53), Color.rgb(7,15,32)))
        overlay.background = bg
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logoFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(30).toFloat()
                setColor(Color.rgb(13,29,50))
                setStroke(dp(2), Color.rgb(224,178,75))
            }
            elevation = dp(16).toFloat()
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.admin_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9),dp(9),dp(9),dp(9))
            contentDescription = "The Trend Lab logo"
        }
        logoFrame.addView(logo, FrameLayout.LayoutParams(dp(116),dp(116),Gravity.CENTER))
        content.addView(logoFrame, LinearLayout.LayoutParams(dp(136),dp(136)).apply {
            bottomMargin = dp(28)
        })
        val brand = TextView(this).apply {
            text = "The Trend Lab"
            setTextColor(Color.rgb(246,198,91))
            textSize = 31f
            typeface = Typeface.create("serif",Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        content.addView(brand)
        val subtitle = TextView(this).apply {
            text = "ADMIN PANEL"
            setTextColor(Color.rgb(190,201,218))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
            letterSpacing = 0.34f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        content.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(9) })
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(Color.rgb(44,58,78))
            }
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(Color.rgb(239,190,79))
            }
            scaleX = 0f
            pivotX = 0f
        }
        track.addView(fill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT
        ))
        content.addView(track, LinearLayout.LayoutParams(dp(210),dp(4)).apply {
            topMargin = dp(34)
        })
        overlay.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT
        ))
        splashOverlay = overlay

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoFrame,View.ALPHA,0f,1f),
                ObjectAnimator.ofFloat(logoFrame,View.SCALE_X,0.72f,1f),
                ObjectAnimator.ofFloat(logoFrame,View.SCALE_Y,0.72f,1f)
            )
            duration = 520
            interpolator = DecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(brand,View.ALPHA,0f,1f).apply {
            startDelay = 280; duration = 520; interpolator = DecelerateInterpolator(); start()
        }
        ObjectAnimator.ofFloat(subtitle,View.ALPHA,0f,1f).apply {
            startDelay = 430; duration = 420; interpolator = DecelerateInterpolator(); start()
        }
        ObjectAnimator.ofFloat(fill,View.SCALE_X,0f,1f).apply {
            startDelay = 350; duration = 1250; interpolator = DecelerateInterpolator(); start()
        }
        overlay.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            ObjectAnimator.ofFloat(overlay,View.ALPHA,1f,0f).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        root.removeView(overlay)
                        splashOverlay = null
                    }
                })
                start()
            }
        },1700L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
            var connection: HttpURLConnection? = null
            try {
                val cacheBust = System.currentTimeMillis()
                connection = (URL("$updateManifestUrl?ts=$cacheBust").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("Accept", "application/json")
                }
                val status = connection.responseCode
                if (status !in 200..299) throw IllegalStateException("Update server returned HTTP $status")
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val update = JSONObject(json)
                val latestCode = update.optInt("versionCode", 0)
                val latestName = update.optString("versionName", "new version")
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val currentCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toInt() else packageInfo.versionCode
                if (latestCode > currentCode) {
                    val apkUrl = update.optString("apkUrl")
                    val sha256 = update.optString("apkSha256")
                    val notes = update.optString("releaseNotes", "A new version is available.")
                    if (!apkUrl.startsWith("https://") || sha256.isBlank()) {
                        throw IllegalStateException("Update manifest is missing a valid APK URL or checksum")
                    }
                    runOnUiThread { if (!isFinishing && !isDestroyed) showUpdateDialog(latestName, notes, apkUrl, sha256) }
                }
            } catch (e: Exception) {
                android.util.Log.e("TrendLabUpdater", "Update check failed", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Update check failed. Check internet and reopen the app.", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                connection?.disconnect()
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