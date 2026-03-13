package com.mobileshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import android.app.Activity
import java.io.FileInputStream

class MainActivity : Activity() {

    private lateinit var frameDisplay: FrameDisplayView
    private lateinit var statusText: TextView

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var frameReader: UsbFrameReader? = null

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_ACCESSORY_DETACHED == intent.action) {
                closeAccessory()
                statusText.text = getString(R.string.disconnected)
                statusText.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frameDisplay = findViewById(R.id.frameDisplay)
        statusText = findViewById(R.id.statusText)

        goFullscreen()

        registerReceiver(
            usbDetachReceiver,
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            RECEIVER_NOT_EXPORTED
        )

        // Check if launched via USB accessory intent
        if (intent != null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED == intent.action) {
            val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            if (accessory != null) {
                openAccessory(accessory)
                return
            }
        }

        // Fallback: check connected accessories
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val accessories = usbManager.accessoryList
        if (!accessories.isNullOrEmpty()) {
            openAccessory(accessories[0])
        }
    }

    private fun openAccessory(accessory: UsbAccessory) {
        closeAccessory()

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val pfd = usbManager.openAccessory(accessory)
        if (pfd == null) {
            statusText.text = "Failed to open USB accessory"
            return
        }

        fileDescriptor = pfd
        val stream = FileInputStream(pfd.fileDescriptor)
        inputStream = stream

        statusText.text = getString(R.string.connected)

        val reader = UsbFrameReader(stream) { bitmap ->
            runOnUiThread {
                frameDisplay.updateFrame(bitmap)
                // Hide status after first frame
                if (statusText.visibility == View.VISIBLE) {
                    statusText.visibility = View.GONE
                }
            }
        }
        frameReader = reader
        reader.start()
    }

    private fun closeAccessory() {
        frameReader?.shutdown()
        frameReader = null

        try { inputStream?.close() } catch (_: Exception) {}
        inputStream = null

        try { fileDescriptor?.close() } catch (_: Exception) {}
        fileDescriptor = null
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbDetachReceiver)
        closeAccessory()
    }
}
