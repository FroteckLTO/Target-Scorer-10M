package com.labed.targetscorer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var overlay: RingOverlay
    private lateinit var preview: PreviewView
    private var camera: Camera? = null
    @Volatile private var detect = true
    private var frameIndex = 0
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status.text = "Autorisation caméra requise"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "Zoom + détection expérimentale des anneaux"
            textSize = 16f
            setPadding(20, 16, 20, 16)
        }
        val cameraFrame = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = RingOverlay(this)
        cameraFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val zoomLabel = TextView(this).apply { text = "Zoom : réglage caméra (optique/numérique selon appareil)" }
        val zoom = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        val toggle = Button(this).apply {
            text = "Détection : ACTIVÉE"
            setOnClickListener {
                detect = !detect
                text = if (detect) "Détection : ACTIVÉE" else "Détection : ARRÊTÉE"
                if (!detect) this@MainActivity.overlay.show(emptyList(), 1, 1, 0)
            }
        }
        root.addView(status)
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(zoomLabel)
        root.addView(zoom)
        root.addView(toggle)
        setContentView(root)
        if (!OpenCVLoader.initLocal()) {
            status.text = "OpenCV indisponible"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(worker) { frame ->
                    try {
                        frameIndex++
                        if (detect && frameIndex % 3 == 0) {
                            val result = findRings(frame)
                            runOnUiThread {
                                if (detect) {
                                    overlay.show(result.first, result.second, result.third, frame.imageInfo.rotationDegrees)
                                    status.text = "Anneaux candidats : ${result.first.size} | Détection expérimentale (pas de score)"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Analyse : ${e.message}" }
                    } finally { frame.close() }
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) { status.text = "Erreur caméra : ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    // Uses the Y plane only; its pixelStride/rowStride are respected.
    private fun findRings(frame: ImageProxy): Triple<List<Ring>, Int, Int> {
        val plane = frame.planes[0]
        val width = frame.width
        val height = frame.height
        val bytes = ByteArray(width * height)
        val buffer = plane.buffer.duplicate()
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * width + x] = buffer.get(y * stride + x * pixelStride)
            }
        }
        val raw = Mat(height, width, CvType.CV_8UC1)
        val small = Mat()
        val blur = Mat()
        val edges = Mat()
        val circles = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            raw.put(0, 0, bytes)
            val scale = min(1.0, 640.0 / max(width, height))
            Imgproc.resize(raw, small, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, blur, Size(7.0, 7.0), 1.5)
            val minRadius = max(4, min(small.cols(), small.rows()) / 120)
            val maxRadius = max(minRadius + 1, min(small.cols(), small.rows()) / 2)
            Imgproc.HoughCircles(blur, circles, Imgproc.HOUGH_GRADIENT, 1.0,
                8.0, 110.0, 22.0, minRadius, maxRadius)
            val candidates = mutableListOf<Ring>()
            for (i in 0 until min(circles.cols(), 80)) {
                val c = circles.get(0, i) ?: continue
                candidates.add(Ring(c[0] / scale, c[1] / scale, c[2] / scale))
            }
            // Contour fallback: accepts imperfect or stylized near-circular outlines.
            Imgproc.Canny(blur, edges, 55.0, 135.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                if (contour.rows() < 12) continue
                val area = Imgproc.contourArea(contour)
                val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                if (area < 80 || perimeter < 20) continue
                val circularity = 4.0 * Math.PI * area / (perimeter * perimeter)
                if (circularity !in 0.55..1.25) continue
                val bounds = Imgproc.boundingRect(contour)
                val ratio = bounds.width.toDouble() / max(1, bounds.height)
                if (ratio !in 0.70..1.40) continue
                val r = (bounds.width + bounds.height) / 4.0 / scale
                candidates.add(Ring((bounds.x + bounds.width / 2.0) / scale,
                    (bounds.y + bounds.height / 2.0) / scale, r))
            }
            // Merge duplicate candidates at similar position and radius.
            val unique = mutableListOf<Ring>()
            for (ring in candidates.sortedByDescending { it.r }) {
                if (unique.none { other ->
                    abs(other.x - ring.x) < max(5.0, ring.r * 0.12) &&
                    abs(other.y - ring.y) < max(5.0, ring.r * 0.12) &&
                    abs(other.r - ring.r) < max(4.0, ring.r * 0.14)
                }) unique.add(ring)
                if (unique.size >= 25) break
            }
            return Triple(unique, width, height)
        } finally {
            contours.forEach { it.release() }
            hierarchy.release(); circles.release(); edges.release()
            blur.release(); small.release(); raw.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}

data class Ring(val x: Double, val y: Double, val r: Double)

class RingOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private var rings: List<Ring> = emptyList()
    private var sourceW = 1
    private var sourceH = 1
    private var rotation = 0

    fun show(value: List<Ring>, w: Int, h: Int, degrees: Int) {
        rings = value; sourceW = max(1, w); sourceH = max(1, h)
        rotation = degrees
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rotated = rotation == 90 || rotation == 270
        val rw = if (rotated) sourceH.toFloat() else sourceW.toFloat()
        val rh = if (rotated) sourceW.toFloat() else sourceH.toFloat()
        val scale = max(width / rw, height / rh)
        val offsetX = (width - rw * scale) / 2f
        val offsetY = (height - rh * scale) / 2f
        for (ring in rings) {
            val (x, y) = when (rotation) {
                90 -> Pair(sourceH - ring.y, ring.x)
                180 -> Pair(sourceW - ring.x, sourceH - ring.y)
                270 -> Pair(ring.y, sourceW - ring.x)
                else -> Pair(ring.x, ring.y)
            }
            canvas.drawCircle(offsetX + x.toFloat() * scale,
                offsetY + y.toFloat() * scale, ring.r.toFloat() * scale, paint)
        }
    }
}
