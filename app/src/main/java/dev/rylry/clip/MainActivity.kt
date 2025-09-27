package dev.rylry.clip

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.Toast

class MainActivity : ComponentActivity() {

    private var recordingService: RecordingService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setupUI()
    }

    private fun requestPermissions() {
        // First, request audio permission
        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun setupUI() {
        val saveButton = Button(this).apply {
            text = "Save Current Buffer"
            setOnClickListener {
                if (bound) {
                    recordingService?.saveCurrentBufferToFile()
                    Toast.makeText(this@MainActivity, "Buffer saved!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Service not bound", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        setContentView(saveButton)
    }

    // Launcher for audio
    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Audio granted -> request notification permission if needed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startRecordingService() // Older Android → start directly
                }
            } else {
                Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // Launcher for notifications
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startRecordingService()
            } else {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
