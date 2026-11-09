package com.thetrendlab.billing

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import android.graphics.Color
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val billingUrl = "https://the-trend-lab.thetrendlab24.workers.dev/billing.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 12, 31)
        window.navigationBarColor = Color.rgb(18, 12, 31)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        web.addJavascriptInterface(BillingBridge(), "BillingNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    "if(!window.__ttlNativePrint){window.__ttlNativePrint=true;window.print=function(){BillingNative.printReceipt();};}",
                    null
                )
            }
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Toast.makeText(this@MainActivity, "Billing screen unavailable. Please check connection or deployment.", Toast.LENGTH_LONG).show()
                }
            }
        }
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl(billingUrl) else web.restoreState(savedInstanceState)
    }

    private inner class BillingBridge {
        @JavascriptInterface
        fun printReceipt() {
            runOnUiThread {
                try {
                    val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val adapter = web.createPrintDocumentAdapter("The Trend Lab Receipt")
                    manager.print("The Trend Lab Receipt", adapter, PrintAttributes.Builder().build())
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Unable to open printer. Use Android print service.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.removeJavascriptInterface("BillingNative")
            web.destroy()
        }
        super.onDestroy()
    }
}
