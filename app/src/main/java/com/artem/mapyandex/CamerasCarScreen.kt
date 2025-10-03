// CamerasCarScreen.kt
package com.artem.mapyandex

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import kotlinx.coroutines.launch

class CamerasCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val cameraList = ListTemplate.Builder().apply {

            setHeaderAction(Action.BACK)

            setSingleList(
                ItemList.Builder().apply {
                    // Добавляем информацию о ближайших камерах
                    addItem(
                        Row.Builder()
                            .setTitle("📷 Ближайшая камера")
                            .addText("100 м впереди")
                            .build()
                    )

                    addItem(
                        Row.Builder()
                            .setTitle("📷 Следующая камера")
                            .addText("500 м впереди")
                            .build()
                    )

                    addItem(
                        Row.Builder()
                            .setTitle("ℹ️ Всего камер на маршруте")
                            .addText("3 камеры")
                            .build()
                    )
                }.build()
            )

            // Действия
            setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Обновить")
                            .setOnClickListener {
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )

        }.build()

        return cameraList
    }
}