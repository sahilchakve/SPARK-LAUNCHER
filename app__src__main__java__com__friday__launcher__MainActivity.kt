package com.friday.launcher

import android.Manifest
import android.graphics.Color
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity(), StarkBridge.Host {

    private lateinit var webView: WebView
    private lateinit var loader: WebViewAssetLoader
    private lateinit var bridge: StarkBridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        pendingPermission?.let { it.grant(it.resources) }
        pendingPermission = null
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            bridge.startListening()
        } else {
            runJs("window.onVoiceState && onVoiceState('denied')")
        }
    }

    override fun runJs(js: String) {
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    override fun requestMicPermission() {
        runOnUiThread { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        webView.setBackgroundColor(Color.parseColor("#050608"))

        // Serves files from assets over https so localStorage, camera and mic work
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        bridge = StarkBridge(this, this)
        webView.addJavascriptInterface(bridge, "Android")

        // Notification and call events go straight to the HUD
        NotifBus.sink = { json -> runJs("window.onNativeEvent && onNativeEvent($json)") }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.url?.let { loader.shouldInterceptRequest(it) }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                filePicker.launch("*/*")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val needed = mutableListOf<String>()
                if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needed += Manifest.permission.CAMERA
                if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needed += Manifest.permission.RECORD_AUDIO
                val missing = needed.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    req.grant(req.resources)
                } else {
                    pendingPermission = req
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }

        // Back button: JS decides (close frame or sheet, or go from JARVIS to home)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(typeof starkBack==='function')&&starkBack()", null)
            }
        })

        webView.loadUrl("https://appassets.androidplatform.net/assets/friday_home.html")
    }

    override fun onResume() {
        super.onResume()
        // Refresh the app list in case apps were installed or removed
        webView.evaluateJavascript("if(window.refreshNative)refreshNative(); if(window.onLauncherResume)onLauncherResume();", null)
    }

    override fun onDestroy() {
        NotifBus.sink = null
        bridge.shutdownSpeech()
        bridge.shutdownTts()
        super.onDestroy()
    }
}
