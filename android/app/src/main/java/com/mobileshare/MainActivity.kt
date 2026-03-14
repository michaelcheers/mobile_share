package com.mobileshare

import android.app.Activity
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import java.io.InputStream

class MainActivity : Activity() {

    private lateinit var frameDisplay: FrameDisplayView
    private lateinit var statusText: TextView

    private var serverThread: Thread? = null
    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var frameReader: FrameReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frameDisplay = findViewById(R.id.frameDisplay)
        statusText = findViewById(R.id.statusText)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
        startServer()
    }

    private fun startServer() {
        serverThread = Thread({
            try {
                // Close any previous socket with this name
                try { serverSocket?.close() } catch (_: Exception) {}

                val server = LocalServerSocket("mobileshare")
                serverSocket = server

                runOnUiThread {
                    statusText.text = getString(R.string.waiting_for_connection)
                }

                while (!Thread.currentThread().isInterrupted) {
                    // Accept a connection (blocks)
                    val client = server.accept() ?: continue
                    clientSocket = client

                    runOnUiThread {
                        statusText.text = getString(R.string.connected)
                    }

                    // Read frames from this connection
                    val reader = FrameReader(client.inputStream) { bitmap ->
                        runOnUiThread {
                            frameDisplay.updateFrame(bitmap)
                            if (statusText.visibility == View.VISIBLE) {
                                statusText.visibility = View.GONE
                            }
                        }
                    }
                    frameReader = reader
                    reader.run() // blocks until connection drops

                    // Connection ended — show status and loop to accept again
                    frameReader = null
                    try { client.close() } catch (_: Exception) {}
                    clientSocket = null

                    runOnUiThread {
                        statusText.text = getString(R.string.waiting_for_connection)
                        statusText.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {
                // Server socket closed
            }
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
        stopServer()
    }
}
