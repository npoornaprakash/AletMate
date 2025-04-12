package com.npoor.aletmate

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.os.*
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.view.KeyEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private val PICK_CONTACT = 1
    private lateinit var contactInfo: TextView
    private lateinit var editTimer: EditText
    private lateinit var btnStartTimer: Button
    private lateinit var btnStopTimer: Button
    private lateinit var btnSendAlert: Button
    private lateinit var btnDeleteContacts: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private var lastKnownLocation: Location? = null
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var safetyTimer: CountDownTimer? = null

    private val CHANNEL_ID = "alertmate_timer_channel"
    private val MAX_CONTACTS = 5
    private val PREFS_NAME = "emergency_contacts"
    private val CONTACTS_KEY = "contacts"
    private var volumeDownPressStart: Long = 0
    private var shakeAlertCancelled = false
    private var lastShakeAlertTime: Long = 0L
    private var isAlertPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnAddContact: Button = findViewById(R.id.btnAddContact)
        btnDeleteContacts = findViewById(R.id.btnDeleteContacts)
        contactInfo = findViewById(R.id.txtContactInfo)
        editTimer = findViewById(R.id.editTimer)
        btnStartTimer = findViewById(R.id.btnStartTimer)
        btnStopTimer = findViewById(R.id.btnStopTimer)
        btnSendAlert = findViewById(R.id.btnSendAlert)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        createNotificationChannel()
        requestLocationPermissions()
        setupLiveLocationTracking()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        loadSavedContacts()

        btnAddContact.setOnClickListener { checkPermissionAndPickContact() }
        btnDeleteContacts.setOnClickListener { deleteAllContacts() }
        btnStartTimer.setOnClickListener {
            val minutes = editTimer.text.toString().toIntOrNull()
            if (minutes != null && getSavedContacts().isNotEmpty()) {
                startSafetyTimer(minutes)
            } else {
                Toast.makeText(this, "Set timer and add at least one contact", Toast.LENGTH_SHORT).show()
            }
        }
        btnStopTimer.setOnClickListener {
            stopSafetyTimer()
        }
        btnSendAlert.setOnClickListener {
            val contacts = getSavedContacts()
            if (contacts.isNotEmpty()) {
                sendEmergencySMS("Emergency Alert! I may need help.", contacts)
            } else {
                Toast.makeText(this, "Please add emergency contacts first", Toast.LENGTH_SHORT).show()
            }
        }

        startAlertServiceIfPermitted()
    }

    private fun stopSafetyTimer() {
        safetyTimer?.cancel()
        safetyTimer = null
        btnStartTimer.text = "Start Safety Timer"
        Toast.makeText(this, "Safety Timer Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun startSafetyTimer(minutes: Int) {
        val millis = minutes * 60 * 1000L
        Toast.makeText(this, "Timer started for $minutes minutes", Toast.LENGTH_SHORT).show()
        showNotification("Safety Timer Started", "Will alert in $minutes minutes.")
        safetyTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                btnStartTimer.text = "Time left: ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                btnStartTimer.text = "Start Safety Timer"
                showNotification("Timer Expired", "Sending emergency alert now.")
                sendEmergencySMS("Timer expired! I may need help.", getSavedContacts())
            }
        }.start()
    }

    private fun hasRequiredPermissions(): Boolean {
        return (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED)
    }

    private fun startAlertServiceIfPermitted() {
        if (hasRequiredPermissions()) {
            val serviceIntent = Intent(this, AlertService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    override fun onStart() {
        super.onStart()
        startAlertServiceIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(shakeListener)
    }

    private val shakeListener = object : SensorEventListener {
        private var shakeCount = 0
        private var lastShakeTime: Long = 0

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val (x, y, z) = event.values
                val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble())
                val currentTime = System.currentTimeMillis()

                if (acceleration > 40) {
                    if (currentTime - lastShakeTime < 1200) shakeCount++ else shakeCount = 1
                    lastShakeTime = currentTime
                    if (shakeCount >= 6 && currentTime - lastShakeAlertTime > 10000) {
                        lastShakeAlertTime = currentTime
                        shakeCount = 0
                        val contacts = getSavedContacts()
                        if (contacts.isNotEmpty()) {
                            showNotification("Shake Detected", "Emergency Alert Triggered!")
                            confirmBeforeAlert("Emergency! I triggered the alert by shaking my phone.", contacts)
                        } else {
                            Toast.makeText(this@MainActivity, "Please add emergency contacts first", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun confirmBeforeAlert(message: String, contacts: Set<String>) {
        shakeAlertCancelled = false
        val alertDialog = AlertDialog.Builder(this)
            .setTitle("Emergency Alert")
            .setMessage("Sending alert in 5 seconds. Tap Cancel to stop.")
            .setCancelable(false)
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                shakeAlertCancelled = true
                Toast.makeText(this, "Alert cancelled", Toast.LENGTH_SHORT).show()
            }
            .create()
        alertDialog.show()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!shakeAlertCancelled && alertDialog.isShowing) {
                alertDialog.dismiss()
                sendEmergencySMS(message, contacts)
            }
        }, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (volumeDownPressStart == 0L) {
                        volumeDownPressStart = System.currentTimeMillis()
                    }
                }
                KeyEvent.ACTION_UP -> {
                    val heldTime = System.currentTimeMillis() - volumeDownPressStart
                    volumeDownPressStart = 0L
                    if (heldTime >= 4000) {
                        val contacts = getSavedContacts()
                        if (contacts.isNotEmpty()) {
                            showNotification("Volume Button Held", "Emergency Alert Triggered!")
                            sendEmergencySMS("Emergency! I triggered the alert by holding the volume button.", contacts)
                        } else {
                            Toast.makeText(this, "Please add emergency contacts first", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendEmergencySMS(baseMessage: String, contacts: Set<String>) {
        val locationMsg = lastKnownLocation?.let {
            "\nMy Live Location: https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "\n(Location not available)"
        sendSMSWithMessage("$baseMessage$locationMsg", contacts)
    }

    private fun sendSMSWithMessage(message: String, contacts: Set<String>) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 2)
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            for (contact in contacts) {
                smsManager.sendTextMessage(contact, null, message, null, null)
            }
            Toast.makeText(this, "Emergency SMS sent!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AlertMate", NotificationManager.IMPORTANCE_HIGH)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, text: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun checkPermissionAndPickContact() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PICK_CONTACT)
        } else {
            pickContact()
        }
    }

    private fun pickContact() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        startActivityForResult(intent, PICK_CONTACT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_CONTACT && resultCode == RESULT_OK && data != null) {
            val contactUri = data.data ?: return
            contentResolver.query(contactUri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    saveContact(number)
                }
            }
        }
    }

    private fun saveContact(number: String) {
        val contacts = getSavedContacts().toMutableSet()
        when {
            contacts.contains(number) -> Toast.makeText(this, "Contact already saved", Toast.LENGTH_SHORT).show()
            contacts.size >= MAX_CONTACTS -> Toast.makeText(this, "Max 5 contacts allowed", Toast.LENGTH_SHORT).show()
            else -> {
                contacts.add(number)
                sharedPreferences.edit().putString(CONTACTS_KEY, JSONArray(contacts.toList()).toString()).apply()
                loadSavedContacts()
                Toast.makeText(this, "Contact saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteAllContacts() {
        sharedPreferences.edit().remove(CONTACTS_KEY).apply()
        loadSavedContacts()
        Toast.makeText(this, "All contacts deleted", Toast.LENGTH_SHORT).show()
    }

    private fun getSavedContacts(): Set<String> {
        val jsonString = sharedPreferences.getString(CONTACTS_KEY, null) ?: return emptySet()
        val jsonArray = JSONArray(jsonString)
        return (0 until jsonArray.length()).mapTo(mutableSetOf()) { jsonArray.getString(it) }
    }

    private fun loadSavedContacts() {
        val contacts = getSavedContacts()
        contactInfo.text = if (contacts.isEmpty()) "No contacts saved" else contacts.joinToString("\n")
    }

    private fun setupLiveLocationTracking() {
        locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                lastKnownLocation = result.lastLocation
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }
}
