// MainCarScreen.kt
package com.artem.mapyandex

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    private var currentSpeed = 0
    private var nearestCamera = "Загрузка..."
    private var currentLocation = "Координаты не получены"
    private var cameraAlert = ""
    private var lastUpdateTime = "Никогда"

    override fun onGetTemplate(): Template {
        return PaneTemplate.Builder(createPane())
            .setTitle("МОЙ НАВИГАТОР")
            .setHeaderAction(Action.BACK)
            .setActionStrip(createActionStrip())
            .build()
    }

    private fun createPane(): Pane {
        val paneBuilder = Pane.Builder()

        // Секция отладки - покажем статус данных
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("🔧 СТАТУС ДАННЫХ")
                .addText(lastUpdateTime)
                .build()
        )

        // Секция скорости
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("🚗 ТЕКУЩАЯ СКОРОСТЬ")
                .addText("$currentSpeed км/ч")
                .build()
        )

        // Предупреждение о камере
        if (cameraAlert.isNotEmpty()) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("⚠️ ВНИМАНИЕ!")
                    .addText(cameraAlert)
                    .build()
            )
        }

        // Секция камер
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("📷 БЛИЖАЙШАЯ КАМЕРА")
                .addText(nearestCamera)
                .build()
        )

        // Секция местоположения
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("📍 МЕСТОПОЛОЖЕНИЕ")
                .addText(currentLocation)
                .build()
        )

        return paneBuilder.build()
    }

    private fun createActionStrip(): ActionStrip {
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("🔄 ОБНОВИТЬ")
                    .setOnClickListener { updateData() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("📋 КАМЕРЫ")
                    .setOnClickListener {
                        screenManager.push(CamerasCarScreen(carContext))
                    }
                    .build()
            )
            .build()
    }

    private fun updateData() {
        lifecycleScope.launch {
            loadDataFromMainApp()
            invalidate()
        }
    }

    private fun loadDataFromMainApp() {
        val sharedPref = carContext.getSharedPreferences("car_data", CarContext.MODE_PRIVATE)

        // Получаем координаты
        val lat = sharedPref.getFloat("current_lat", 0f)
        val lon = sharedPref.getFloat("current_lon", 0f)

        currentSpeed = sharedPref.getInt("current_speed", 0)
        cameraAlert = sharedPref.getString("camera_alert", "") ?: ""

        // Форматируем координаты
        currentLocation = if (lat != 0f && lon != 0f) {
            "Ш:${"%.6f".format(lat)}\nД:${"%.6f".format(lon)}"
        } else {
            "❌ Данные не получены"
        }

        nearestCamera = sharedPref.getString("nearest_camera", "⏳ Расчет...") ?: "⏳ Расчет..."

        // Время последнего обновления
        lastUpdateTime = "Обновлено: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
    }

//    override fun onResume() {
//        super.onResume()
//        updateData()
//    }
}