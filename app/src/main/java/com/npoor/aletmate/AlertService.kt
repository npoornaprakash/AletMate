package com.npoor.aletmate

import android.app.*
import android.content.*
import android.hardware.*
import android.location.Location
import android.os.*
import android.telephony.SmsManager
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray

class AlertService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var shakeCount = 0
    private var lastShakeTime: Long = 0
    private val shakeThreshold = 40.0
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: Location? = null
    private val CHANNEL_ID = "alertmate_service_channel"
    private var lastShakeAlertTime: Long = 0
    private var alertPending = false
    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        const val ACTION_CANCEL_ALERT = "com.npoor.aletmate.CANCEL_ALERT"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize shake detection
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        // Shared prefs and location setup
        sharedPreferences = getSharedPreferences("emergency_contacts", Context.MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLiveLocationUpdates()

        // Foreground service and wake lock
        createNotificationChannel()
        startForeground(1, getListeningNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertMate::ShakeLock")
        wakeLock.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALERT) {
            alertPending = false
            stopForeground(true)
            startForeground(1, getListeningNotification())
            return START_STICKY
        }

        accelerometer?.also {
            sensorManager.unregisterListener(this)
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        if (this::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val (x, y, z) = event.values
            val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble())
            val currentTime = System.currentTimeMillis()

            if (acceleration > shakeThreshold) {
                if (currentTime - lastShakeTime < 1200) shakeCount++ else shakeCount = 1
                lastShakeTime = currentTime

                if (shakeCount >= 6 && currentTime - lastShakeAlertTime > 10000) {
                    lastShakeAlertTime = currentTime
                    shakeCount = 0
                    triggerAlertWithDelay()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerAlertWithDelay() {
        if (alertPending) return
        alertPending = true

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }

        val cancelIntent = Intent(this, AlertService::class.java).apply {
            action = ACTION_CANCEL_ALERT
        }
        val pendingIntent = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency Alert")
            .setContentText("Alert will be sent in 5 seconds. Tap Cancel to stop.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(NotificationCompat.Action(0, "Cancel", pendingIntent))
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        startForeground(2, notification)

        Handler(Looper.getMainLooper()).postDelayed({
            if (alertPending) {
                sendEmergencySMS("Emergency Alert from background!")
                stopForeground(true)
                startForeground(1, getListeningNotification())
                alertPending = false
            }
        }, 5000)
    }

    private fun sendEmergencySMS(baseMessage: String) {
        val contacts = getSavedContacts()
        val locationMsg = lastKnownLocation?.let {
            "\nMy Live Location: https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "\n(Location not available)"
        val fullMsg = baseMessage + locationMsg

        try {
            val smsManager = SmsManager.getDefault()
            for (contact in contacts) {
                smsManager.sendTextMessage(contact, null, fullMsg, null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSavedContacts(): Set<String> {
        val jsonString = sharedPreferences.getString("contacts", null) ?: return emptySet()
        val jsonArray = JSONArray(jsonString)
        return (0 until jsonArray.length()).mapTo(mutableSetOf()) { jsonArray.getString(it) }
    }

    private fun requestLiveLocationUpdates() {
        val request = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    lastKnownLocation = result.lastLocation
                }
            }, Looper.getMainLooper())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AlertMate Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getListeningNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AlertMate Service Running")
            .setContentText("Listening for emergency gestures...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }
}
