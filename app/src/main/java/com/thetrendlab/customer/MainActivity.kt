package com.thetrendlab.customer

import android.app.Activity
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pageProgress: ProgressBar
    private var downloadPoller: Runnable? = null
    private var progressDialog: ProgressDialog? = null
    private val updatePrefs by lazy { getSharedPreferences("ttl_update", Context.MODE_PRIVATE) }
    private var downloadThread: Thread? = null
    private var downloadedApkFile: File? = null
    private var pendingInstallUri: Uri? = null
    private var pendingDownloadUrl: String? = null
    private var pendingExpectedSha256: String? = null
    private var updateDialogVisible = false
    private var offlineOverlay: View? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var refreshOnNextResume = false
    private var doubleBackToExitPressedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the WebView inside the real Android system-bar safe area.
        // Android 15 (targetSdk 35) enforces edge-to-edge by default; opting out
        // prevents status/navigation bars from drawing over fixed web content.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = FrameLayout(this)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.rgb(184, 145, 65), Color.rgb(65, 35, 78))
            setProgressBackgroundColorSchemeColor(Color.WHITE)
        }
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        // Decor fitting above keeps this WebView below the status bar and above
        // the navigation/gesture area. Do not add a second inset as WebView padding.
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageProgress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                // APK-only: hide header shortcuts duplicated by the persistent bottom navigation.
                // This is injected into this WebView only; the public website remains unchanged.
                val apkHeaderCleanup = """
                    (function() {
                      if (window.innerWidth > 768) return;
                      if (document.getElementById('ttl-apk-header-shortcuts-style')) return;
                      var style = document.createElement('style');
                      style.id = 'ttl-apk-header-shortcuts-style';
                      style.textContent = '.header-actions,.header-icons,.header-tools,.top-header-actions,.mobile-header-actions,.header-action-buttons,.header-right-actions{display:none!important}';
                      document.head.appendChild(style);
                      var headers = Array.from(document.querySelectorAll('header,.header,.site-header,.app-header,.mobile-header,.topbar'));
                      headers.forEach(function(header) {
                        header.querySelectorAll('button,a,[role="button"]').forEach(function(el) {
                          var label = ((el.getAttribute('aria-label') || '') + ' ' +
                            (el.getAttribute('title') || '') + ' ' +
                            (el.getAttribute('data-action') || '') + ' ' +
                            (el.innerText || '')).toLowerCase();
                          if (/wishlist|cart|account|profile/.test(label)) el.style.setProperty('display','none','important');
                        });
                      });
                    })();
                """.trimIndent()
                view?.evaluateJavascript(apkHeaderCleanup, null)

                // APK-only robust category shelf fix. Detects category blocks by their visible
                // "SHOP COLLECTION" label instead of assuming a website-specific element ID.
                // Re-runs after dynamic rendering because product shelves are inserted asynchronously.
                // Category shelf layout is controlled by responsive website CSS.
                // Avoid mutating product-grid DOM from the WebView: it can interfere
                // with the initial All-products render during asynchronous catalogue loading.

                val categorySpecificIcons = """
                    (function(){
                      var iconMap={
                        'accessories & utility':'👜',
                        'beauty & personal care':'💄',
                        'fashion jewellery & accessories':'💎',
                        'fragrance & grooming':'🧴',
                        'gents':'👔',
                        'home & linen':'🛏️',
                        'innerwear':'🩲',
                        'kids wear':'🧸',
                        'ladies':'👗',
                        'men’s clothing':'👕',
                        "men's clothing":'👕',
                        'review – unclassified':'📦',
                        'review - unclassified':'📦',
                        'sarees & dupattas':'🥻',
                        'women’s clothing':'👚',
                        "women's clothing":'👚'
                      };
                      function norm(t){return (t||'').normalize('NFKC').replace(/[’]/g,"'").replace(/[–—]/g,'-').replace(/\s+/g,' ').trim().toLowerCase();}
                      function apply(){
                        try{
                          document.querySelectorAll('.category-tile').forEach(function(tile){
                            var title=tile.querySelector('strong');
                            var icon=tile.querySelector('.category-emoji');
                            if(!title||!icon)return;
                            var key=norm(title.textContent);
                            var glyph=iconMap[key];
                            if(!glyph)return;
                            if(icon.getAttribute('data-ttl-icon-key')===key&&icon.textContent===glyph)return;
                            icon.setAttribute('data-ttl-icon-key',key);
                            icon.replaceChildren(document.createTextNode(glyph));
                            icon.style.setProperty('display','inline-flex','important');
                            icon.style.setProperty('align-items','center','important');
                            icon.style.setProperty('justify-content','center','important');
                            icon.style.setProperty('font-size','34px','important');
                            icon.style.setProperty('line-height','1','important');
                          });
                        }catch(e){console.warn('Category icon enhancement:',e);}
                      }
                      apply();
                      if(!window.__ttlCategoryIconObserver){
                        window.__ttlCategoryIconObserver=new MutationObserver(apply);
                        window.__ttlCategoryIconObserver.observe(document.documentElement,{childList:true,subtree:true});
                      }
                      setTimeout(apply,250);setTimeout(apply,1000);setTimeout(apply,2500);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(categorySpecificIcons, null)

                // APK-only five-banner carousel. Switch the live image directly on every
                // dot/timer event; do not gate navigation on preload callbacks (WebView can
                // delay those callbacks and otherwise leave every dot stuck on slide one).
                val apkFiveBannerCarousel = """
                    (function(w,d){
                      var root=w.location.origin;
                      var files=[
                        ['tl-banner-kurti-v376.jpg','The Trend Lab exclusive kurti collection'],
                        ['tl-banner-menswear-v376.jpg','The Trend Lab stylish menswear collection'],
                        ['tl-banner-kids-v376.jpg','The Trend Lab kids wear collection'],
                        ['tl-banner-saree-v376.jpg','The Trend Lab elegant saree collection'],
                        ['tl-banner-cosmetics-v376.jpg','The Trend Lab cosmetics and beauty collection']
                      ];
                      var slides=files.map(function(x,i){return {src:root+'/'+x[0]+'?apk_carousel=2128_'+i,alt:x[1]};});
                      function init(){
                        var hero=d.querySelector('.shop-hero.shop-hero-artwork-mode')||d.querySelector('.shop-hero');
                        var visual=d.getElementById('shopHeroVisual')||(hero&&hero.querySelector('.shop-hero-visual'));
                        if(!hero||!visual)return false;
                        var img=visual.querySelector('img');
                        if(!img){img=d.createElement('img');visual.appendChild(img);}
                        hero.style.setProperty('height','auto','important');
                        hero.style.setProperty('min-height','0','important');
                        hero.style.setProperty('padding','0','important');
                        hero.style.setProperty('overflow','hidden','important');
                        visual.style.setProperty('display','block','important');
                        visual.style.setProperty('position','relative','important');
                        visual.style.setProperty('inset','auto','important');
                        visual.style.setProperty('width','100%','important');
                        visual.style.setProperty('height','auto','important');
                        visual.style.setProperty('min-height','0','important');
                        visual.style.setProperty('padding','0','important');
                        visual.style.setProperty('margin','0','important');
                        visual.style.setProperty('overflow','hidden','important');
                        img.style.setProperty('display','block','important');
                        img.style.setProperty('position','static','important');
                        img.style.setProperty('width','100%','important');
                        img.style.setProperty('height','auto','important');
                        img.style.setProperty('max-width','100%','important');
                        img.style.setProperty('max-height','none','important');
                        img.style.setProperty('object-fit','contain','important');
                        img.style.setProperty('object-position','center','important');
                        var copy=hero.querySelector('.shop-hero-copy');
                        if(copy)copy.style.setProperty('display','none','important');
                        var dots=hero.querySelector('.hero-dots');
                        if(!dots){dots=d.createElement('div');dots.className='hero-dots';hero.appendChild(dots);}
                        dots.innerHTML='';
                        dots.style.setProperty('position','absolute','important');
                        dots.style.setProperty('z-index','50','important');
                        dots.style.setProperty('left','50%','important');
                        dots.style.setProperty('bottom','8px','important');
                        dots.style.setProperty('transform','translateX(-50%)','important');
                        dots.style.setProperty('display','flex','important');
                        dots.style.setProperty('gap','8px','important');
                        var buttons=[],current=0;
                        function paint(){
                          for(var k=0;k<buttons.length;k++){
                            buttons[k].style.setProperty('background',k===current?'#ffffff':'rgba(255,255,255,.5)','important');
                            buttons[k].style.setProperty('width',k===current?'22px':'9px','important');
                          }
                        }
                        function show(n){
                          current=(n+slides.length)%slides.length;
                          img.onload=function(){img.setAttribute('data-ttl-slide-loaded',String(current));};
                          img.onerror=function(){w.console.error('APK carousel image failed to load: '+slides[current].src);};
                          img.setAttribute('src',slides[current].src);
                          img.setAttribute('alt',slides[current].alt);
                          img.setAttribute('data-ttl-slide',String(current));
                          img.removeAttribute('srcset');
                          paint();
                        }
                        for(var i=0;i<slides.length;i++){
                          var b=d.createElement('button');
                          b.type='button';b.setAttribute('aria-label','Show banner '+(i+1));
                          b.style.cssText='border:1px solid rgba(70,35,80,.35);border-radius:20px;padding:0;height:9px;width:9px;min-width:9px;cursor:pointer;';
                          (function(n,btn){btn.addEventListener('click',function(){show(n);},false);})(i,b);
                          dots.appendChild(b);buttons.push(b);
                        }
                        show(0);
                        if(w.__ttlApkFiveBannerTimer)w.clearInterval(w.__ttlApkFiveBannerTimer);
                        w.__ttlApkFiveBannerTimer=w.setInterval(function(){show(current+1);},5000);
                        return true;
                      }
                      function run(){if(!init())w.setTimeout(run,700);}
                      run();
                    })(window,document);
                """.trimIndent()
                view?.evaluateJavascript(apkFiveBannerCarousel, null)


                val versionSync = """
                    (function() {
                      try {
                        var version = Android.getAppVersion();
                        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                        var node;
                        while ((node = walker.nextNode())) {
                          if (/Customer\\s+app\\s+v1\\.0/i.test(node.nodeValue || '')) {
                            node.nodeValue = node.nodeValue.replace(/Customer\\s+app\\s+v1\\.0/ig, 'Customer app v' + version);
                          }
                        }
                      } catch (e) {}
                    })();
                """.trimIndent()
                view?.evaluateJavascript(versionSync, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = try { Uri.parse(url ?: return true) } catch (_: Exception) { return true }
                return routeNavigation(uri)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return routeNavigation(uri)
            }
        }
        webView.addJavascriptInterface(UpdateBridge(), "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                pageProgress.progress = newProgress
                pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress >= 100) swipeRefresh.isRefreshing = false
            }
        }
        swipeRefresh.addView(webView, ViewGroup.LayoutParams(-1, -1))
        swipeRefresh.setOnRefreshListener { webView.reload() }
        root.addView(swipeRefresh, FrameLayout.LayoutParams(-1, -1))
        pageProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(190, 151, 72))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.GONE
        }
        root.addView(pageProgress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        setContentView(root)
        webView.requestApplyInsets()
        showBrandOpening(root)
        setupConnectivityMonitor(root)

        if (savedInstanceState == null) {
            webView.clearCache(true)
            webView.loadUrl("https://the-trend-lab.thetrendlab24.workers.dev/customer.html?app_refresh=2100")
        } else {
            webView.restoreState(savedInstanceState)
            webView.post { webView.reload() }
        }
        restorePendingUpdateDownload()
        checkForMandatoryUpdate(false)
    }

    private fun routeNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (scheme == "https" && host == "the-trend-lab.thetrendlab24.workers.dev") return false

        // WhatsApp support links must be launched directly. resolveActivity() can return
        // null on Android 11+ because of package visibility, even when a handler exists.
        if (scheme == "https" && (host == "wa.me" || host == "api.whatsapp.com" || host == "www.whatsapp.com")) {
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            for (pkg in packages) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
                    return true
                } catch (_: android.content.ActivityNotFoundException) {
                    // Try WhatsApp Business or the browser fallback.
                } catch (_: Exception) {
                    // Continue to the next supported handler.
                }
            }
            return try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open WhatsApp support"))
                true
            } catch (_: Exception) {
                Toast.makeText(this, "No browser or WhatsApp app is available. Please install WhatsApp or a browser.", Toast.LENGTH_LONG).show()
                true
            }
        }

        return try {
            val intent = when (scheme) {
                "http", "https", "tel", "mailto", "sms", "smsto", "upi", "market" -> Intent(Intent.ACTION_VIEW, uri)
                "intent" -> Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                else -> return true
            }
            // Do not gate launches on resolveActivity(): package visibility can hide valid handlers.
            startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No compatible app is installed to open this link.", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this link.", Toast.LENGTH_SHORT).show()
            true
        }
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, callback)
            }
        } catch (_: Exception) {
            // Keep the app usable even if the device blocks network callbacks.
        }
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
        fun getAppVersion(): String {
            return try {
                val info = packageManager.getPackageInfo(packageName, 0)
                info.versionName ?: "Unknown"
            } catch (_: Exception) { "Unknown" }
        }

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
                } else {
                    // Metadata is at or below the installed build: discard any stale installer
                    // state left behind after Android completed a previous update.
                    runOnUiThread {
                        clearPendingUpdateDownload(true)
                        if (showLatestMessage) Toast.makeText(this, "You're using the latest version.", Toast.LENGTH_LONG).show()
                    }
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
        dialog.setOnDismissListener { updateDialogVisible = false }
        dialog.show()
        if (forced) dialog.setCanceledOnTouchOutside(false)
    }

    private fun startUpdateDownload(apkUrl: String, expectedSha256: String) {
        val parsed = try { Uri.parse(apkUrl) } catch (_: Exception) { null }
        if (parsed == null || parsed.scheme != "https" || parsed.host !in listOf("github.com", "objects.githubusercontent.com")) {
            Toast.makeText(this, "The update download link is invalid.", Toast.LENGTH_LONG).show()
            return
        }
        // Download the verified APK inside the app; do not redirect the user to GitHub/browser.
        enqueueUpdateDownload(parsed.toString(), expectedSha256)
    }

    private fun enqueueUpdateDownload(apkUrl: String, expectedSha256: String) {
        if (downloadThread?.isAlive == true) {
            Toast.makeText(this, "Update download is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExpectedSha256 = expectedSha256
        showDownloadProgress()
        progressDialog?.progress = 0
        progressDialog?.setMessage("Connecting securely to update server…")
        downloadThread = Thread {
            var connection: HttpURLConnection? = null
            val target = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "TheTrendLab-update.apk")
            val temp = File(target.parentFile, "TheTrendLab-update.apk.part")
            try {
                connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000
                    readTimeout = 30000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "TheTrendLab-Android-Updater")
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Update server HTTP $code")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(16384)
                        var count: Long = 0
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            count += n
                            val pct = if (total > 0) ((count * 100) / total).toInt().coerceIn(0, 100) else -1
                            runOnUiThread {
                                progressDialog?.let { dialog ->
                                    if (pct >= 0) dialog.progress = pct
                                    dialog.setMessage(if (total > 0) "Downloaded ${count / 1024} KB of ${total / 1024} KB" else "Downloaded ${count / 1024} KB")
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (!temp.exists() || temp.length() <= 0) throw IllegalStateException("The server returned an empty APK.")
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) throw IllegalStateException("Could not save the downloaded APK.")
                if (!verifyDownloadedApk(Uri.fromFile(target), expectedSha256)) {
                    target.delete()
                    throw SecurityException("APK verification failed. Installation blocked.")
                }
                downloadedApkFile = target
                updatePrefs.edit().putString("apk_path", target.absolutePath)
                    .putString("expected_sha256", expectedSha256).putBoolean("install_pending", true).apply()
                runOnUiThread {
                    progressDialog?.dismiss()
                    progressDialog = null
                    Toast.makeText(this, "Update downloaded and verified.", Toast.LENGTH_LONG).show()
                    launchInstallerOrShowButton()
                }
            } catch (e: Exception) {
                temp.delete()
                runOnUiThread {
                    progressDialog?.dismiss()
                    progressDialog = null
                    Toast.makeText(this, "Update download failed: ${e.message ?: "Please retry"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.also { it.start() }
    }

    private fun showDownloadProgress() {
        if (isFinishing || isDestroyed) return
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this).apply {
                setTitle("The Trend Lab update")
                setMessage("Connecting securely to update server…")
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                setCancelable(false)
                setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
                    downloadThread?.interrupt()
                    downloadThread = null
                    progressDialog = null
                }
            }
        }
        if (progressDialog?.isShowing != true) progressDialog?.show()
    }

    private fun clearPendingUpdateDownload(deleteFile: Boolean = true) {
        val path = updatePrefs.getString("apk_path", null)
        updatePrefs.edit()
            .remove("apk_path")
            .remove("expected_sha256")
            .putBoolean("install_pending", false)
            .apply()
        downloadedApkFile = null
        if (deleteFile && !path.isNullOrBlank()) {
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    private fun restorePendingUpdateDownload() {
        val path = updatePrefs.getString("apk_path", null) ?: return
        val file = File(path)
        if (!file.exists() || file.length() <= 0) {
            clearPendingUpdateDownload(false)
            return
        }
        try {
            val installedInfo = packageManager.getPackageInfo(packageName, 0)
            val installedCode = if (Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode else @Suppress("DEPRECATION") installedInfo.versionCode.toLong()
            val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            val downloadedCode = if (archiveInfo == null) -1L else if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode else @Suppress("DEPRECATION") archiveInfo.versionCode.toLong()
            // An APK already installed (or older) must never be offered again after restart.
            if (downloadedCode <= installedCode) {
                clearPendingUpdateDownload(true)
                return
            }
            downloadedApkFile = file
            showInstallReadyDialog()
        } catch (_: Exception) {
            clearPendingUpdateDownload(true)
        }
    }

    private fun launchInstallerOrShowButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow update installation")
                .setMessage("Enable ‘Allow from this source’ for The Trend Lab. Return here and the verified update installer will open automatically.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Open Settings > Install unknown apps > The Trend Lab and allow it.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        installDownloadedUpdate()
    }

    private fun showInstallReadyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Update downloaded")
            .setMessage("The update is downloaded and verified. Tap Install Update to open Android's installer.")
            .setPositiveButton("Install Update") { _, _ -> launchInstallerOrShowButton() }
            .setNegativeButton("Later", null)
            .show()
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
        val file = downloadedApkFile ?: updatePrefs.getString("apk_path", null)?.let { File(it) }
        if (file == null || !file.exists()) {
            showInstallReadyDialog()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivityForResult(install, 901)
        } catch (_: Exception) {
            showInstallReadyDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            (webView.parent as? FrameLayout)?.let { refreshConnectivity(it) }
            if (refreshOnNextResume) {
                refreshOnNextResume = false
                val currentUrl = webView.url.orEmpty()
                if (currentUrl.startsWith("https://the-trend-lab.thetrendlab24.workers.dev/") && hasValidatedInternet()) {
                    webView.reload()
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            if (updatePrefs.getBoolean("install_pending", false)) {
                val path = updatePrefs.getString("apk_path", null)
                val expected = updatePrefs.getString("expected_sha256", null)
                val file = path?.let { File(it) }
                if (file != null && file.exists() && verifyDownloadedApk(Uri.fromFile(file), expected)) {
                    downloadedApkFile = file
                    updatePrefs.edit().putBoolean("install_pending", false).apply()
                    Handler(Looper.getMainLooper()).postDelayed({ launchInstallerOrShowButton() }, 350)
                }
            }
        }
        val url = pendingDownloadUrl
        if (!url.isNullOrBlank()) {
            pendingDownloadUrl = null
            enqueueUpdateDownload(url, pendingExpectedSha256 ?: "")
            pendingExpectedSha256 = null
        }
    }

    override fun onPause() {
        refreshOnNextResume = true
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            doubleBackToExitPressedOnce = false
            webView.goBack()
        } else {
            if (doubleBackToExitPressedOnce) {
                finish()
                return
            }
            doubleBackToExitPressedOnce = true
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                doubleBackToExitPressedOnce = false
            }, 2000)
        }
    }

    override fun onDestroy() {
        downloadThread?.interrupt()
        try { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } } catch (_: Exception) { }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}