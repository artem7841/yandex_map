package com.artem.mapyandex

import com.yandex.mapkit.geometry.Point

class DataClass {
    val apoKey = "c181997f-ad9d-4251-bd60-dadfc4bcdbbc"
    val cameraPoints = listOf(
        Point(37.42259713926415, -122.0840224511963),
        Point(37.42237432178187, -122.08558021116042),
        Point(56.792995, 60.648634),
        Point(56.791660, 60.651930),
        Point(56.792593, 60.643717),
        Point(56.798976, 60.649182),
        Point(56.815002, 60.660315),
        Point(56.838212, 60.603476),

        Point(56.840232, 60.621561),
        Point(56.842722, 60.642937),
        Point(56.843045, 60.645993),
    )

    // Радиус обнаружения камеры (в метрах)
    val detectionRadius = 5;


}