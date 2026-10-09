package com.chromehisto.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val HOST = "chromehisto.app"
        const val HOME = "https://$HOST/home.html"
        private const val REQ_PICK = 1
        private const val REQ_SAVE = 2
        private const val REQ_PERMS = 3
    }

    private lateinit var web: WebView
    private lateinit var native: NativeContext
    private var pendingSave: File? = null
    private var pendingSaveText: String? = null
    private var currentReport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        native = NativeContext(this)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != HOST) return null
                val path = u.path ?: "/"
                return try {
                    when {
                        path == "/" || path == "/home.html" ->
                            WebResourceResponse("text/html", "utf-8", assets.open("home.html"))
                        path.startsWith("/reports/") -> {
                            val f = File(ReportBuilder.reportsDir(this@MainActivity), File(path).name)
                            WebResourceResponse("text/html", "utf-8", f.inputStream())
                        }
                        else -> WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                    }
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == HOST && u.scheme == "https") return false
                // Every other link opens instantly in the user's browser (normally Chrome).
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No app can open $u", Toast.LENGTH_LONG).show()
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                val path = Uri.parse(url).path ?: ""
                currentReport = if (path.startsWith("/reports/")) File(path).name else null
                invalidateOptionsMenu()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, native.hasLocationPermission(), false)
            }
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME)
        askPermissions()
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) runImport { it.importUris(listOf(uri)) }
    }

    override fun onResume() {
        super.onResume()
        native.startLocation()
    }

    override fun onPause() {
        native.stopLocation()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------- permissions
    private fun askPermissions() {
        val want = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        native.startLocation()
        js("window.onNative && onNative('permissions', null)")
    }

    // ------------------------------------------------------------- menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Home")
        if (currentReport != null) {
            menu.add(0, 2, 1, "Save report as…")
            menu.add(0, 3, 2, "Share report")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> web.loadUrl(HOME)
            2 -> currentReport?.let { saveReport(it) }
            3 -> currentReport?.let { shareReport(it) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------- actions
    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun status(msg: String) = js("window.onNative && onNative('status', ${JSONObject.quote(msg)})")

    private fun runImport(job: (Importer) -> Unit) {
        if (!web.url.orEmpty().endsWith("/home.html")) web.loadUrl(HOME)
        thread(name = "import") {
            val store = EntryStore(cacheDir)
            try {
                val importer = Importer(this, store, ::status)
                job(importer)
                if (importer.results.isEmpty()) throw IllegalStateException("No history records found.")
                status("Measuring device, Wi-Fi and location for the export record …")
                val ctx = ReportBuilder.exportContext(this, native)
                val name = "chrome-history-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                status("Writing report (${store.size} records) …")
                val files = ReportBuilder.build(this, importer.results, store, ctx, name)
                if (files.size > 1) status("Done: ${store.size} records in ${files.size} report parts")
                runOnUiThread { web.loadUrl("https://$HOST/reports/${files.first().name}") }
            } catch (e: Throwable) {
                js("window.onNative && onNative('error', ${JSONObject.quote(e.message ?: e.toString())})")
            } finally {
                store.close()
            }
        }
    }

    private fun saveReport(name: String) {
        pendingSave = File(ReportBuilder.reportsDir(this), File(name).name)
        pendingSaveText = null
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/html")
            .putExtra(Intent.EXTRA_TITLE, File(name).name), REQ_SAVE)
    }

    private fun shareReport(name: String) {
        val uri = ReportProvider.uriFor(this, File(name).name)
        val send = Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Chrome history report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share report"))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) runImport { it.importUris(uris) }
            }
            REQ_SAVE -> {
                val dst = data.data ?: return
                thread {
                    try {
                        contentResolver.openOutputStream(dst)!!.use { out ->
                            val text = pendingSaveText
                            if (text != null) out.write(text.toByteArray(Charsets.UTF_8))
                            else pendingSave!!.inputStream().use { it.copyTo(out) }
                        }
                        runOnUiThread { Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- JS bridge
    inner class Bridge {
        @JavascriptInterface fun snapshot(): String = native.snapshot().toString()

        @JavascriptInterface fun pick() = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PICK)
        }

        @JavascriptInterface fun readRoot() = runOnUiThread {
            runImport { it.importRoot() }
        }

        @JavascriptInterface fun appInfo(): String = JSONObject()
            .put("id", packageName)
            .put("version", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" })
            .toString()

        @JavascriptInterface fun listReports(): String = ReportBuilder.list(this@MainActivity).toString()

        @JavascriptInterface fun openReport(file: String) = runOnUiThread {
            web.loadUrl("https://$HOST/reports/${Uri.encode(File(file).name)}")
        }

        @JavascriptInterface fun shareReport(file: String) = runOnUiThread { this@MainActivity.shareReport(file) }

        @JavascriptInterface fun saveReport(file: String) = runOnUiThread { this@MainActivity.saveReport(file) }

        @JavascriptInterface fun deleteReport(file: String) {
            ReportBuilder.delete(this@MainActivity, file)
        }

        /** Used by the report template for CSV and click-log exports. */
        @JavascriptInterface fun saveFile(name: String, text: String, mime: String) = runOnUiThread {
            pendingSave = null
            pendingSaveText = text
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(mime.ifEmpty { "application/octet-stream" })
                .putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE)
        }

        @JavascriptInterface fun permissions(): String = JSONObject()
            .put("location", native.hasLocationPermission())
            .put("phone", checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            .put("android", Build.VERSION.RELEASE)
            .toString()

        @JavascriptInterface fun requestPermissions() = runOnUiThread {
            val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            // Once all are granted (or permanently denied), the app settings page is the only way to change them.
            if (missing.isEmpty()) openAppSettings() else askPermissions()
        }

        @JavascriptInterface fun openAppSettings() = runOnUiThread {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
    }
}
