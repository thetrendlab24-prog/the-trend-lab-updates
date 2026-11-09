package com.thetrendlab.billing

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import android.graphics.Color
import android.view.WindowManager
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val billingUrl = "https://the-trend-lab.thetrendlab24.workers.dev/billing.html"
    private val prefs by lazy { getSharedPreferences("ttl_billing_printer", Context.MODE_PRIVATE) }
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var pendingReceipt: String? = null

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
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
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
                    """
                    (function(){
                      function ttlNativePrint(){
                        var paperEl=document.getElementById('paper') || document.querySelector('[name="paper"],select[id*="paper"],select[name*="paper"]');
                        var paper=paperEl ? String(paperEl.value || paperEl.options?.[paperEl.selectedIndex]?.text || '58') : '58';
                        var receipt=document.querySelector('#receipt,#printArea,.print-area,#invoice,.invoice-receipt,.receipt');
                        var content=receipt ? (receipt.innerText || receipt.textContent || '') : (document.querySelector('#billSummary,.bill-summary,.current-bill')?.innerText || document.body.innerText || '');
                        BillingNative.printReceipt(content,paper);
                      }
                      try { Object.defineProperty(window,'print',{configurable:true,writable:true,value:ttlNativePrint}); }
                      catch(e){ window.print=ttlNativePrint; }
                      window.__ttlNativePrintReady=true;
                    })();
                    """.trimIndent(),
                    null
                )
                view?.evaluateJavascript(
                    "if(!window.__ttlPrintGuard){window.__ttlPrintGuard=setInterval(function(){if(!window.__ttlNativePrintReady){window.print=function(){BillingNative.printReceipt(document.querySelector('#receipt,#printArea,.print-area,#invoice,.invoice-receipt,.receipt')?.innerText||document.body.innerText,document.getElementById('paper')?.value||'58');};window.__ttlNativePrintReady=true;}},300);setTimeout(function(){clearInterval(window.__ttlPrintGuard);},10000);}",
                    null
                )
            }
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) Toast.makeText(this@MainActivity, "Billing screen unavailable. Please check connection or deployment.", Toast.LENGTH_LONG).show()
            }
        }
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl(billingUrl) else web.restoreState(savedInstanceState)
    }

    private inner class BillingBridge {
        @JavascriptInterface
        fun printReceipt(text: String, paper: String) {
            if (paper.contains("58")) {
                pendingReceipt = text
                runOnUiThread { ensureBluetoothPermissionThenChoose() }
            } else {
                runOnUiThread { openAndroidPrintDialog() }
            }
        }
    }

    private fun ensureBluetoothPermissionThenChoose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7401)
            return
        }
        choosePrinter()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7401) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) choosePrinter()
            else toast("Bluetooth permission is required to print receipts.")
        }
    }

    @Suppress("MissingPermission")
    private fun choosePrinter() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { toast("This device does not support Bluetooth."); return }
            if (!adapter.isEnabled) { toast("Please turn on Bluetooth and pair your 58mm printer."); return }
            val devices = adapter.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
            if (devices.isEmpty()) { toast("No paired Bluetooth printer found. Pair your 58mm printer in Android Bluetooth settings first."); return }
            val saved = prefs.getString("printer_mac", null)
            val preferred = devices.firstOrNull { it.address == saved && looksLikePrinter(it) }
            if (preferred != null) { connectAndPrint(preferred, pendingReceipt.orEmpty()); return }
            val printerDevices = devices.filter { looksLikePrinter(it) }
            val choices = if (printerDevices.isNotEmpty()) printerDevices else devices
            val labels = choices.map { "${it.name ?: "Bluetooth device"}  ·  ${it.address}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select 58mm Bluetooth Printer")
                .setItems(labels) { _, which ->
                    val device = choices[which]
                    prefs.edit().putString("printer_mac", device.address).apply()
                    connectAndPrint(device, pendingReceipt.orEmpty())
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) { toast("Bluetooth error: ${e.message ?: "Unable to list paired devices"}") }
    }

    private fun looksLikePrinter(device: BluetoothDevice): Boolean {
        val n = (device.name ?: "").lowercase()
        return listOf("printer", "pos", "thermal", "58", "80", "bt").any { n.contains(it) }
    }

    @Suppress("MissingPermission")
    private fun connectAndPrint(device: BluetoothDevice, receipt: String) {
        toast("Connecting to ${device.name ?: "Bluetooth printer"}…")
        thread(name = "ttl-bt-print") {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter?.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                val out: OutputStream = socket.outputStream
                out.write(byteArrayOf(0x1B, 0x40)) // ESC @ initialize
                out.write(byteArrayOf(0x1B, 0x61, 0x01)) // center
                out.write("THE TREND LAB\n".toByteArray(Charsets.US_ASCII))
                out.write("Fashion | Lifestyle | Accessories\n".toByteArray(Charsets.US_ASCII))
                out.write("--------------------------------\n".toByteArray(Charsets.US_ASCII))
                out.write(byteArrayOf(0x1B, 0x61, 0x00)) // left
                val clean = receipt
                    .replace("₹", "Rs.")
                    .replace("\u00a0", " ")
                    .replace(Regex("[\\t ]+"), " ")
                    .replace(Regex("\\n{3,}"), "\\n\\n")
                    .trim()
                val lines = clean.split("\\n").flatMap { wrapLine(it.trim(), 32) }
                for (line in lines) {
                    val safe = line.map { if (it.code in 32..126) it else '?' }.joinToString("")
                    out.write((safe + "\\n").toByteArray(Charsets.US_ASCII))
                }
                out.write("\\nThank you!\\n\\n".toByteArray(Charsets.US_ASCII))
                out.write(byteArrayOf(0x1D, 0x56, 0x00)) // full cut where supported
                out.flush()
                runOnUiThread { toast("Receipt sent to ${device.name ?: "Bluetooth printer"}.") }
            } catch (e: Exception) {
                runOnUiThread { toast("Printer connection failed: ${e.message ?: "Check pairing and printer power"}") }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun wrapLine(line: String, width: Int): List<String> {
        if (line.isBlank()) return listOf("")
        val words = line.split(Regex("\\s+"))
        val out = mutableListOf<String>()
        var current = ""
        for (word in words) {
            if (word.length > width) {
                if (current.isNotEmpty()) { out.add(current); current = "" }
                word.chunked(width).forEach { out.add(it) }
            } else if (current.isEmpty()) current = word
            else if (current.length + 1 + word.length <= width) current += " " + word
            else { out.add(current); current = word }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun openAndroidPrintDialog() {
        try {
            val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print("The Trend Lab Invoice", web.createPrintDocumentAdapter("The Trend Lab Invoice"), PrintAttributes.Builder().build())
        } catch (_: Exception) { toast("Unable to open Android print service.") }
    }

    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onSaveInstanceState(outState: Bundle) { web.saveState(outState); super.onSaveInstanceState(outState) }
    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onDestroy() { if (::web.isInitialized) { web.removeJavascriptInterface("BillingNative"); web.destroy() }; super.onDestroy() }
}
