package com.golftracer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

data class PointF3D(
    val x: Float, val y: Float,
    val confidence: Float = 1f,
    val timestamp: Long = System.nanoTime()
)

class BallTracker {
    private val path = mutableListOf<PointF3D>()
    private var lastKnownPosition: PointF3D? = null
    private var manualOverride: PointF? = null
    private val lock = Any()
    private val trailMaxLength = 90
    private val pixelsPerMeterAt10m = 65f

    fun setManualTap(point: PointF) {
        synchronized(lock) {
            manualOverride = point
            val pt = PointF3D(point.x, point.y, 1f)
            lastKnownPosition = pt
            path.add(pt)
        }
    }

    fun processFrame(image: android.media.Image, w: Int, h: Int): List<PointF3D> {
        synchronized(lock) {
            manualOverride?.let { tap ->
                val pt = PointF3D(tap.x, tap.y, 1f)
                lastKnownPosition = pt
                path.add(pt)
                manualOverride = null
                return path.toList()
            }
            val detected = detectBall(image, w, h)
            if (detected != null) {
                lastKnownPosition = detected
                addToPath(detected)
            } else {
                lastKnownPosition?.let { last ->
                    val pred = PointF3D(last.x + 1.5f, last.y - 2.5f, 0.45f)
                    addToPath(pred)
                    lastKnownPosition = pred
                }
            }
            return path.toList()
        }
    }

    private fun detectBall(image: android.media.Image, w: Int, h: Int): PointF3D? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        var sumX = 0f; var sumY = 0f; var count = 0
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val idx = (y * w + x) * 4
                if (idx + 2 >= data.size) continue
                val r = data[idx+0].toInt() and 0xFF
                val g = data[idx+1].toInt() and 0xFF
                val b = data[idx+2].toInt() and 0xFF
                val brightness = (r + g + b) / 3
                if (brightness > 175 && kotlin.math.abs(r - g) < 70 && kotlin.math.abs(g - b) < 90) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count in 8..900) {
            return PointF3D((sumX/count/w)*1080f, (sumY/count/h)*2400f, 1f)
        }
        return null
    }

    private fun addToPath(point: PointF3D) {
        path.add(point)
        while (path.size > trailMaxLength) path.removeFirst()
    }

    fun getCarryDistanceMeters(): Float {
        synchronized(lock) {
            if (path.size < 2) return 0f
            val start = path.first(); val end = path.last()
            val pixelDistance = hypot(end.x - start.x, end.y - start.y)
            return pixelDistance / pixelsPerMeterAt10m
        }
    }

    fun reset() { synchronized(lock) { path.clear(); lastKnownPosition = null; manualOverride = null } }
}

class TrailRenderer @JvmOverloads constructor(
    context: android.content.Context, attrs: android.util.AttributeSet? = null
) : android.view.View(context, attrs) {
    private val basePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 7f
    }
    private var pathPoints: List<PointF3D> = emptyList()

    fun updatePath(points: List<PointF3D>) { pathPoints = points; invalidate() }
    fun clear() { pathPoints = emptyList(); invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val points = pathPoints
        if (points.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1 until points.size) {
            val prev = points[i-1]; val curr = points[i]
            val progress = i / points.size.toFloat()
            val color = getGradientColor(progress)
            val path = android.graphics.Path()
            path.moveTo(prev.x / 1080f * w, prev.y / 2400f * h)
            path.lineTo(curr.x / 1080f * w, curr.y / 2400f * h)
            basePaint.color = color
            basePaint.alpha = (255 * (0.35 + 0.65 * progress)).toInt()
            canvas.drawPath(path, basePaint)
        }
    }

    private fun getGradientColor(t: Float): Int {
        val r: Int; val g: Int
        when {
            t < 0.5f -> {
                val mid = t * 2
                r = (mid * 255).toInt(); g = 255
            }
            else -> {
                val mid = (t - 0.5f) * 2
                r = 255; g = ((1 - mid) * 255).toInt()
            }
        }
        return android.graphics.Color.rgb(r, g, 0)
    }
}

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var binding: com.golftracer.databinding.ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var ballTracker: BallTracker
    private lateinit var trailRenderer: TrailRenderer
    private var lastVideoFile: File? = null
    private val frameHistory = mutableListOf<List<PointF3D>>()
    private val REPLAY_FRAME_DELAY = 67L
    private var isRecording = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.all { it.value }) initCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = com.golftracer.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ballTracker = BallTracker()
        trailRenderer = binding.trailOverlay
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val tapX = event.x / binding.previewView.width * 1080f
                val tapY = event.y / binding.previewView.height * 2400f
                ballTracker.setManualTap(PointF(tapX, tapY))
                Toast.makeText(this, "🎯 Ball repositioned!", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnReplay.setOnClickListener { playReplay() }
        binding.btnExport.setOnClickListener { exportVideo() }

        if (allPermissionsGranted()) initCamera()
        else requestPermissions.launch(REQUIRED_PERMISSIONS)
    }

    private fun initCamera() {
        val cp = ProcessCameraProvider.getInstance(this)
        cp.addListener({
            val provider = cp.get()
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1080, 2400))
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(1080, 2400))
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imgProxy ->
                        if (isRecording) {
                            val pts = ballTracker.processFrame(imgProxy.image!!, 1080, 2400)
                            synchronized(frameHistory) {
                                frameHistory.add(pts)
                                if (frameHistory.size > 300) frameHistory.removeFirst()
                            }
                            runOnUiThread {
                                trailRenderer.updatePath(pts)
                                binding.tvCarry.text = "Carry: %.1fm".format(ballTracker.getCarryDistanceMeters())
                            }
                        }
                        imgProxy.close()
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            ballTracker.reset()
            trailRenderer.clear()
            synchronized(frameHistory) { frameHistory.clear() }
            binding.statsPanel.visibility = View.VISIBLE
            binding.btnReplay.visibility = View.GONE
            binding.btnExport.visibility = View.GONE
            binding.btnRecord.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            lastVideoFile = File(dir, "GolfShot_$timeStamp.mp4")
            Toast.makeText(this, "🎬 Recording… Tap to fix ball", Toast.LENGTH_LONG).show()
        } else {
            binding.btnRecord.setBackgroundColor(getColor(android.R.color.darker_gray))
            binding.btnReplay.visibility = View.VISIBLE
            binding.btnExport.visibility = View.VISIBLE
            Toast.makeText(this, "✅ Shot saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playReplay() {
        binding.btnReplay.isEnabled = false
        binding.btnRecord.isEnabled = false
        val history = synchronized(frameHistory) { frameHistory.toList() }
        object : Handler(Looper.getMainLooper()) {
            var idx = 0
            override fun handleMessage(msg: android.os.Message) {
                if (idx < history.size) {
                    trailRenderer.updatePath(history[idx])
                    idx++
                    sendEmptyMessageDelayed(0, REPLAY_FRAME_DELAY)
                } else {
                    trailRenderer.clear()
                    binding.btnReplay.isEnabled = true
                    binding.btnRecord.isEnabled = true
                    removeCallbacksAndMessages(null)
                }
            }
        }.sendEmptyMessage(0)
    }

    private fun exportVideo() {
        val file = lastVideoFile ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Export to…"))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    }
}
