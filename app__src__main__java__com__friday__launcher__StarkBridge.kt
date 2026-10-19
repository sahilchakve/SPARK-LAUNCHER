package com.friday.launcher

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Bridge between the HTML launcher and Android.
 * JS calls: Android.getApps(), Android.launch(pkg), Android.openUrl(url), Android.getDeviceInfo()
 */
class StarkBridge(private val context: Context, private val host: Host) {

    interface Host {
        fun runJs(js: String)
        fun requestMicPermission()
    }

    private val pm = context.packageManager
    private val iconCache = HashMap<String, String>()

    // Live sensor cache (updated by listeners, read by getDeviceInfo)
    private val accel = FloatArray(3)
    private var hasAccel = false
    private var lightLux = -1f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accel[0] = event.values[0]
                    accel[1] = event.values[1]
                    accel[2] = event.values[2]
                    hasAccel = true
                }
                Sensor.TYPE_LIGHT -> lightLux = event.values[0]
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    // Native text-to-speech (Android WebView does not provide the browser speech API)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: Triple<String, Double, Double>? = null
    private var ttsFailed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsFailed = true
                pending = null
            } else {
                val r = tts?.setLanguage(Locale("en", "IN"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                ttsReady = true
                pending?.let { (text, pitch, rate) -> doSpeak(text, pitch, rate) }
                pending = null
            }
        }
    }

    private fun doSpeak(text: String, pitch: Double, rate: Double) {
        tts?.setPitch(pitch.toFloat())
        tts?.setSpeechRate(rate.toFloat())
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "stark")
    }

    @JavascriptInterface
    fun speak(text: String, pitch: Double, rate: Double) {
        mainHandler.post {
            ensureTts()
            if (ttsReady) doSpeak(text, pitch, rate) else pending = Triple(text, pitch, rate)
        }
    }

    @JavascriptInterface
    fun ttsStatus(): String = when {
        ttsFailed -> "failed (no TTS engine, install Google Text-to-speech)"
        ttsReady -> "ready"
        tts != null -> "loading"
        else -> "not started"
    }

    fun shutdownTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    @JavascriptInterface
    fun getApps(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val result = JSONArray()

        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val label = ri.loadLabel(pm).toString()
            val obj = JSONObject()
            obj.put("id", pkg)
            obj.put("pkg", pkg)
            obj.put("name", label)
            obj.put("emoji", label.take(1).uppercase())
            obj.put("color", "#222222")
            obj.put("cat", "Apps")
            obj.put("action", "native")
            obj.put("native", true)
            obj.put("icon", iconData(pkg, ri))
            result.put(obj)
        }
        return result.toString()
    }

    private fun iconData(pkg: String, ri: android.content.pm.ResolveInfo): String {
        iconCache[pkg]?.let { return it }
        val value = try {
            val drawable = ri.loadIcon(pm)
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
        iconCache[pkg] = value
        return value
    }

    @JavascriptInterface
    fun launch(pkg: String) {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Live device snapshot as JSON. Never throws; returns "{}" on failure. */
    @JavascriptInterface
    fun getDeviceInfo(): String = try {
        buildDeviceInfo().toString()
    } catch (e: Exception) {
        "{}"
    }

    private fun buildDeviceInfo(): JSONObject {
        val root = JSONObject()
        root.put("source", "android")

        // Battery
        val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = bi?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val pct: Any = if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL
        root.put(
            "battery", JSONObject()
                .put("level", pct)
                .put("charging", charging)
                .put("tempC", tempC)
                .put("voltageMv", voltage)
                .put("health", healthName(health))
        )

        // RAM
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedPct = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0
        root.put(
            "memory", JSONObject()
                .put("totalMb", mi.totalMem / 1048576)
                .put("availMb", mi.availMem / 1048576)
                .put("usedPct", usedPct)
                .put("low", mi.lowMemory)
        )

        // Storage (internal /data)
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb = stat.totalBytes / 1073741824.0
        val freeGb = stat.availableBytes / 1073741824.0
        val storagePct = if (totalGb > 0) ((totalGb - freeGb) * 100 / totalGb).toInt() else 0
        root.put(
            "storage", JSONObject()
                .put("totalGb", round1(totalGb))
                .put("freeGb", round1(freeGb))
                .put("usedPct", storagePct)
        )

        // CPU: core count, current frequency per core (often restricted), load average
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = JSONArray()
        for (i in 0 until cores) {
            val khz = readText("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()
            val value: Any = if (khz != null) khz / 1000 else JSONObject.NULL
            freqs.put(value)
        }
        val load: Any = readText("/proc/loadavg")?.split(" ")?.firstOrNull()?.toDoubleOrNull() ?: JSONObject.NULL
        root.put("cpu", JSONObject().put("cores", cores).put("freqMhz", freqs).put("load", load))

        // Network
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps: NetworkCapabilities? = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
        val downMbps: Any = if (downKbps > 0) round1(downKbps / 1000.0) else JSONObject.NULL
        root.put(
            "network", JSONObject()
                .put("online", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                .put("transport", transport)
                .put("downMbps", downMbps)
        )

        // Display
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        val refresh = Math.round(wm.defaultDisplay.refreshRate)
        val brightness: Any = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        } catch (e: Exception) {
            JSONObject.NULL
        }
        root.put(
            "display", JSONObject()
                .put("w", metrics.widthPixels)
                .put("h", metrics.heightPixels)
                .put("dpr", metrics.density.toDouble())
                .put("refresh", refresh)
                .put("brightness", brightness)
        )

        // Thermal status (Android 10+)
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            val pmgr = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalName(pmgr.currentThermalStatus)
        } else {
            "n/a"
        }
        root.put("thermal", JSONObject().put("status", thermal))

        // Sensors (live values from the listeners)
        val accelMag: Any = if (hasAccel) {
            sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]).toDouble()
        } else {
            JSONObject.NULL
        }
        val light: Any = if (lightLux >= 0) lightLux.toDouble() else JSONObject.NULL
        root.put("sensors", JSONObject().put("accelMag", accelMag).put("light", light))

        // Device info
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .size
        root.put(
            "device", JSONObject()
                .put("maker", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("uptimeMin", SystemClock.elapsedRealtime() / 60000)
                .put("apps", launchable)
        )
        return root
    }

    // ---------- Continuous voice (native SpeechRecognizer, restarts itself until OFF) ----------
    private var listening = false
    private var recognizer: SpeechRecognizer? = null

    private val recogListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                host.runJs("window.onVoiceResult && onVoiceResult(${JSONObject.quote(text)})")
            }
            restartSoon()
        }
        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                listening = false
                host.runJs("window.onVoiceState && onVoiceState('denied')")
                return
            }
            restartSoon()
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun startListening() {
        if (!micGranted()) {
            host.requestMicPermission()
            return
        }
        listening = true
        host.runJs("window.onVoiceState && onVoiceState('listening')")
        mainHandler.post { startOneRecognition() }
    }

    @JavascriptInterface
    fun stopListening() {
        listening = false
        mainHandler.post { recognizer?.cancel() }
        host.runJs("window.onVoiceState && onVoiceState('off')")
    }

    private fun startOneRecognition() {
        if (!listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listening = false
            host.runJs("window.onVoiceState && onVoiceState('unavailable')")
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recogListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        sr.startListening(intent)
    }

    private fun restartSoon() {
        if (!listening) return
        mainHandler.postDelayed({ startOneRecognition() }, 250)
    }

    fun shutdownSpeech() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    // ---------- Notification access and media ----------
    @JavascriptInterface
    fun hasNotifAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(context.packageName)
    }

    @JavascriptInterface
    fun openNotifAccess() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @JavascriptInterface
    fun mediaInfo(): String {
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return "null"
            val session = msm.getActiveSessions(ComponentName(context, StarkNotifService::class.java)).firstOrNull()
                ?: return "null"
            val md = session.metadata
            JSONObject()
                .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                .put("playing", session.playbackState?.state == PlaybackState.STATE_PLAYING)
                .toString()
        } catch (e: Exception) {
            "null"
        }
    }

    @JavascriptInterface
    fun mediaControl(cmd: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (cmd == "volup") {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return
        }
        if (cmd == "voldown") {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return
        }
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        val session = (try {
            msm.getActiveSessions(ComponentName(context, StarkNotifService::class.java)).firstOrNull()
        } catch (e: Exception) {
            null
        }) ?: return
        val tc = session.transportControls ?: return
        when (cmd) {
            "play" -> tc.play()
            "pause" -> tc.pause()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
        }
    }

    private fun readText(path: String): String? = try {
        File(path).readText().trim()
    } catch (e: Exception) {
        null
    }

    private fun round1(v: Double): Double = Math.round(v * 10) / 10.0

    private fun healthName(h: Int): String = when (h) {
        2 -> "good"
        3 -> "overheat"
        4 -> "dead"
        5 -> "overvoltage"
        6 -> "failure"
        7 -> "cold"
        else -> "unknown"
    }

    private fun thermalName(s: Int): String = when (s) {
        0 -> "normal"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }
}
