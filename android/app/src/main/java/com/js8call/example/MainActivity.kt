package com.js8call.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_LOCATION = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up bottom navigation
        bottomNav = findViewById(R.id.bottom_navigation)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        bottomNav.setupWithNavController(navController)

        // Check permissions
        checkPermissions()
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show explanation
                Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.permission_audio_message,
                    Snackbar.LENGTH_LONG
                ).setAction("Grant") {
                    requestAudioPermission()
                }.show()
            }
            else -> {
                // Request permission
                requestAudioPermission()
            }
        }
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // Permission granted
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Microphone permission granted",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    // Permission denied
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.permission_audio_denied,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
