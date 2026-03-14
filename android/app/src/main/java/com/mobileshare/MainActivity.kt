package com.mobileshare

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var frameDisplay: FrameDisplayView
    private lateinit var qrScanner: QrScannerView
    private lateinit var statusText: TextView

    private var serverThread: Thread? = null
    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var frameReader: FrameReader? = null

    private val PREFS = "mobileshare"
    private val KEY_SECRET = "secret"
    private val CAMERA_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frameDisplay = findViewById(R.id.frameDisplay)
        qrScanner = findViewById(R.id.qrScanner)
        statusText = findViewById(R.id.statusText)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        val secret = getSecret()
        if (secret.isEmpty()) {
            startPairing()
        } else {
            startServer()
        }
    }

    private fun getSecret(): String {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SECRET, "") ?: ""
    }

    private fun saveSecret(secret: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putString(KEY_SECRET, secret).apply()
    }

    private fun startPairing() {
        statusText.text = getString(R.string.scan_qr)
        frameDisplay.visibility = View.GONE
        qrScanner.visibility = View.VISIBLE

        qrScanner.onQrDecoded = { secret ->
            saveSecret(secret)
            qrScanner.shutdown()
            qrScanner.visibility = View.GONE
            frameDisplay.visibility = View.VISIBLE
            statusText.text = getString(R.string.paired)
            startServer()
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == CAMERA_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // Re-create the scanner surface to trigger camera open
            qrScanner.visibility = View.GONE
            qrScanner.visibility = View.VISIBLE
        }
    }

    private fun startServer() {
        serverThread = Thread({
            try {
                try { serverSocket?.close() } catch (_: Exception) {}

                val server = LocalServerSocket("mobileshare")
                serverSocket = server

                runOnUiThread {
                    statusText.text = getString(R.string.waiting_for_connection)
                }

                while (!Thread.currentThread().isInterrupted) {
                    val client = server.accept() ?: continue

                    // Only allow ADB shell (UID 2000)
                    val peerUid = client.peerCredentials.uid
                    if (peerUid != 2000) {
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }

                    // Send secret for PC to verify, wait for confirmation
                    val secret = getSecret()
                    client.outputStream.write(secret.toByteArray())
                    client.outputStream.flush()
                    val confirm = ByteArray(1)
                    val n = client.inputStream.read(confirm)
                    if (n != 1 || confirm[0] != 1.toByte()) {
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }

                    clientSocket = client

                    runOnUiThread {
                        statusText.text = getString(R.string.connected)
                    }

                    val reader = FrameReader(client.inputStream) { bitmap ->
                        runOnUiThread {
                            frameDisplay.updateFrame(bitmap)
                            if (statusText.visibility == View.VISIBLE) {
                                statusText.visibility = View.GONE
                            }
                        }
                    }
                    frameReader = reader
                    reader.run()

                    frameReader = null
                    try { client.close() } catch (_: Exception) {}
                    clientSocket = null

                    runOnUiThread {
                        statusText.text = getString(R.string.waiting_for_connection)
                        statusText.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {}
        }, "SocketServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopServer() {
        frameReader?.shutdown()
        frameReader = null
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
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
        qrScanner.shutdown()
        stopServer()
    }
}
