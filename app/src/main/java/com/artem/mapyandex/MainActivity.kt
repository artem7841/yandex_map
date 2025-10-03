package com.artem.mapyandex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.yandex.mapkit.Animation
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
    private lateinit var plusButton: Button
    private lateinit var minusButton: Button
    private var mapObjects: MapObjectCollection? = null
    private var userLocationPlacemark: PlacemarkMapObject? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    // zoom
    private var currentZoom = 19.0f

    // Для определения направления движения
    private var previousLocation: Location? = null
    val dataClass = DataClass()
    private val cameraPoints = dataClass.cameraPoints
    private val detectionRadius = dataClass.detectionRadius

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

        // ВСЕГДА инициализируем MapKit - он нужен для карты на телефоне и в Auto
        try {
            MapKitFactory.setApiKey(dataClass.apoKey)
            MapKitFactory.initialize(this)
            Log.d("MapKit", "✅ MapKit initialized for both phone and Auto")
        } catch (e: Exception) {
            Log.e("MapKit", "❌ MapKit init error: ${e.message}")
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Общая инициализация для обоих режимов
        mapView = findViewById(R.id.mapview)
        mapView.map.isNightModeEnabled = true

        speedTextView = findViewById(R.id.speedTextView)
        plusButton = findViewById(R.id.plus)
        minusButton = findViewById(R.id.minus)
        cameraAlertTextView = findViewById(R.id.cameraAlertTextView)

        setupSpeedView()
        setupCameraAlertView()

        // Добавляем метки камер на карту (работает и в Auto и на телефоне)
        mapObjects = mapView.mapWindow.map.mapObjects.addCollection()
        setupCameraMarkers()

        // Инициализируем данные для Auto
        initializeAutoData()

        // Запускаем систему локации
        checkLocationPermission()

        Handler().postDelayed({
            forceSendTestData()
        }, 3000)

        Log.d("MainActivity", "🎯 App started - ready for phone and Auto")
    }

    private fun forceSendTestData() {
        val testLocation = Location(
            Point(56.838011, 60.597465), // Центр Екатеринбурга
            15.0,
            250.0,
            null,
            90.0,
            0.0,
            null,
            System.currentTimeMillis(),
            0L
        )
        sendEnhancedDataToCarApp(testLocation)
        Log.d("Phone", "🔄 FORCED test data sent to Auto")
    }

    private fun setupCameraMarkers() {
        try {
            Log.d("CameraMarkers", "📍 Setting up camera markers on map")

            val cameraImageProvider = ImageProvider.fromResource(this, R.drawable.camera_point)

            cameraPoints.forEach { point ->
                mapObjects?.addPlacemark()?.apply {
                    geometry = point
                    setIcon(cameraImageProvider)
                    setIconStyle(IconStyle().apply {
                        scale = 0.1f
                        anchor = PointF(0.5f, 1.0f)
                    })
                }
            }

            Log.d("CameraMarkers", "✅ Added ${cameraPoints.size} camera markers to map")

        } catch (e: Exception) {
            Log.e("CameraMarkers", "❌ Error setting up camera markers: ${e.message}")
        }
    }

    private fun initializeAutoData() {
        val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat("current_lat", 0f)
            putFloat("current_lon", 0f)
            putInt("current_speed", 0)
            putString("nearest_camera", "⏳ Ожидание данных...")
            putString("camera_alert", "")
            putLong("last_update_time", System.currentTimeMillis())
            apply()
        }
        Log.d("AutoDebug", "Инициализированы начальные данные для Auto")
    }

    private fun setupSpeedView() {
        speedTextView.apply {
            setTextColor(Color.WHITE)
            textSize = 40f
            setPadding(24, 12, 24, 12)
            text = "0 км/ч"
            background = null // Полностью прозрачный
            setShadowLayer(6f, 0f, 0f, Color.BLACK) // Тень для читаемости
            val typeface = ResourcesCompat.getFont(this@MainActivity, R.font.geologica)
            setTypeface(typeface, Typeface.BOLD_ITALIC)
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
            visibility = View.GONE
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
        val isAutomotive = packageManager.hasSystemFeature("android.hardware.type.automotive")

        if (isAutomotive) {
            Log.d("LocationDebug", "🚗 ANDROID AUTO - Receiving location from phone")
            // В Android Auto НЕ получаем локацию, а только слушаем данные из SharedPreferences
            startLocationListeningFromPhone()

        } else {
            Log.d("LocationDebug", "📱 PHONE MODE - Getting real location and sending to Auto")
            // На телефоне получаем реальную локацию и отправляем в Auto
            startMapKitLocation()
        }
    }

    private fun startSendingLocationToAuto() {
        Log.d("AutoSync", "📤 Starting to send location to Android Auto")

        val handler = android.os.Handler()
        handler.postDelayed(object : Runnable {
            override fun run() {
                // Периодически проверяем и обновляем данные для Auto
                val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)

                // Логируем статус
                val lastUpdate = sharedPref.getLong("last_update_time", 0)
                val dataAge = (System.currentTimeMillis() - lastUpdate) / 1000

                Log.d("AutoSync", "📊 Auto sync status: data age = ${dataAge}sec")

                handler.postDelayed(this, 2000) // Проверка каждые 2 секунды
            }
        }, 1000)
    }

    private fun startLocationListeningFromPhone() {
        Log.d("AutoListen", "📥 Android Auto listening for phone location")

        val handler = android.os.Handler()
        handler.postDelayed(object : Runnable {
            override fun run() {
                val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)

                // Получаем данные от телефона
                val lat = sharedPref.getFloat("current_lat", 0f)
                val lon = sharedPref.getFloat("current_lon", 0f)
                val speed = sharedPref.getInt("current_speed", 0)
                val lastUpdate = sharedPref.getLong("last_update_time", 0)

                val dataAge = (System.currentTimeMillis() - lastUpdate) / 1000

                if (lat != 0f && lon != 0f) {
                    Log.d("AutoListen", "📍 Received from phone: ${"%.6f".format(lat)}, ${"%.6f".format(lon)}, speed: $speed km/h, age: ${dataAge}sec")

                    // Создаем Yandex Location из полученных данных
                    val locationFromPhone = Location(
                        Point(lat.toDouble(), lon.toDouble()),
                        10.0, // accuracy
                        0.0,  // altitude
                        null,
                        null, // heading
                        (speed / 3.6), // speed (convert km/h to m/s)
                        null,
                        lastUpdate,
                        0L
                    )

                    // ОБНОВЛЯЕМ КАРТУ В ANDROID AUTO!
                    updateUserLocation(locationFromPhone)
                    updateSpeed(locationFromPhone)

                } else {
                    Log.d("AutoListen", "⏳ Waiting for location data from phone...")
                }

                handler.postDelayed(this, 1000) // Проверка каждую секунду
            }
        }, 500)
    }

    private fun startCustomGpsInAuto() {
        Log.d("AutoGPS", "🚀 Starting custom GPS for Android Auto")

        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

            // Проверяем какие провайдеры доступны в Android Auto
            val availableProviders = locationManager.allProviders
            Log.d("AutoGPS", "📡 Available providers in Auto: ${availableProviders.joinToString()}")

            // В Android Auto пробуем разные провайдеры по порядку
            val provider = when {
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> {
                    Log.d("AutoGPS", "✅ Using GPS_PROVIDER")
                    android.location.LocationManager.GPS_PROVIDER
                }
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> {
                    Log.d("AutoGPS", "✅ Using NETWORK_PROVIDER")
                    android.location.LocationManager.NETWORK_PROVIDER
                }
                locationManager.isProviderEnabled(android.location.LocationManager.PASSIVE_PROVIDER) -> {
                    Log.d("AutoGPS", "✅ Using PASSIVE_PROVIDER")
                    android.location.LocationManager.PASSIVE_PROVIDER
                }
                else -> {
                    Log.w("AutoGPS", "❌ No location providers available, using simulation")
                    null
                }
            }

            if (provider != null && ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED) {

                // Создаем listener как отдельную переменную
                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        Log.d("AutoGPS", "📍 Real location received: ${location.latitude}, ${location.longitude}")

                        val yandexLocation = Location(
                            Point(location.latitude, location.longitude),
                            location.accuracy.toDouble(),
                            location.altitude,
                            null,
                            if (location.hasBearing()) location.bearing.toDouble() else null,
                            if (location.hasSpeed()) location.speed.toDouble() else null,
                            null,
                            location.time,
                            0L
                        )

                        updateUserLocation(yandexLocation)
                        updateSpeed(yandexLocation)
                        checkCameraProximity(yandexLocation)
                        sendEnhancedDataToCarApp(yandexLocation)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                        Log.d("AutoGPS", "📊 Location status changed: $provider -> $status")
                    }

                    override fun onProviderEnabled(provider: String) {
                        Log.d("AutoGPS", "✅ Provider enabled: $provider")
                    }

                    override fun onProviderDisabled(provider: String) {
                        Log.d("AutoGPS", "❌ Provider disabled: $provider")
                    }
                }

                // Запрашиваем обновления локации
                locationManager.requestLocationUpdates(
                    provider,
                    1000L, // 1 секунда
                    1f,    // 1 метр
                    locationListener
                )

                Log.d("AutoGPS", "✅ Custom GPS started with provider: $provider")

                // Пытаемся получить последнюю известную локацию
                val lastLocation = locationManager.getLastKnownLocation(provider)
                lastLocation?.let { location ->
                    Log.d("AutoGPS", "📌 Using last known location: ${location.latitude}, ${location.longitude}")

                    // Создаем Yandex Location из Android Location
                    val yandexLocation = Location(
                        Point(location.latitude, location.longitude),
                        location.accuracy.toDouble(),
                        location.altitude,
                        null,
                        if (location.hasBearing()) location.bearing.toDouble() else null,
                        if (location.hasSpeed()) location.speed.toDouble() else null,
                        null,
                        location.time,
                        0L
                    )

                    updateUserLocation(yandexLocation)
                    updateSpeed(yandexLocation)
                    checkCameraProximity(yandexLocation)
                    sendEnhancedDataToCarApp(yandexLocation)
                }

            } else {
                Log.w("AutoGPS", "🚨 No valid provider or permission, starting simulation")
                // Fallback - запускаем плавную симуляцию
                //startSmoothCircularSimulation()
            }

        } catch (e: SecurityException) {
            Log.e("AutoGPS", "🔒 Permission denied: ${e.message}")
            //startSmoothCircularSimulation()
        } catch (e: Exception) {
            Log.e("AutoGPS", "💥 Error starting custom GPS: ${e.message}")
            //startSmoothCircularSimulation()
        }
    }

    // Новый метод для синхронизации данных между приложением и Auto
    private fun startDataSyncService() {
        val handler = android.os.Handler()
        handler.postDelayed(object : Runnable {
            override fun run() {
                // Периодически проверяем и отправляем актуальные данные в Auto
                val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)

                // Логируем что отправляем
                val lat = sharedPref.getFloat("current_lat", 0f)
                val lon = sharedPref.getFloat("current_lon", 0f)
                val speed = sharedPref.getInt("current_speed", 0)

                Log.d("DataSync", "Синхронизация с Auto: lat=$lat, lon=$lon, speed=$speed")

                handler.postDelayed(this, 2000) // Синхронизация каждые 2 секунды
            }
        }, 1000)
    }

    private fun sendEnhancedDataToCarApp(location: Location) {
        val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)

        val nearestCameraInfo = calculateNearestCameraInfo(location.position)
        val speedKmh = getCurrentSpeedKmh(location)
        val alertText = if (cameraAlertTextView.visibility == View.VISIBLE) {
            cameraAlertTextView.text.toString()
        } else {
            ""
        }

        with(sharedPref.edit()) {
            putFloat("current_lat", location.position.latitude.toFloat())
            putFloat("current_lon", location.position.longitude.toFloat())
            putInt("current_speed", speedKmh.toInt())
            putString("nearest_camera", nearestCameraInfo)
            putString("camera_alert", alertText)
            putLong("last_update_time", System.currentTimeMillis())
            apply()
        }

        val isAutomotive = packageManager.hasSystemFeature("android.hardware.type.automotive")
        if (!isAutomotive) {
            Log.d("PhoneToAuto", "📤 PHONE → AUTO: " +
                    "Lat: ${"%.6f".format(location.position.latitude)}, " +
                    "Lon: ${"%.6f".format(location.position.longitude)}, " +
                    "Speed: ${speedKmh.toInt()} km/h")
        }
    }

    private fun forceRealGpsInEmulator() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

            // Принудительно используем GPS провайдер
            if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        Log.d("RealGPS", "Real location: ${location.latitude}, ${location.longitude}")

                        val yandexLocation = Location(
                            Point(location.latitude, location.longitude),
                            location.accuracy.toDouble(),
                            location.altitude,
                            null,
                            if (location.hasBearing()) location.bearing.toDouble() else null,
                            if (location.hasSpeed()) location.speed.toDouble() else null,
                            null,
                            location.time,
                            0L
                        )

                        updateUserLocation(yandexLocation)
                        updateSpeed(yandexLocation)
                        checkCameraProximity(yandexLocation)
                        sendEnhancedDataToCarApp(yandexLocation)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        1000L,
                        1f,
                        locationListener
                    )
                    Log.d("RealGPS", "Real GPS updates started")
                }
            }
        } catch (e: Exception) {
            Log.e("RealGPS", "Real GPS failed: ${e.message}")
        }
    }

    private fun startMapKitLocation() {
        try {
            locationManager = MapKitFactory.getInstance().createLocationManager()

            locationListener = object : LocationListener {
                override fun onLocationUpdated(location: Location) {
                    Log.d("LocationDebug", "MapKit Location updated: ${location.position}")
                    updateUserLocation(location)
                    updateSpeed(location)
                    checkCameraProximity(location)
                    sendLocationToCarApp(location)
                }

                override fun onLocationStatusUpdated(status: LocationStatus) {
                    Log.d("LocationDebug", "MapKit Location status: $status")
                }
            }

            val subscriptionSettings = SubscriptionSettings(
                UseInBackground.DISALLOW,
                Purpose.AUTOMOTIVE_NAVIGATION
            )

            locationListener?.let { listener ->
                locationManager?.subscribeForLocationUpdates(subscriptionSettings, listener)
            }

        } catch (e: Exception) {
            Log.e("LocationDebug", "MapKit location failed: ${e.message}")
            //setupEmulatorLocation()
        }
    }

//    private fun setupEmulatorLocation() {
//        Log.d("EmulatorDebug", "Setting up emulator location")
//
//        val basePoint = Point(56.791660, 60.651930)
//
//        val simulatedLocation = Location(
//            basePoint,
//            15.0,
//            250.0,
//            null,
//            90.0,
//            13.9,
//            null,
//            System.currentTimeMillis(),
//            0L
//        )
//
//        updateUserLocation(simulatedLocation)
//        updateSpeed(simulatedLocation)
//        sendLocationToCarApp(simulatedLocation)
//
//        Log.d("EmulatorDebug", "Emulator location set: $basePoint")
//    }

//    private fun startAutoDataSimulation() {
//        val handler = android.os.Handler()
//
//        // Маршрут для плавного движения по Екатеринбургу
//        val routePoints = listOf(
//            Point(56.791660, 60.651930), // Начальная точка
//            Point(56.792000, 60.652500),
//            Point(56.792300, 60.653000),
//            Point(56.792600, 60.653500),
//            Point(56.792900, 60.654000),
//            Point(56.793200, 60.654500),
//            Point(56.793500, 60.655000),
//            Point(56.793800, 60.655500),
//            Point(56.794100, 60.656000)
//        )
//
//        var currentPointIndex = 0
//        var interpolationFactor = 0f
//
//        handler.postDelayed(object : Runnable {
//            override fun run() {
//                val isAutomotive = packageManager.hasSystemFeature("android.hardware.type.automotive")
//                if (isAutomotive) {
//                    // Плавная интерполяция между точками маршрута
//                    val startPoint = routePoints[currentPointIndex]
//                    val endPoint = routePoints[(currentPointIndex + 1) % routePoints.size]
//
//                    // Линейная интерполяция между точками
//                    val currentLat = startPoint.latitude + (endPoint.latitude - startPoint.latitude) * interpolationFactor
//                    val currentLon = startPoint.longitude + (endPoint.longitude - startPoint.longitude) * interpolationFactor
//
//                    // Плавное изменение скорости (50-60 км/ч)
//                    val baseSpeed = 14.5 // ~52 км/ч
//                    val speedVariation = Math.sin(interpolationFactor * Math.PI * 2) * 0.5 // ±0.5 м/с
//                    val currentSpeed = baseSpeed + speedVariation
//
//                    // Плавное изменение направления
//                    val deltaLat = endPoint.latitude - startPoint.latitude
//                    val deltaLon = endPoint.longitude - startPoint.longitude
//                    val currentHeading = Math.toDegrees(Math.atan2(deltaLon, deltaLat)).toFloat()
//
//                    val simulatedLocation = Location(
//                        Point(currentLat, currentLon),
//                        10.0, // accuracy
//                        250.0, // altitude
//                        null,
//                        currentHeading.toDouble(), // heading
//                        currentSpeed, // speed
//                        null,
//                        System.currentTimeMillis(),
//                        0L
//                    )
//
//                    updateUserLocation(simulatedLocation)
//                    updateSpeed(simulatedLocation)
//                    sendLocationToCarApp(simulatedLocation)
//
//                    // Увеличиваем интерполяцию
//                    interpolationFactor += 0.05f // Плавное движение между точками
//
//                    // Если дошли до конца сегмента, переходим к следующему
//                    if (interpolationFactor >= 1.0f) {
//                        interpolationFactor = 0f
//                        currentPointIndex = (currentPointIndex + 1) % routePoints.size
//                        Log.d("AutoSimulation", "Переход к точке $currentPointIndex")
//                    }
//
//                    Log.d("AutoSimulation", "Позиция: ${"%.6f".format(currentLat)}, ${"%.6f".format(currentLon)}")
//                }
//                handler.postDelayed(this, 500) // Обновление каждые 500мс - оптимально для плавности
//            }
//        }, 500)
//    }

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
        cameraAlertTextView.visibility = View.VISIBLE
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
        cameraAlertTextView.visibility = View.GONE
    }

    private fun updateCameraDistanceInfo(distance: Double) {
        if (distance < detectionRadius * 3) {
            val distanceText = String.format(Locale.getDefault(), "%.0f", distance)
            Log.d("CameraDebug", "Distance to nearest camera: $distance m")
        }
    }

    private fun sendLocationToCarApp(location: Location) {
        val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)

        // Рассчитываем ближайшую камеру
        val nearestCameraInfo = calculateNearestCameraInfo(location.position)

        with(sharedPref.edit()) {
            putFloat("current_lat", location.position.latitude.toFloat())
            putFloat("current_lon", location.position.longitude.toFloat())
            putInt("current_speed", getCurrentSpeedKmh(location).toInt())
            putString("nearest_camera", nearestCameraInfo)
            apply()
        }

        Log.d("AutoDebug", "Data sent to Auto: ${location.position}, $nearestCameraInfo")
    }

    private fun calculateNearestCameraInfo(userPosition: Point): String {
        var minDistance = Double.MAX_VALUE

        for (cameraPoint in cameraPoints) {
            val distance = calculateDistance(userPosition, cameraPoint)
            if (distance < minDistance) {
                minDistance = distance
            }
        }

        return when {
            minDistance < 50 -> "🚨 ОЧЕНЬ БЛИЗКО! ${minDistance.toInt()} м"
            minDistance < 200 -> "⚠️ Близко ${minDistance.toInt()} м"
            minDistance < 500 -> "📷 ${minDistance.toInt()} м впереди"
            minDistance < 1000 -> "👀 Далеко ${minDistance.toInt()} м"
            else -> "✅ Камер поблизости нет"
        }
    }

    private fun updateUserLocation(location: Location) {
        runOnUiThread {
            try {
                val isAutomotive = packageManager.hasSystemFeature("android.hardware.type.automotive")

                if (isAutomotive) {
                    // В ANDROID AUTO: добавляем метку текущей локации на карту
                    Log.d("AutoMap", "📍 Adding user location to Auto map: ${location.position}")

                    if (mapObjects == null && ::mapView.isInitialized) {
                        mapObjects = mapView.mapWindow.map.mapObjects.addCollection()
                    }

                    // Удаляем предыдущую метку
                    userLocationPlacemark?.let {
                        mapObjects?.remove(it)
                    }

                    // Создаем новую метку для автомобиля СИНЕГО цвета
                    userLocationPlacemark = mapObjects?.addPlacemark(location.position)
                    userLocationPlacemark?.apply {
                        isDraggable = false
                        setIcon(ImageProvider.fromResource(this@MainActivity, R.drawable.icon_auto))
                        setIconStyle(IconStyle().apply {
                            anchor = PointF(0.5f, 1.0f)
                            scale = 0.1f
                            rotationType = RotationType.NO_ROTATION
                            zIndex = 100.0f
                        })
                    }

                    // Двигаем камеру к новой позиции
                    val cameraPosition = CameraPosition(
                        location.position,
                        currentZoom,
                        0.0f, // азимут
                        60.0f // наклон
                    )

                    mapView.map.move(
                        cameraPosition,
                        Animation(Animation.Type.SMOOTH, 0.5f),
                        null
                    )

                    Log.d("AutoMap", "✅ User location updated on Auto map")

                } else {
                    // НА ТЕЛЕФОНЕ: обычная логика (твоя существующая)
                    if (mapObjects == null) {
                        mapObjects = mapView.mapWindow.map.mapObjects.addCollection()
                    }

                    userLocationPlacemark?.let {
                        mapObjects?.remove(it)
                    }

                    userLocationPlacemark = mapObjects?.addPlacemark(location.position)
                    userLocationPlacemark?.apply {
                        isDraggable = false
                        setIcon(ImageProvider.fromResource(this@MainActivity, R.drawable.icon_auto))
                        setIconStyle(IconStyle().apply {
                            anchor = PointF(0.5f, 1.0f)
                            scale = 0.1f
                            rotationType = RotationType.NO_ROTATION
                            zIndex = 100.0f
                        })
                    }

                    val azimuth = calculateAzimuth(location)
                    val cameraPosition = CameraPosition(
                        location.position,
                        currentZoom,
                        azimuth,
                        60.0f
                    )

                    mapView.map.move(
                        cameraPosition,
                        Animation(Animation.Type.SMOOTH, 0.3f),
                        null
                    )
                }

                previousLocation = location
                sendEnhancedDataToCarApp(location)

            } catch (e: Exception) {
                Log.e("LocationDebug", "❌ Error updating user location: ${e.message}")
            }
        }
    }

    private fun updateCameraZoom() {
        val cameraPosition = CameraPosition(
            mapView.map.cameraPosition.target,
            currentZoom,
            mapView.map.cameraPosition.azimuth,
            mapView.map.cameraPosition.tilt
        )

        mapView.map.move(
            cameraPosition,
            Animation(Animation.Type.SMOOTH, 0.3f),
            null
        )
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

                Log.d("SpeedDebug", "Speed: $speedKmh km/h")

                // Отправляем данные в Android Auto
                val alertText = if (cameraAlertTextView.visibility == View.VISIBLE) {
                    cameraAlertTextView.text.toString()
                } else {
                    ""
                }

                sendDataToCarApp(speedKmh, alertText)

            } catch (e: Exception) {
                Log.e("SpeedDebug", "Error updating speed: ${e.message}")
            }
        }
    }

    private fun sendDataToCarApp(speed: Double, cameraAlert: String) {
        val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt("current_speed", speed.toInt())
            putString("camera_alert", cameraAlert)
            apply()
        }
    }

    private fun calculateSpeedFromMovement(currentLocation: Location): Double {
        previousLocation?.let { prev ->
            val timeDiff = (currentLocation.absoluteTimestamp - prev.absoluteTimestamp) / 1000.0
            if (timeDiff > 0) {
                val distance = calculateDistance(prev.position, currentLocation.position)
                val speedMs = distance / timeDiff
                return speedMs * 3.6
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
        val earthRadius = 6371000.0

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
                    17.0f,
                    0.0f,
                    60.0f
                )

                mapView.map.move(cameraPosition)

                // Устанавливаем скорость 0
                speedTextView.text = "0 км/ч"

                Log.d("LocationDebug", "Default location set with navigation view")

                val sharedPref = getSharedPreferences("car_data", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putFloat("current_lat", defaultPoint.latitude.toFloat())
                    putFloat("current_lon", defaultPoint.longitude.toFloat())
                    putInt("current_speed", 0)
                    putString("nearest_camera", "Режим по умолчанию")
                    apply()
                }
            } catch (e: Exception) {
                Log.e("LocationDebug", "Error setting default location: ${e.message}")
            }
        }
    }

    private fun stopLocationUpdates() {
        // Останавливаем MapKit LocationManager
        try {
            locationListener?.let { listener ->
                locationManager?.unsubscribe(listener)
            }
        } catch (e: Exception) {
            Log.e("LocationDebug", "Error stopping MapKit location: ${e.message}")
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