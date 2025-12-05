package com.example.ipcam

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

import android.media.MediaPlayer
import androidx.preference.PreferenceManager

class MainActivity : ComponentActivity() {
    private lateinit var uploader: WsJpegUploader
    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private var baseInfoText: String = ""

    private lateinit var arrowOverlay: ArrowOverlay

    private lateinit var fused: com.google.android.gms.location.FusedLocationProviderClient
    private var locCallback: LocationCallback? = null

    private enum class Direction { LEFT, FORWARD, RIGHT }

    private lateinit var mpLeft: MediaPlayer
    private lateinit var mpRight: MediaPlayer
    private lateinit var mpForward: MediaPlayer

    @Volatile private var lastSpokenDir: Direction? = null

    // === Latency met frameId ===
    private val pendingFrames = ConcurrentHashMap<Long, Long>() // frameId -> sentAt(elapsedRealtime)
    @Volatile private var lastHeadingVal: Double? = null
    @Volatile private var lastLatencyMs: Long? = null
    @Volatile private var lastLocLine: String = ""

    @Volatile private var lastSentFrameId: Long? = null

    @Volatile private var headingTolerancePct: Int = 10


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val cam = grantResults[Manifest.permission.CAMERA] == true
        val fine = grantResults[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grantResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (cam) startCamera() else info.text = "Camera-permissie geweigerd."
        if (fine || coarse) startLocationUpdates() else {
            info.text = info.text.toString() + "\nLocatie-permissie geweigerd."
        }
    }


    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        when (key) {
            "min_interval_ms" -> {
                val v = sp.getString(key, "200")?.toLongOrNull() ?: 200L
                uploader.updateMinIntervalMs(v)
                baseInfoText = "Signaling WS: ws://94.111.36.87:9000\nLocal IP: ${getWifiIp() ?: "0.0.0.0"}\nminIntervalMs: $v ms"
                updateInfoText()
            }

            // ✅ Correcte key + variabele updaten
            "heading_tolerance_pct" -> {
                val v = sp.getString(key, "10")?.toIntOrNull()?.coerceIn(0, 40) ?: 10
                headingTolerancePct = v
                updateInfoText()
            }

            "model_select" -> {
                val v = sp.getString(key, "unrealsim.pt") ?: "unrealsim.pt"
                uploader.updateModel(v)
                baseInfoText = "Signaling WS: ws://94.111.36.87:9000\nLocal IP: ${getWifiIp() ?: "0.0.0.0"}\nminIntervalMs: $v ms"
                updateInfoText()
            }


        }
    }


    private var nv21Buf: ByteArray? = null

    private fun yuv420888ToNv21(image: android.media.Image, out: ByteArray) {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var outPos = 0
        if (yRowStride == w) {
            yBuf.get(out, 0, w * h); outPos = w * h
        } else {
            val yRow = ByteArray(yRowStride)
            for (row in 0 until h) {
                yBuf.get(yRow, 0, yRowStride)
                System.arraycopy(yRow, 0, out, outPos, w)
                outPos += w
            }
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val chromaWidth = w / 2
        val chromaHeight = h / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                out[outPos++] = vBuf.get(vIndex)
                out[outPos++] = uBuf.get(uIndex)
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility =
            (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)


        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        info = findViewById(R.id.info)
        arrowOverlay = findViewById(R.id.arrowOverlay)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val initialTol = prefs.getString("heading_tolerance_pct", "10")?.toIntOrNull() ?: 10
        headingTolerancePct = initialTol


        val ip = getWifiIp() ?: "0.0.0.0"
        baseInfoText = "Signaling WS: ws://94.111.36.87:9000\nLocal IP: $ip"
        info.text = baseInfoText

        val initialMin = prefs.getString("min_interval_ms", "200")?.toLongOrNull() ?: 200L

        info.alpha = 0f

        previewView.setOnClickListener {
            if (info.alpha == 0f) {
                info.animate().alpha(1f).setDuration(300).start()
                // Automatisch weer verbergen na 5 seconden
                info.postDelayed({ info.animate().alpha(0f).setDuration(300).start() }, 5000)
            } else {
                info.animate().alpha(0f).setDuration(300).start()
            }
        }




        // === Uploader met frameId-latency ===
        uploader = WsJpegUploader(
            url = "ws://94.111.36.87:9000",
            minIntervalMs = initialMin,
            onHeading = { heading, frameId ->
                runOnUiThread {
                    lastHeadingVal = heading
                    val latencyText: String = if (frameId != null) {
                        val sentAt = pendingFrames.remove(frameId)
                        if (sentAt != null) {
                            val ms = android.os.SystemClock.elapsedRealtime() - sentAt
                            lastLatencyMs = ms
                            "$ms ms"
                        } else {
                            // geen match: toon laatst bekende of —
                            lastLatencyMs?.let { "$it ms" } ?: "—"
                        }
                    } else {
                        lastLatencyMs?.let { "$it ms" } ?: "—"
                    }

                    arrowOverlay.setHeading(heading)
                    val dir = headingToDirection(heading)
                    speakDirectionIfChanged(dir)

                    updateInfoText()
                }
            },
            onSent = { sentAt, frameId ->
                pendingFrames[frameId] = sentAt
                lastSentFrameId = frameId
                runOnUiThread { updateInfoText() }

            }
        )



        fused = LocationServices.getFusedLocationProviderClient(this)

        mpLeft = MediaPlayer.create(this, R.raw.left)
        mpRight = MediaPlayer.create(this, R.raw.right)
        mpForward = MediaPlayer.create(this, R.raw.forward)

        // Permissions in één keer vragen
        permissionLauncher.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }



    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val image = imageProxy.image
                if (image == null || imageProxy.format != ImageFormat.YUV_420_888) {
                    imageProxy.close(); return@setAnalyzer
                }
                try {
                    nv21Buf = nv21Buf?.takeIf { it.size >= imageProxy.width * imageProxy.height * 3 / 2 }
                        ?: ByteArray(imageProxy.width * imageProxy.height * 3 / 2)

                    yuv420888ToNv21(image, nv21Buf!!)

                    val yuv = YuvImage(
                        nv21Buf,
                        ImageFormat.NV21,
                        imageProxy.width,
                        imageProxy.height,
                        null
                    )
                    val baos = ByteArrayOutputStream()
                    yuv.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 50, baos)
                    val jpeg = baos.toByteArray()
                    latestJpeg.set(jpeg)

                    uploader.trySend(jpeg)  // binary JPEG over WS (stuurt ook frame_meta + frameId)
                } catch (_: Exception) {
                } finally {
                    imageProxy.close()
                }
            }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val req = LocationRequest.Builder(1000L)
            .setMinUpdateIntervalMillis(500L)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        locCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                val json = """{
                  "type":"location",
                  "lat":${loc.latitude},
                  "lon":${loc.longitude},
                  "accuracy_m":${loc.accuracy},
                  "ts":${System.currentTimeMillis()},
                }""".trimIndent()

                uploader.sendJson(json)

                lastLocLine = "Loc: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)} (±${loc.accuracy.toInt()}m)"
                updateInfoText()
            }
        }

        fused.requestLocationUpdates(req, locCallback as LocationCallback, mainLooper)
    }

    private fun updateInfoText() {
        val headingStr = lastHeadingVal?.let { "%.1f°".format(it) } ?: "—"
        val latencyStr = lastLatencyMs?.let { "$it ms" } ?: "—"
        val frameIdStr = lastSentFrameId?.toString() ?: "—"

        info.text = buildString {
            append(baseInfoText)
            append("\nHeading: "); append(headingStr)
            append("\nLast Sent FrameId: "); append(frameIdStr)
            append("\nLatency: "); append(latencyStr)
            if (lastLocLine.isNotEmpty()) {
                append("\n"); append(lastLocLine)
            }
            append("\nTol: ±${"%.1f".format(90.0 * headingTolerancePct / 100.0)}° (${headingTolerancePct}%)")

        }
    }

    private fun headingToDirection(heading: Double): Direction {
        val tolDeg = 90.0 * (headingTolerancePct.coerceIn(0, 40)) / 100.0
        return when {
            heading <= 90.0 - tolDeg -> Direction.RIGHT
            heading >= 90.0 + tolDeg -> Direction.LEFT
            else -> Direction.FORWARD
        }
    }

    // Speel enkel bij verandering
    private fun speakDirectionIfChanged(dir: Direction) {
        if (dir == lastSpokenDir) return
        fun resetPlayer(p: MediaPlayer) {
            if (p.isPlaying) p.pause()
            try { p.seekTo(0) } catch (_: Exception) {}
        }
        resetPlayer(mpLeft); resetPlayer(mpRight); resetPlayer(mpForward)
        val player = when (dir) {
            Direction.LEFT -> mpLeft
            Direction.RIGHT -> mpRight
            Direction.FORWARD -> mpForward
        }
        try { player.start() } catch (_: Exception) {}
        lastSpokenDir = dir
    }


    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        headingTolerancePct = prefs.getString("heading_tolerance_pct", "10")
            ?.toIntOrNull()?.coerceIn(0, 40) ?: 10
        updateInfoText()
    }


    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        cameraExecutor.shutdown()
        uploader.shutdown()
        locCallback?.let { fused.removeLocationUpdates(it) }
        try { mpLeft.release() } catch (_: Exception) {}
        try { mpRight.release() } catch (_: Exception) {}
        try { mpForward.release() } catch (_: Exception) {}
    }

    private fun getWifiIp(): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress.indexOf(':') < 0 }
            ?.hostAddress
    }
}

private fun Any.getString(string: String, string2: String) {}
