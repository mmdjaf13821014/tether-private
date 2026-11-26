package app.tether.client.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.tether.client.R
import app.tether.core.PairingCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Pairing: scans the QR code the Android phone shows (Tether › Pair, valid 5 minutes) with the camera and ZXing, or
 * takes the code typed or pasted by hand. Both forms parse: the `tether://pair?c=…` link
 * (the QR code since 1.2) and `tether1:…` (1.1 phones, typed codes).
 */
class PairActivity : Activity() {
    private val camThread = HandlerThread("pair-camera").apply { start() }
    private val cam = Handler(camThread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private lateinit var preview: TextureView
    private lateinit var hint: android.widget.TextView
    @Volatile private var done = false
    private val qr = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(16)); setBackgroundColor(col(R.color.background)) }
        root.addView(label(getString(R.string.pair_title), size = 22f, bold = true))
        hint = label(getString(R.string.pair_scan_hint), R.color.muted, 14f)
        root.addView(hint)
        root.addView(label(getString(R.string.pair_guide), R.color.muted, 13f))
        preview = TextureView(this)
        root.addView(FrameLayout(this).apply { addView(preview) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val manual = EditText(this).apply { hint = getString(R.string.pair_manual_hint); setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted)); setSingleLine() }
        root.addView(manual)
        root.addView(row(pill(getString(R.string.pair)) { use(manual.text.toString()) }, pill(getString(android.R.string.cancel), secondary = true) { finish() },
            gravity = Gravity.CENTER_VERTICAL))
        setContentView(root)
        fitSystemBars(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else hint.text = getString(R.string.pair_no_camera)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    override fun onPause() { stopCamera(); super.onPause() }
    override fun onDestroy() { camThread.quitSafely(); super.onDestroy() }

    private fun use(text: String) {
        val code = PairingCode.parse(text) ?: run { hint.text = getString(R.string.pair_bad_code); hint.setTextColor(col(R.color.warn)); return }
        done = true
        app.pair(code)
        finish()
    }

    private fun startCamera() {
        if (device != null) return
        if (preview.isAvailable) openCamera()
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: cm.cameraIdList.firstOrNull() ?: return
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        // A middle size: big enough for a QR code across the frame, small enough to decode several times a second.
        val size = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 1280 }.maxByOrNull { it.width * it.height } ?: Size(640, 480)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (done) return@setOnImageAvailableListener
                val plane = img.planes[0]
                val buf = plane.buffer
                val data = ByteArray(buf.remaining()).also { buf.get(it) }
                val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                val text = runCatching { qr.decode(BinaryBitmap(HybridBinarizer(src)), hints).text }.getOrNull()
                qr.reset()
                if (text != null && PairingCode.parse(text) != null) { done = true; runOnUiThread { use(text) } }
            } finally { img.close() }
        }, cam)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    val st = preview.surfaceTexture ?: return
                    st.setDefaultBufferSize(size.width, size.height)
                    val surface = Surface(st)
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface); addTarget(r.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            runCatching { s.setRepeatingRequest(req, null, cam) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { hint.text = getString(R.string.pair_no_camera) } }
                    }, cam)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null }
            }, cam)
        } catch (t: Throwable) { hint.text = getString(R.string.pair_no_camera) }
    }

    private fun stopCamera() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
    }
}
