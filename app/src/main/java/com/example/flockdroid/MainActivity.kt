package com.flockdroid.app

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ConsoleMessage
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize file chooser launcher for image uploads
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val results: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    // Multiple files selected
                    Array(data.clipData!!.itemCount) { i ->
                        data.clipData!!.getItemAt(i).uri
                    }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }

        webView = WebView(this)
        setContentView(webView)

        configureWebView()

        webView.loadUrl("https://flockmod.com/draw/")
    }

    private fun configureWebView() {
        val webSettings: WebSettings = webView.settings

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Intercept flockmod.js and replace it with your modified version
                if (url.contains("flockmod.js")) {
                    Log.d("FlockMod", "Intercepting flockmod.js from: $url")
                    return try {
                        val modifiedJs = assets.open("flockmod.js")
                        WebResourceResponse(
                            "text/javascript",
                            "UTF-8",
                            modifiedJs
                        )
                    } catch (e: Exception) {
                        Log.e("FlockMod", "Failed to load modified flockmod.js", e)
                        null
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("FlockMod-JS", "${message.message()} -- From line ${message.lineNumber()} of ${message.sourceId()}")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing callback
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val chooserIntent = Intent.createChooser(intent, "Select Image")
                fileChooserLauncher.launch(chooserIntent)
                return true
            }
        }

        // Download listener for saving images
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                if (url.startsWith("data:")) {
                    // Handle base64 data URI
                    saveDataUri(url, mimeType, contentDisposition)
                } else {
                    // Handle regular HTTP/HTTPS URL
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        val cookie = CookieManager.getInstance().getCookie(url)
                        addRequestHeader("Cookie", cookie)
                        addRequestHeader("User-Agent", userAgent)
                        setDescription("Downloading file...")
                        setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimeType)
                        )
                    }

                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(this@MainActivity, "Downloading...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("FlockMod", "Download failed", e)
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun saveDataUri(dataUri: String, mimeType: String?, contentDisposition: String?) {
        try {
            // Parse data URI: data:[<mediatype>][;base64],<data>
            val base64Index = dataUri.indexOf(";base64,")
            if (base64Index == -1) {
                Toast.makeText(this, "Invalid data URI format", Toast.LENGTH_SHORT).show()
                return
            }

            // Extract mime type from data URI if not provided
            val uriMimeType = if (mimeType.isNullOrEmpty() || mimeType == "application/octet-stream") {
                dataUri.substring(5, base64Index) // Extract from "data:" to ";base64"
            } else {
                mimeType
            }

            // Get base64 data
            val base64Data = dataUri.substring(base64Index + 8)
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

            // Determine file extension from mime type
            val extension = when {
                uriMimeType.contains("png") -> "png"
                uriMimeType.contains("jpeg") || uriMimeType.contains("jpg") -> "jpg"
                uriMimeType.contains("gif") -> "gif"
                uriMimeType.contains("webp") -> "webp"
                uriMimeType.contains("bmp") -> "bmp"
                else -> "png"
            }

            // Generate filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "flockmod_$timestamp.$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, uriMimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(decodedBytes)
                    }
                    Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_SHORT).show()
                    Log.d("FlockMod", "Saved data URI to: $fileName")
                } ?: run {
                    Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Android 9 and below - write directly to Downloads folder
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(decodedBytes)
                }
                Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_SHORT).show()
                Log.d("FlockMod", "Saved data URI to: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("FlockMod", "Failed to save data URI", e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}