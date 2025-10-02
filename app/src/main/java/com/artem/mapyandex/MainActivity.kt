package com.artem.mapyandex

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.location.*
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.runtime.image.ImageProvider
import java.util.Locale
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.RotationType

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var speedTextView: TextView
    private var mapObjects: MapObjectCollection? = null
    private var userLocationPlacemark: PlacemarkMapObject? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    // Для определения направления движения
    private var previousLocation: Location? = null

    // Массив точек камер
    private val cameraPoints = listOf(
        Point(37.42259713926415, -122.0840224511963),
        Point(37.42237432178187, -122.08558021116042),
        Point(56.792995, 60.648634),
        Point(56.791660, 60.651930),
        Point(56.792593, 60.643717),
    )

    // Радиус обнаружения камеры (в метрах)
    private val detectionRadius = 50.0

    // Флаг для отслеживания, была ли уже зафиксирована скорость на этой камере
    private val processedCameras = mutableSetOf<Point>()

    // TextView для отображения информации о камере
    private lateinit var cameraAlertTextView: TextView

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLocationUpdates()
        } else {
            setDefaultLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapKitFactory.setApiKey("c181997f-ad9d-4251-bd60-dadfc4bcdbbc")
        MapKitFactory.initialize(this)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapview)
        speedTextView = findViewById(R.id.speedTextView)
        // Добавь этот TextView в layout для отображения предупреждений о камерах
        cameraAlertTextView = findViewById(R.id.cameraAlertTextView)

        mapObjects = mapView.mapWindow.map.mapObjects.addCollection()

        // Добавляем метки камер на карту
        val cameraImageProvider = ImageProvider.fromResource(this, R.drawable.camera_point)

        cameraPoints.forEach { point ->
            mapObjects?.addPlacemark().apply {
                this?.geometry = point
                this?.setIcon(cameraImageProvider)
                // Настройка масштаба иконки
                this?.setIconStyle(IconStyle().apply {
                    scale = 0.1f
                    anchor = PointF(0.5f, 1.0f)
                })
            }
        }

        // Настраиваем отображение скорости и предупреждений
        setupSpeedView()
        setupCameraAlertView()

        checkLocationPermission()
    }

    private fun setupSpeedView() {
        speedTextView.apply {
            setBackgroundResource(android.R.drawable.btn_default)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            textSize = 20f
            setPadding(40, 20, 40, 20)
            text = "0 км/ч"
        }
    }

    private fun setupCameraAlertView() {
        cameraAlertTextView.apply {
            setBackgroundResource(android.R.drawable.btn_default)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            textSize = 16f
            setPadding(20, 10, 20, 10)
            text = ""
            visibility = android.view.View.GONE
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startLocationUpdates() {
        locationManager = MapKitFactory.getInstance().createLocationManager()

        locationListener = object : LocationListener {
            override fun onLocationUpdated(location: Location) {
                Log.d("LocationDebug", "Location updated: ${location.position}")
                updateUserLocation(location)
                updateSpeed(location)
                checkCameraProximity(location) // Проверяем приближение к камерам
            }

            override fun onLocationStatusUpdated(status: LocationStatus) {
                Log.d("LocationDebug", "Location status: $status")
                when (status) {
                    LocationStatus.AVAILABLE -> {}
                    LocationStatus.NOT_AVAILABLE -> setDefaultLocation()
                    LocationStatus.RESET -> {}
                }
            }
        }

        val subscriptionSettings = SubscriptionSettings(
            UseInBackground.DISALLOW,
            Purpose.AUTOMOTIVE_NAVIGATION
        )

        locationListener?.let { listener ->
            locationManager?.subscribeForLocationUpdates(subscriptionSettings, listener)
        }
    }

    private fun checkCameraProximity(currentLocation: Location) {
        runOnUiThread {
            try {
                val userPoint = currentLocation.position
                var nearestCamera: Point? = null
                var minDistance = Double.MAX_VALUE

                // Ищем ближайшую камеру
                for (cameraPoint in cameraPoints) {
                    val distance = calculateDistance(userPoint, cameraPoint)
                    if (distance < minDistance) {
                        minDistance = distance
                        nearestCamera = cameraPoint
                    }
                }

                // Если находимся в радиусе обнаружения камеры
                if (nearestCamera != null && minDistance <= detectionRadius) {
                    // Проверяем, не обрабатывали ли мы уже эту камеру
                    if (!processedCameras.contains(nearestCamera)) {
                        // Фиксируем скорость
                        val speedKmh = getCurrentSpeedKmh(currentLocation)
                        showCameraAlert(speedKmh, minDistance)

                        // Добавляем камеру в обработанные
                        processedCameras.add(nearestCamera)

                        Log.d("CameraDebug", "Camera detected! Speed: $speedKmh km/h, Distance: $minDistance m")
                    }
                } else {
                    // Скрываем предупреждение, если вышли из зоны камеры
                    if (minDistance > detectionRadius * 1.5) {
                        hideCameraAlert()
                    }
                }

                // Обновляем информацию о расстоянии до камеры
                updateCameraDistanceInfo(minDistance)

            } catch (e: Exception) {
                Log.e("CameraDebug", "Error checking camera proximity: ${e.message}")
            }
        }
    }

    private fun getCurrentSpeedKmh(location: Location): Double {
        return when {
            location.speed != null -> {
                // Конвертируем м/с в км/ч
                location.speed!! * 3.6
            }
            else -> {
                // Если скорость недоступна, вычисляем по перемещению
                calculateSpeedFromMovement(location)
            }
        }
    }

    private fun showCameraAlert(speedKmh: Double, distance: Double) {
        cameraAlertTextView.visibility = android.view.View.VISIBLE
        val speedText = String.format(Locale.getDefault(), "%.0f", speedKmh)
        val distanceText = String.format(Locale.getDefault(), "%.0f", distance)

        cameraAlertTextView.text = "КАМЕРА! Скорость: $speedText км/ч"

        // Меняем цвет в зависимости от скорости
        when {
            speedKmh > 100 -> {
                cameraAlertTextView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            speedKmh > 80 -> {
                cameraAlertTextView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            }
            else -> {
                cameraAlertTextView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
        }
    }

    private fun hideCameraAlert() {
        cameraAlertTextView.visibility = android.view.View.GONE
    }

    private fun updateCameraDistanceInfo(distance: Double) {
        // Можно добавить дополнительную информацию о расстоянии до ближайшей камеры
        if (distance < detectionRadius * 3) {
            val distanceText = String.format(Locale.getDefault(), "%.0f", distance)
            // Можно обновлять другой TextView или добавлять информацию в существующий
            Log.d("CameraDebug", "Distance to nearest camera: $distance m")
        }
    }

    private fun updateUserLocation(location: Location) {
        runOnUiThread {
            try {
                if (mapObjects == null) {
                    mapObjects = mapView.mapWindow.map.mapObjects.addCollection()
                }

                // Удаляем предыдущую метку
                userLocationPlacemark?.let {
                    mapObjects?.remove(it)
                }

                // Создаем новую метку для автомобиля
                userLocationPlacemark = mapObjects?.addPlacemark(location.position)
                userLocationPlacemark?.apply {
                    isDraggable = false
                    setIcon(ImageProvider.fromResource(this@MainActivity, R.drawable.icon_auto))
                    setIconStyle(IconStyle().apply {
                        anchor = PointF(0.5f, 1.0f) // Центр иконки
                        scale = 0.1f // Масштаб
                        rotationType = RotationType.NO_ROTATION // Поворот по направлению
                        zIndex = 100.0f // Поверх других объектов
                    })
                }

                // Вычисляем направление движения
                val azimuth = calculateAzimuth(location)

                // Создаем позицию камеры как в навигаторе
                val cameraPosition = CameraPosition(
                    location.position, // позиция автомобиля
                    17.0f,            // zoom (приближение)
                    azimuth,          // азимут (направление камеры)
                    60.0f             // наклон камеры (tilt) - как в навигаторах
                )

                // Анимированное перемещение камеры
                mapView.mapWindow.map.move(
                    cameraPosition,
                    com.yandex.mapkit.Animation(com.yandex.mapkit.Animation.Type.SMOOTH, 1.0f),
                    null
                )

                previousLocation = location

                Log.d("LocationDebug", "Camera positioned: azimuth=$azimuth, tilt=60.0")

            } catch (e: Exception) {
                Log.e("LocationDebug", "Error updating car location: ${e.message}")
            }
        }
    }

    private fun updateSpeed(location: Location) {
        runOnUiThread {
            try {
                val speedKmh = getCurrentSpeedKmh(location)

                // Форматируем скорость
                val speedText = if (speedKmh > 0) {
                    String.format(Locale.getDefault(), "%.0f км/ч", speedKmh)
                } else {
                    "0 км/ч"
                }

                speedTextView.text = speedText

                // Меняем цвет в зависимости от скорости
                when {
                    speedKmh > 100 -> {
                        speedTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    }
                    speedKmh > 60 -> {
                        speedTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                    }
                    else -> {
                        speedTextView.setTextColor(ContextCompat.getColor(this, android.R.color.black))
                    }
                }

                Log.d("SpeedDebug", "Speed: $speedKmh km/h")

            } catch (e: Exception) {
                Log.e("SpeedDebug", "Error updating speed: ${e.message}")
            }
        }
    }

    private fun calculateSpeedFromMovement(currentLocation: Location): Double {
        previousLocation?.let { prev ->
            val timeDiff = (currentLocation.absoluteTimestamp - prev.absoluteTimestamp) / 1000.0 // в секундах
            if (timeDiff > 0) {
                val distance = calculateDistance(prev.position, currentLocation.position)
                val speedMs = distance / timeDiff // м/с
                return speedMs * 3.6 // км/ч
            }
        }
        return 0.0
    }

    private fun calculateAzimuth(currentLocation: Location): Float {
        // Если есть направление в данных локации - используем его
        if (currentLocation.heading != null) {
            return currentLocation.heading!!.toFloat()
        }

        // Иначе вычисляем направление по предыдущей позиции
        previousLocation?.let { prev ->
            val prevPoint = prev.position
            val currentPoint = currentLocation.position

            val deltaX = currentPoint.longitude - prevPoint.longitude
            val deltaY = currentPoint.latitude - prevPoint.latitude

            // Вычисляем азимут в радианах
            val azimuthRad = Math.atan2(deltaX, deltaY)
            // Конвертируем в градусы
            val azimuthDeg = Math.toDegrees(azimuthRad).toFloat()

            // Нормализуем от 0 до 360
            return if (azimuthDeg >= 0) azimuthDeg else azimuthDeg + 360
        }

        // Если нет предыдущей локации - камера смотрит на север
        return 0.0f
    }

    private fun calculateDistance(point1: Point, point2: Point): Double {
        val earthRadius = 6371000.0 // радиус Земли в метрах

        val lat1 = Math.toRadians(point1.latitude)
        val lon1 = Math.toRadians(point1.longitude)
        val lat2 = Math.toRadians(point2.latitude)
        val lon2 = Math.toRadians(point2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return earthRadius * c
    }

    private fun setDefaultLocation() {
        val defaultPoint = Point(56.791660, 60.651930)
        runOnUiThread {
            try {
                if (mapObjects == null) {
                    mapObjects = mapView.mapWindow.map.mapObjects.addCollection()
                }

                userLocationPlacemark?.let {
                    mapObjects?.remove(it)
                }

                userLocationPlacemark = mapObjects?.addPlacemark(defaultPoint)
                userLocationPlacemark?.apply {
                    setText("Автомобиль (по умолчанию)")
                    isDraggable = false
                    setIcon(ImageProvider.fromResource(this@MainActivity, R.drawable.icon_auto))
                }

                // Позиция камеры по умолчанию с наклоном
                val cameraPosition = CameraPosition(
                    defaultPoint,
                    17.0f,   // zoom
                    0.0f,    // азимут (север)
                    60.0f    // наклон
                )

                mapView.mapWindow.map.move(cameraPosition)

                // Устанавливаем скорость 0
                speedTextView.text = "0 км/ч"

                Log.d("LocationDebug", "Default location set with navigation view")
            } catch (e: Exception) {
                Log.e("LocationDebug", "Error setting default location: ${e.message}")
            }
        }
    }

    private fun stopLocationUpdates() {
        locationListener?.let { listener ->
            locationManager?.unsubscribe(listener)
        }
        locationListener = null
        locationManager = null
        Log.d("LocationDebug", "Location updates stopped")
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
        mapView.onStart()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    override fun onStop() {
        stopLocationUpdates()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }
}