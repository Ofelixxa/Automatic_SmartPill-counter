package com.example.contadordepills

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// ESTADOS DE LA APLICACIÓN
enum class EstadoApp {
    CAMARA_EN_VIVO,
    PREVIEW_CRUDA,
    RESULTADO_PROCESADO
}

class MainActivity : AppCompatActivity() {

    // Vistas
    private lateinit var viewFinder: PreviewView
    private lateinit var imageOverlay: ImageView
    private lateinit var textConteo: TextView
    private lateinit var textInstrucciones: TextView
    private lateinit var spinnerMedicamento: Spinner

    // Layouts de Botones
    private lateinit var layoutCaptura: LinearLayout
    private lateinit var layoutValidacion: LinearLayout
    private lateinit var layoutInventario: LinearLayout

    // Botones
    private lateinit var btnCapturarTelefono: Button
    private lateinit var btnCapturarESP32: Button
    private lateinit var btnProcesar: Button
    private lateinit var btnCancelar: Button
    private lateinit var btnGuardarInventario: Button

    // Variables de Estado
    private lateinit var cameraExecutor: ExecutorService
    private var estadoActual = EstadoApp.CAMARA_EN_VIVO
    private var frameActualTelefono: Bitmap? = null
    private var bitmapCapturadoCrudo: Bitmap? = null
    private var ultimoBitmapProcesado: Bitmap? = null
    private var ultimoConteoDetectado: Int = 0
    private var modoActual: String = "Teléfono"

    // --- VARIABLES PARA SELECCIÓN MANUAL Y VARITA MÁGICA ---
    private var listaContornosGlobal = ArrayList<MatOfPoint>()
    private var estadoSeleccionContornos = BooleanArray(0)
    private var matVisualizacionBase: Mat? = null

    // --- CONFIGURACIÓN DE RED (IP ACTUALIZADA) ---
    private val IP_ESP32_CAM = "10.97.161.129"  // IP de la cámara
    private val IP_ESP32_LCD = "10.97.161.171" // IP de la pantalla

    // Android pedirá la foto a la cámara
    private val URL_ESP32_CAPTURA = "http://$IP_ESP32_CAM/captura"

    // Android mandará el número a la pantalla
    private val URL_ESP32_ACTUALIZAR_CONTEO = "http://$IP_ESP32_LCD/actualizar"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "Se requiere permiso de cámara", Toast.LENGTH_LONG).show()
    }

    // --- VARIABLES DE CÁMARA Y FLASH ---
    private var cameraControl: androidx.camera.core.Camera? = null
    private var isFlashOn = false
    private lateinit var btnFlash: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (OpenCVLoader.initDebug()) {
            Log.i("OpenCV", "OpenCV cargado exitosamente")
        } else {
            Toast.makeText(this, "Fallo al cargar OpenCV", Toast.LENGTH_LONG).show()
        }

        viewFinder = findViewById(R.id.viewFinder)
        imageOverlay = findViewById(R.id.imageOverlay)
        textConteo = findViewById(R.id.textConteo)
        textInstrucciones = findViewById(R.id.textInstrucciones)
        spinnerMedicamento = findViewById(R.id.spinnerMedicamento)

        layoutCaptura = findViewById(R.id.layoutCaptura)
        layoutValidacion = findViewById(R.id.layoutValidacion)
        layoutInventario = findViewById(R.id.layoutInventario)

        btnCapturarTelefono = findViewById(R.id.btnCapturarTelefono)
        btnCapturarESP32 = findViewById(R.id.btnCapturarESP32)
        btnProcesar = findViewById(R.id.btnProcesar)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnGuardarInventario = findViewById(R.id.btnGuardarInventario)

        cameraExecutor = Executors.newSingleThreadExecutor()
        actualizarSpinner()

        btnFlash = findViewById(R.id.btnFlash)

        // LÓGICA DEL FLASH
        btnFlash.setOnClickListener {
            cameraControl?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    isFlashOn = !isFlashOn
                    cam.cameraControl.enableTorch(isFlashOn)

                    if (isFlashOn) {
                        btnFlash.text = "🔦 Flash ON"
                        btnFlash.setBackgroundColor(Color.parseColor("#FFD60A"))
                        btnFlash.setTextColor(Color.BLACK)
                    } else {
                        btnFlash.text = "🔦 Flash OFF"
                        btnFlash.setBackgroundColor(Color.parseColor("#3A3A3C"))
                        btnFlash.setTextColor(Color.WHITE)
                    }
                } else {
                    Toast.makeText(this, "Este dispositivo no tiene flash", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // LÓGICA TÁCTIL (Selección + Varita Mágica)
        imageOverlay.setOnTouchListener { _, event ->
            if (estadoActual == EstadoApp.RESULTADO_PROCESADO && event.action == MotionEvent.ACTION_DOWN) {
                val matrix = imageOverlay.imageMatrix
                val inverse = android.graphics.Matrix()

                if (matrix.invert(inverse)) {
                    val touchPoint = floatArrayOf(event.x, event.y)
                    inverse.mapPoints(touchPoint)
                    val puntoTocado = Point(touchPoint[0].toDouble(), touchPoint[1].toDouble())

                    var contornoTocadoIndex = -1

                    for (i in 0 until listaContornosGlobal.size) {
                        val contorno2f = MatOfPoint2f(*listaContornosGlobal[i].toArray())
                        val distancia = Imgproc.pointPolygonTest(contorno2f, puntoTocado, false)
                        contorno2f.release()

                        if (distancia >= 0) {
                            contornoTocadoIndex = i
                            break
                        }
                    }

                    if (contornoTocadoIndex != -1) {
                        estadoSeleccionContornos[contornoTocadoIndex] = !estadoSeleccionContornos[contornoTocadoIndex]
                        redibujarContornosInteractivos()
                    } else {
                        forzarDeteccionEnPunto(puntoTocado)
                    }
                }
            }
            true
        }

        // EVENTOS DE BOTONES
        btnCapturarTelefono.setOnClickListener {
            if (estadoActual == EstadoApp.CAMARA_EN_VIVO && frameActualTelefono != null) {
                bitmapCapturadoCrudo = frameActualTelefono
                imageOverlay.setImageBitmap(bitmapCapturadoCrudo)
                imageOverlay.setBackgroundColor(Color.BLACK)
                modoActual = "Cámara Local"
                cambiarEstado(EstadoApp.PREVIEW_CRUDA)
            }
        }

        btnCapturarESP32.setOnClickListener {
            if (estadoActual == EstadoApp.CAMARA_EN_VIVO) {
                textInstrucciones.text = "Conectando con ESP32..."
                modoActual = "ESP32-CAM"
                thread {
                    try {
                        val url = URL(URL_ESP32_CAPTURA)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.connect()

                        if (connection.responseCode == 200) {
                            val bitmapDescargado = BitmapFactory.decodeStream(connection.inputStream)
                            runOnUiThread {
                                bitmapCapturadoCrudo = bitmapDescargado
                                imageOverlay.setImageBitmap(bitmapCapturadoCrudo)
                                imageOverlay.setBackgroundColor(Color.BLACK)
                                cambiarEstado(EstadoApp.PREVIEW_CRUDA)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread { Toast.makeText(this@MainActivity, "Error ESP32", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }

        btnProcesar.setOnClickListener {
            if (bitmapCapturadoCrudo != null) {
                textInstrucciones.text = "Toca las pastillas que faltaron o quita las que sobren"
                prepararContornosInteractivos(bitmapCapturadoCrudo!!)
                cambiarEstado(EstadoApp.RESULTADO_PROCESADO)
            }
        }

        btnCancelar.setOnClickListener {
            limpiarMemoriaOpenCV()
            imageOverlay.setImageBitmap(null)
            imageOverlay.setBackgroundColor(Color.TRANSPARENT)
            bitmapCapturadoCrudo = null
            ultimoBitmapProcesado = null
            cambiarEstado(EstadoApp.CAMARA_EN_VIVO)
        }

        btnGuardarInventario.setOnClickListener {
            if (estadoActual == EstadoApp.RESULTADO_PROCESADO && ultimoBitmapProcesado != null) {
                guardarImagenEnGaleria(ultimoBitmapProcesado!!, ultimoConteoDetectado, modoActual)

                val pos = spinnerMedicamento.selectedItemPosition
                if (pos >= 0 && pos < GestorBotiquin.inventario.size) {
                    val med = GestorBotiquin.inventario[pos]
                    med.cantidad += ultimoConteoDetectado
                    GestorBotiquin.guardarDatos(this)
                    Toast.makeText(this, "¡Guardado! ${med.nombre}: ${med.cantidad} pastillas.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "El inventario está vacío.", Toast.LENGTH_LONG).show()
                }
                btnCancelar.performClick()
            }
        }

        // Navegación Inferior
        findViewById<TextView>(R.id.navInicio).setOnClickListener {
            startActivity(Intent(this, InicioActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }
        findViewById<TextView>(R.id.navBotiquin).setOnClickListener {
            startActivity(Intent(this, BotiquinActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }

        cambiarEstado(EstadoApp.CAMARA_EN_VIVO)
        if (allPermissionsGranted()) startCamera() else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        actualizarSpinner()
    }

    private fun actualizarSpinner() {
        val nombresMedicamentos = GestorBotiquin.inventario.map { it.nombre }
        spinnerMedicamento.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nombresMedicamentos)
    }

    private fun cambiarEstado(nuevoEstado: EstadoApp) {
        estadoActual = nuevoEstado
        when(nuevoEstado) {
            EstadoApp.CAMARA_EN_VIVO -> {
                layoutCaptura.visibility = View.VISIBLE; layoutValidacion.visibility = View.GONE; layoutInventario.visibility = View.GONE
                textConteo.text = "-- Esperando captura --"
                textInstrucciones.text = "Enfoca las pastillas o conéctate al ESP32"
            }
            EstadoApp.PREVIEW_CRUDA -> {
                layoutCaptura.visibility = View.GONE; layoutValidacion.visibility = View.VISIBLE; layoutInventario.visibility = View.GONE
                btnProcesar.visibility = View.VISIBLE
                textConteo.text = "Imagen cruda ($modoActual)"
                textInstrucciones.text = "¿Validar y procesar esta imagen?"
            }
            EstadoApp.RESULTADO_PROCESADO -> {
                layoutValidacion.visibility = View.VISIBLE
                btnProcesar.visibility = View.GONE
                layoutInventario.visibility = View.VISIBLE
                textInstrucciones.text = "Toca una pastilla ignorada para detectarla."
            }
        }
    }

    // ALGORITMO OPENCV PREPARADOR
    private fun prepararContornosInteractivos(bitmapOriginal: Bitmap) {
        limpiarMemoriaOpenCV()

        matVisualizacionBase = Mat()
        Utils.bitmapToMat(bitmapOriginal, matVisualizacionBase)

        val matGris = Mat()
        val matBlur = Mat()
        val matBordes = Mat()
        val jerarquia = Mat()
        var kernel: Mat? = null

        try {
            Imgproc.cvtColor(matVisualizacionBase, matGris, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(matGris, matBlur, Size(9.0, 9.0), 0.0)
            Imgproc.Canny(matBlur, matBordes, 40.0, 120.0)

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.morphologyEx(matBordes, matBordes, Imgproc.MORPH_CLOSE, kernel)

            Imgproc.findContours(matBordes, listaContornosGlobal, jerarquia, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            estadoSeleccionContornos = BooleanArray(listaContornosGlobal.size)

            for (i in 0 until listaContornosGlobal.size) {
                if (Imgproc.contourArea(listaContornosGlobal[i]) > 800) {
                    estadoSeleccionContornos[i] = true
                }
            }
            redibujarContornosInteractivos()
        } catch (e: Exception) { e.printStackTrace() }
        finally { matGris.release(); matBlur.release(); matBordes.release(); jerarquia.release(); kernel?.release() }
    }

    // HERRAMIENTA "VARITA MÁGICA" (FloodFill)
    private fun forzarDeteccionEnPunto(punto: Point) {
        if (matVisualizacionBase == null) return

        val matGris = Mat()
        val mascaraOriginal = Mat()
        val mascaraRecortada = Mat()
        val jerarquia = Mat()
        val contornosNuevos = ArrayList<MatOfPoint>()

        try {
            Imgproc.cvtColor(matVisualizacionBase, matGris, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(matGris, matGris, Size(5.0, 5.0), 0.0)

            mascaraOriginal.create(matGris.rows() + 2, matGris.cols() + 2, CvType.CV_8U)
            mascaraOriginal.setTo(Scalar(0.0))

            val toleranciaAbajo = Scalar(25.0)
            val toleranciaArriba = Scalar(25.0)
            val flags = 4 or (255 shl 8) or Imgproc.FLOODFILL_MASK_ONLY

            Imgproc.floodFill(matGris, mascaraOriginal, punto, Scalar(255.0), Rect(), toleranciaAbajo, toleranciaArriba, flags)

            mascaraRecortada.create(matGris.rows(), matGris.cols(), CvType.CV_8U)
            mascaraOriginal.submat(1, mascaraOriginal.rows() - 1, 1, mascaraOriginal.cols() - 1).copyTo(mascaraRecortada)

            Imgproc.findContours(mascaraRecortada, contornosNuevos, jerarquia, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            if (contornosNuevos.isNotEmpty()) {
                val mejorContorno = contornosNuevos.maxByOrNull { Imgproc.contourArea(it) }

                if (mejorContorno != null && Imgproc.contourArea(mejorContorno) > 200) {
                    listaContornosGlobal.add(mejorContorno)

                    val nuevoEstado = BooleanArray(estadoSeleccionContornos.size + 1)
                    System.arraycopy(estadoSeleccionContornos, 0, nuevoEstado, 0, estadoSeleccionContornos.size)
                    nuevoEstado[nuevoEstado.size - 1] = true

                    estadoSeleccionContornos = nuevoEstado
                    redibujarContornosInteractivos()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            matGris.release()
            mascaraOriginal.release()
            mascaraRecortada.release()
            jerarquia.release()

            for (i in 0 until contornosNuevos.size) {
                if (contornosNuevos[i] != listaContornosGlobal.lastOrNull()) {
                    contornosNuevos[i].release()
                }
            }
        }
    }

    // COMUNICACIÓN SILENCIOSA CON ESP32
    private fun enviarConteoAlESP32(conteo: Int) {
        thread {
            try {
                val url = URL(URL_ESP32_ACTUALIZAR_CONTEO)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "cantidad=$conteo"
                val outputBytes = postData.toByteArray(Charsets.UTF_8)

                connection.outputStream.use { os ->
                    os.write(outputBytes)
                }

                val responseCode = connection.responseCode
                Log.d("ESP32_Sync", "Conteo enviado. Respuesta: $responseCode")
                connection.disconnect()

            } catch (e: Exception) {
                // Falla silenciosamente. No interrumpe la UI del usuario.
                Log.e("ESP32_Sync", "ESP32 no disponible. No se pudo enviar el conteo.")
            }
        }
    }

    // REDIBUJO RÁPIDO
    private fun redibujarContornosInteractivos() {
        if (matVisualizacionBase == null) return

        val matDibujo = matVisualizacionBase!!.clone()
        var conteo = 0

        val colorVerde = Scalar(0.0, 255.0, 0.0)
        val colorRojoOscuro = Scalar(255.0, 0.0, 0.0, 100.0)
        val grosorLinea = 6

        for (i in 0 until listaContornosGlobal.size) {
            if (Imgproc.contourArea(listaContornosGlobal[i]) < 100) continue

            if (estadoSeleccionContornos[i]) {
                Imgproc.drawContours(matDibujo, listaContornosGlobal, i, colorVerde, grosorLinea)
                conteo++
            } else {
                Imgproc.drawContours(matDibujo, listaContornosGlobal, i, colorRojoOscuro, 3)
            }
        }

        val bitmapVisualizado = Bitmap.createBitmap(matDibujo.cols(), matDibujo.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(matDibujo, bitmapVisualizado)
        matDibujo.release()

        ultimoBitmapProcesado = bitmapVisualizado
        ultimoConteoDetectado = conteo

        // Llama a nuestra función de red en segundo plano cada vez que el conteo cambia
        enviarConteoAlESP32(conteo)

        runOnUiThread {
            imageOverlay.setImageBitmap(bitmapVisualizado)
            textConteo.text = "Detectadas: $conteo"
        }
    }

    private fun limpiarMemoriaOpenCV() {
        matVisualizacionBase?.release()
        matVisualizacionBase = null
        for (c in listaContornosGlobal) { c.release() }
        listaContornosGlobal.clear()
        estadoSeleccionContornos = BooleanArray(0)
    }

    private fun guardarImagenEnGaleria(bitmap: Bitmap, conteo: Int, origen: String) {
        val bitmapParaDibujar = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmapParaDibujar)
        val paint = Paint().apply { color = Color.YELLOW; textSize = 60f; isAntiAlias = true; setShadowLayer(5f, 3f, 3f, Color.BLACK) }
        canvas.drawText("Píldoras contadas: $conteo", 50f, 100f, paint)
        canvas.drawText("Origen: $origen", 50f, 180f, paint)
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Botiquin_${origen}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it).use { os -> os?.let { bitmapParaDibujar.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear(); contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                it.setAnalyzer(cameraExecutor, PillAnalyzer { bitmap -> frameActualTelefono = bitmap })
            }
            try {
                cameraProvider.unbindAll()
                cameraControl = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
            } catch (exc: Exception) { Log.e("CameraX", "Fallo al vincular la cámara", exc) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        limpiarMemoriaOpenCV()
        cameraExecutor.shutdown()
    }
}

class PillAnalyzer(private val listener: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val bitmap = image.toBitmap()
        listener(bitmap)
        image.close()
    }
}