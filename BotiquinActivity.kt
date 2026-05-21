package com.example.contadordepills

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// 1. EL MODELO DE DATOS Y GESTOR GLOBAL
data class MedicamentoItem(val nombre: String, var cantidad: Int)

object GestorBotiquin {
    var inventario = mutableListOf<MedicamentoItem>()

    fun guardarDatos(context: Context) {
        val prefs = context.getSharedPreferences("MiAppMedicina", Context.MODE_PRIVATE)
        val json = Gson().toJson(inventario)
        prefs.edit().putString("datos_botiquin", json).apply()
    }

    fun cargarDatos(context: Context) {
        val prefs = context.getSharedPreferences("MiAppMedicina", Context.MODE_PRIVATE)
        val json = prefs.getString("datos_botiquin", null)
        if (json != null) {
            val tipo = object : TypeToken<MutableList<MedicamentoItem>>() {}.type
            inventario = Gson().fromJson(json, tipo)
        } else {
            inventario = mutableListOf() // Si es la primera vez que abre la app
        }
    }
}

class BotiquinActivity : AppCompatActivity() {

    private lateinit var inputNombre: EditText
    private lateinit var inputCantidad: EditText
    private lateinit var btnGuardar: Button
    private lateinit var contenedorExistencias: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_botiquin)

        GestorBotiquin.cargarDatos(this)

        // Vincular vistas
        inputNombre = findViewById(R.id.inputNombreMedicamento)
        inputCantidad = findViewById(R.id.inputCantidadMedicamento)
        btnGuardar = findViewById(R.id.btnGuardarMedicamento)
        contenedorExistencias = findViewById(R.id.contenedorExistencias)

        actualizarPantalla()

        // Lógica para guardar un nuevo medicamento por primera vez
        btnGuardar.setOnClickListener {
            val nombre = inputNombre.text.toString().trim()
            val cantidadStr = inputCantidad.text.toString().trim()

            if (nombre.isNotEmpty() && cantidadStr.isNotEmpty()) {
                val cantidad = cantidadStr.toInt()

                // Verificar si ya existe para no duplicarlo, sino sumarlo
                val existente = GestorBotiquin.inventario.find { it.nombre.equals(nombre, ignoreCase = true) }
                if (existente != null) {
                    existente.cantidad += cantidad
                    Toast.makeText(this, "Medicamento existente. Se sumaron las existencias.", Toast.LENGTH_SHORT).show()
                } else {
                    GestorBotiquin.inventario.add(MedicamentoItem(nombre, cantidad))
                    Toast.makeText(this, "Nuevo medicamento añadido", Toast.LENGTH_SHORT).show()
                }

                GestorBotiquin.guardarDatos(this)

                // Limpiar campos y actualizar UI
                inputNombre.text.clear()
                inputCantidad.text.clear()
                actualizarPantalla()
            } else {
                Toast.makeText(this, "Por favor, llena ambos campos", Toast.LENGTH_SHORT).show()
            }
        }

        // Navegación
        findViewById<TextView>(R.id.navInicio).setOnClickListener {
            val intent = Intent(this, InicioActivity::class.java)
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
        GestorBotiquin.cargarDatos(this)
        actualizarPantalla() // Refrescar si venimos del escáner donde sumamos pastillas
    }

    private fun actualizarPantalla() {
        contenedorExistencias.removeAllViews()

        for (med in GestorBotiquin.inventario) {

            // Crear la tarjeta blanca
            val card = CardView(this).apply {
                radius = 16f
                cardElevation = 2f
                setCardBackgroundColor(Color.WHITE)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 24)
                layoutParams = params
            }

            // Contenedor horizontal dentro de la tarjeta
            val layoutHorizontal = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 40, 40, 40)
                gravity = Gravity.CENTER_VERTICAL
            }

            // Textos (Nombre y Cantidad)
            val layoutTextos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val txtNombre = TextView(this).apply {
                text = med.nombre
                textSize = 18f
                setTextColor(Color.parseColor("#1C1C1E"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val txtCantidad = TextView(this).apply {
                text = "Quedan: ${med.cantidad} pastillas"
                textSize = 14f
                setTextColor(Color.parseColor("#8E8E93"))
                if (med.cantidad < 15) {
                    text = "Quedan: ${med.cantidad} pastillas (¡Por agotarse!)"
                    setTextColor(Color.parseColor("#FF3B30")) // Rojo alerta
                }
            }

            // --- NUEVO: Botón de Sumar Existencias (➕) ---
            val btnSumar = TextView(this).apply {
                text = "➕"
                textSize = 20f
                setTextColor(Color.parseColor("#34C759")) // Verde
                setPadding(20, 20, 20, 20)
            }

            // Lógica para abrir el Pop-up y sumar pastillas
            btnSumar.setOnClickListener {
                // Crear un campo de texto para el diálogo
                val input = EditText(this@BotiquinActivity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "Cantidad a sumar"
                }

                AlertDialog.Builder(this@BotiquinActivity)
                    .setTitle("Reabastecer ${med.nombre}")
                    .setMessage("¿Cuántas pastillas deseas agregar a las existencias actuales (${med.cantidad})?")
                    .setView(input) // Metemos el campo de texto en la ventana
                    .setPositiveButton("Sumar") { _, _ ->
                        val cantidadExtraStr = input.text.toString()
                        if (cantidadExtraStr.isNotEmpty()) {
                            val cantidadExtra = cantidadExtraStr.toInt()
                            med.cantidad += cantidadExtra // Sumamos matemáticamente

                            GestorBotiquin.guardarDatos(this@BotiquinActivity) // Guardamos en memoria
                            actualizarPantalla() // Redibujamos la pantalla

                            Toast.makeText(this@BotiquinActivity, "Se agregaron $cantidadExtra pastillas", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            // Botón de eliminar (Una '✖')
            val btnEliminar = TextView(this).apply {
                text = "✖"
                textSize = 18f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(20, 20, 0, 20)
            }

            // Programar el diálogo de confirmación para eliminar
            btnEliminar.setOnClickListener {
                AlertDialog.Builder(this@BotiquinActivity)
                    .setTitle("Eliminar Medicamento")
                    .setMessage("¿Estás seguro de que deseas eliminar '${med.nombre}' del inventario? Esta acción también lo quitará de las opciones de alarmas.")
                    .setPositiveButton("Eliminar") { _, _ ->
                        GestorBotiquin.inventario.remove(med)
                        GestorBotiquin.guardarDatos(this@BotiquinActivity)
                        actualizarPantalla()
                        Toast.makeText(this@BotiquinActivity, "Eliminado", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            // Ensamblar las vistas (¡El orden importa aquí!)
            layoutTextos.addView(txtNombre)
            layoutTextos.addView(txtCantidad)
            layoutHorizontal.addView(layoutTextos)
            layoutHorizontal.addView(btnSumar) // Agregamos el botón de suma primero
            layoutHorizontal.addView(btnEliminar) // Luego el de eliminar
            card.addView(layoutHorizontal)

            contenedorExistencias.addView(card)
        }
    }
}