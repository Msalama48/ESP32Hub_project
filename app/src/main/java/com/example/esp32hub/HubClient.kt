package com.example.esp32hub

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** TCP client for the ESP32 master (port 6000): challenge/response login, then line commands. */
class HubClient(ctx: Context) {
    private val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val sender = Executors.newSingleThreadExecutor()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStreamWriter? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    var onLine: (String) -> Unit = {}
    var onState: (Boolean) -> Unit = {}

    fun connect(host: String, port: Int, password: String) {
        disconnect()
        Thread {
            try {
                onLine("# Looking for the Wi-Fi network...")
                val net = wifiNetwork()
                if (net == null) onLine("# No Wi-Fi network found, using default route")
                // Using the Wi-Fi network's own socket factory forces traffic over the ESP32 hotspot
                // even though that network has no internet and Android prefers mobile data.
                val s: Socket = net?.socketFactory?.createSocket() ?: Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s
                val rd = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
                out = OutputStreamWriter(s.getOutputStream(), "UTF-8")

                val chal = rd.readLine() ?: throw IOException("connection closed")
                if (!chal.startsWith("CHAL ")) throw IOException(chal)
                val nonce = chal.substring(5).trim()
                write("AUTH " + sha256(nonce + password))

                while (true) {
                    val line = rd.readLine() ?: break
                    if (line.startsWith("OK AUTHED")) onState(true)
                    onLine(line)
                }
            } catch (e: Exception) {
                onLine("# Error: ${e.message}")
            } finally {
                cleanup()
                onState(false)
                onLine("# Disconnected")
            }
        }.start()
    }

    fun send(cmd: String) {
        sender.execute {
            try { write(cmd) } catch (e: Exception) { onLine("# Send failed: ${e.message}") }
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun write(s: String) {
        synchronized(this) {
            val o = out ?: throw IOException("not connected")
            o.write(s + "\n"); o.flush()
        }
    }

    private fun cleanup() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
    }

    private fun wifiNetwork(): Network? {
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val latch = CountDownLatch(1)
        var found: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { found = network; latch.countDown() }
        }
        callback = cb
        cm.requestNetwork(req, cb)
        latch.await(4, TimeUnit.SECONDS)
        return found
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
