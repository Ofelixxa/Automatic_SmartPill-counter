package com.example.contadordepills

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

// 1. EL MODELO DE ALARMA
data class Alarma(
    val medicamento: String,
    val hora: Int,
    val minuto: Int,
    val dosis: Int
) : Comparable<Alarma> {
    override fun compareTo(other: Alarma): Int {
        if (this.hora != other.hora) return this.hora.compareTo(other.hora)
        return this.minuto.compareTo(other.minuto)
    }
    fun getHoraFormateada(): String = String.format("%02d:%02d", hora, minuto)
}

// 2. GESTOR DE ALARMAS GLOBAL (Singleton)
object GestorAlarmas {
    var listaAlarmas = mutableListOf<Alarma>()

    fun guardarDatos(context: Context) {
        val prefs = context.getSharedPreferences("MiAppMedicina", Context.MODE_PRIVATE)
        val json = Gson().toJson(listaAlarmas)
        prefs.edit().putString("datos_alarmas", json).apply()
    }

    fun cargarDatos(context: Context) {
        val prefs = context.getSharedPreferences("MiAppMedicina", Context.MODE_PRIVATE)
        val json = prefs.getString("datos_alarmas", null)
        if (json != null) {
            val tipo = object : TypeToken<MutableList<Alarma>>() {}.type
            listaAlarmas = Gson().fromJson(json, tipo)
        } else {
            listaAlarmas = mutableListOf()
        }
    }
}

class InicioActivity : AppCompatActivity() {

    private lateinit var spinnerMedicamentoAlarma: Spinner
    private lateinit var btnSeleccionarHora: Button
    private lateinit var btnGuardarAlarma: Button
    private lateinit var inputDosisAlarma: EditText // NUEVO CAMPO

    // Referencias a los textos dinámicos del layout
    private lateinit var txtProximaNombre: TextView
    private lateinit var txtProximaHora: TextView
    private lateinit var txtProximaDosis: TextView
    private lateinit var contenedorHorario: LinearLayout

    private var horaSeleccionada: Int = 0
    private var minutoSeleccionado: Int = 0
    private var horaConfigurada: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inicio)

        // Permiso de notificaciones para Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Cargar datos guardados
        GestorBotiquin.cargarDatos(this)
        GestorAlarmas.cargarDatos(this)

        // Vincular vistas
        spinnerMedicamentoAlarma = findViewById(R.id.spinnerMedicamentoAlarma)
        btnSeleccionarHora = findViewById(R.id.btnSeleccionarHora)
        btnGuardarAlarma = findViewById(R.id.btnGuardarAlarma)
        inputDosisAlarma = findViewById(R.id.inputDosisAlarma)

        txtProximaNombre = findViewById(R.id.txtProximaNombre)
        txtProximaHora = findViewById(R.id.txtProximaHora)
        txtProximaDosis = findViewById(R.id.txtProximaDosis)
        contenedorHorario = findViewById(R.id.contenedorHorario)

        // Refrescar la pantalla al iniciar
        actualizarPantalla()

        btnSeleccionarHora.setOnClickListener {
            mostrarSelectorDeHora()
        }

        // GUARDAR NUEVA ALARMA
        btnGuardarAlarma.setOnClickListener {
            val dosisStr = inputDosisAlarma.text.toString()

            if (horaConfigurada && dosisStr.isNotEmpty()) {
                if (spinnerMedicamentoAlarma.selectedItem != null) {
                    val medicamento = spinnerMedicamentoAlarma.selectedItem.toString()
                    val dosis = dosisStr.toInt()

                    val nuevaAlarma = Alarma(medicamento, horaSeleccionada, minutoSeleccionado, dosis)
                    GestorAlarmas.listaAlarmas.add(nuevaAlarma)

                    GestorAlarmas.listaAlarmas.sort()
                    GestorAlarmas.guardarDatos(this)

                    programarAlarmaDelSistema(nuevaAlarma)
                    actualizarPantalla()

                    Toast.makeText(this, "Alarma guardada", Toast.LENGTH_SHORT).show()

                    // Resetear formulario
                    horaConfigurada = false
                    btnSeleccionarHora.text = "Seleccionar Hora (00:00)"
                    inputDosisAlarma.text.clear()
                } else {
                    Toast.makeText(this, "Añade un medicamento en el Botiquín primero", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Selecciona la hora y escribe la dosis", Toast.LENGTH_SHORT).show()
            }
        }

        // NAVEGACIÓN INFERIOR
        findViewById<TextView>(R.id.navInicio).setOnClickListener { }

        findViewById<TextView>(R.id.navBotiquin).setOnClickListener {
            val intent = Intent(this, BotiquinActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }

        findViewById<TextView>(R.id.navEscanear).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Actualizar el Spinner por si se agregaron medicamentos en el Botiquín
        val nombresMedicamentos = GestorBotiquin.inventario.map { it.nombre }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nombresMedicamentos)
        spinnerMedicamentoAlarma.adapter = adapter

        actualizarPantalla()
    }

    private fun mostrarSelectorDeHora() {
        val calendario = Calendar.getInstance()
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(calendario.get(Calendar.HOUR_OF_DAY))
            .setMinute(calendario.get(Calendar.MINUTE))
            .setTitleText("Selecciona la hora de la toma")
            .build()

        timePicker.addOnPositiveButtonClickListener {
            horaSeleccionada = timePicker.hour
            minutoSeleccionado = timePicker.minute
            horaConfigurada = true
            btnSeleccionarHora.text = "Hora: ${String.format("%02d:%02d", horaSeleccionada, minutoSeleccionado)}"
        }
        timePicker.show(supportFragmentManager, "TIME_PICKER")
    }

    // DIBUJAR PANTALLA
    private fun actualizarPantalla() {
        if (GestorAlarmas.listaAlarmas.isEmpty()) {
            txtProximaNombre.text = "Sin alarmas"
            txtProximaHora.text = "--:--"
            txtProximaDosis.text = "Programa tu medicamento abajo"
            contenedorHorario.removeAllViews()
            return
        }

        GestorAlarmas.listaAlarmas.sort()

        val ahora = Calendar.getInstance()
        val horaActual = ahora.get(Calendar.HOUR_OF_DAY)
        val minutoActual = ahora.get(Calendar.MINUTE)

        val proxima = GestorAlarmas.listaAlarmas.find {
            it.hora > horaActual || (it.hora == horaActual && it.minuto > minutoActual)
        } ?: GestorAlarmas.listaAlarmas.first()

        txtProximaNombre.text = proxima.medicamento
        txtProximaHora.text = proxima.getHoraFormateada()
        txtProximaDosis.text = "Dosis: ${proxima.dosis} pastilla(s)"

        contenedorHorario.removeAllViews()
        for (alarma in GestorAlarmas.listaAlarmas) {
            val card = CardView(this).apply {
                radius = 16f
                cardElevation = 4f
                val params = LinearLayout.LayoutParams(-1, -2)
                params.setMargins(0, 0, 0, 20)
                layoutParams = params
            }

            val layoutInterno = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(30, 30, 30, 30)
                gravity = Gravity.CENTER_VERTICAL
            }

            val info = TextView(this).apply {
                text = "${alarma.getHoraFormateada()} - ${alarma.medicamento}\nDosis: ${alarma.dosis}"
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setTextColor(Color.parseColor("#1C1C1E"))
                textSize = 16f
            }

            val btnTomar = Button(this).apply {
                text = "Tomar"
                setBackgroundColor(Color.parseColor("#34C759"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    restarDelInventario(alarma.medicamento, alarma.dosis)
                }
            }

            val btnEliminar = Button(this).apply {
                text = "X"
                setBackgroundColor(Color.parseColor("#FF3B30"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 0, 0, 0) }
                setOnClickListener {
                    cancelarAlarmaEnSistema(alarma)
                    GestorAlarmas.listaAlarmas.remove(alarma)
                    GestorAlarmas.guardarDatos(this@InicioActivity)
                    actualizarPantalla()
                }
            }

            layoutInterno.addView(info)
            layoutInterno.addView(btnTomar)
            layoutInterno.addView(btnEliminar)
            card.addView(layoutInterno)
            contenedorHorario.addView(card)
        }
    }

    // DESCONTAR DEL INVENTARIO Y GUARDAR
    private fun restarDelInventario(nombreMed: String, cantidad: Int) {
        val medicamento = GestorBotiquin.inventario.find { it.nombre == nombreMed }
        if (medicamento != null) {
            if (medicamento.cantidad >= cantidad) {
                medicamento.cantidad -= cantidad
                GestorBotiquin.guardarDatos(this)
                Toast.makeText(this, "Dosis registrada. Quedan ${medicamento.cantidad} pastillas.", Toast.LENGTH_SHORT).show()
                actualizarPantalla()
            } else {
                Toast.makeText(this, "¡Inventario insuficiente! Ve al escáner para reabastecer.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // PROGRAMAR EN ANDROID
    private fun programarAlarmaDelSistema(alarma: Alarma) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmaReceiver::class.java).apply {
            putExtra("MEDICAMENTO", alarma.medicamento)
            putExtra("DOSIS", alarma.dosis)
        }
        val requestCode = alarma.hora * 100 + alarma.minuto
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarma.hora)
            set(Calendar.MINUTE, alarma.minuto)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Faltan permisos de alarma en tu dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    // ELIMINAR DEL SISTEMA DE ANDROID
    private fun cancelarAlarmaEnSistema(alarma: Alarma) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmaReceiver::class.java)
        val requestCode = alarma.hora * 100 + alarma.minuto
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }
}