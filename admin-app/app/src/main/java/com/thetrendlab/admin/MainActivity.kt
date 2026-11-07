package com.thetrendlab.admin

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.content.Intent
import android.net.Uri
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequestCode = 7103
    private val adminUrl = "https://the-trend-lab.thetrendlab24.workers.dev/admin.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Finish any previous picker request before opening a new one.
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback

                return try {
                    val chooserIntent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    chooserIntent.addCategory(Intent.CATEGORY_OPENABLE)
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(chooserIntent, fileChooserRequestCode)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "Unable to open file picker.", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return route(uri)
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val value = url ?: return true
                return route(Uri.parse(value))
            }
        }
        setContentView(web)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(adminUrl)
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
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
