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

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder().apply {
            // Секция местоположения
            addRow(
                Row.Builder()
                    .setTitle("📍 МЕСТОПОЛОЖЕНИЕ")
                    .addText(currentLocation)
                    .build()
            )

            // Секция скорости
            addRow(
                Row.Builder()
                    .setTitle("🚗 СКОРОСТЬ")
                    .addText("$currentSpeed км/ч")
                    .build()
            )

            // Секция камер
            addRow(
                Row.Builder()
                    .setTitle("📷 БЛИЖАЙШАЯ КАМЕРА")
                    .addText(nearestCamera)
                    .build()
            )

            // Действия
            addAction(
                Action.Builder()
                    .setTitle("🔄 ОБНОВИТЬ")
                    .setOnClickListener {
                        updateData()
                    }
                    .build()
            )

            addAction(
                Action.Builder()
                    .setTitle("📋 ВСЕ КАМЕРЫ")
                    .setOnClickListener {
                        screenManager.push(CamerasCarScreen(carContext))
                    }
                    .build()
            )
        }.build()

        return PaneTemplate.Builder(pane)
            .setTitle("МОЙ НАВИГАТОР")
            .setHeaderAction(Action.BACK)
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

        // Форматируем координаты
        currentLocation = if (lat != 0f && lon != 0f) {
            String.format("Ш: %.6f\nД: %.6f", lat, lon)
        } else {
            "Данные не получены"
        }

        // Получаем информацию о камерах
        nearestCamera = sharedPref.getString("nearest_camera", "Расчет...") ?: "Расчет..."
    }

//    override fun onResume() {
//        super.onResume()
//        updateData()
//    }
}