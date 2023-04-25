package eu.kanade.tachiyomi.data.sync

import logcat.LogPriority
import tachiyomi.core.util.system.logcat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

class OAuthCallbackServer {

    private val port = 53682
    var onCallback: (String) -> Unit = {}
        private set

    fun setOnCallbackListener(onCallbackListener: (String) -> Unit) {
        onCallback = onCallbackListener
    }

    private val serverThread = ServerThread()

    fun start() {
        if (serverThread.state == Thread.State.NEW) {
            serverThread.start()
        }
    }

    fun stop() {
        serverThread.stopServer()
    }

    inner class ServerThread : Thread() {
        private var serverSocket: ServerSocket? = null
        private var running = true

        override fun run() {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val socket = serverSocket?.accept()
                    handleClient(socket)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                serverSocket?.close()
            }
        }

        private fun handleClient(socket: Socket?) {
            val input = BufferedReader(InputStreamReader(socket?.getInputStream()))
            val output = BufferedWriter(OutputStreamWriter(socket?.getOutputStream()))

            val requestLine = input.readLine()

            if (requestLine != null) {
                val requestUri = requestLine.split(" ")[1]
                val uri = URI(requestUri)

                if (uri.path == "/auth") {
                    val queryParams = uri.query.split("&").map { it.split("=") }.associate { it[0] to it[1] }
                    val authorizationCode = queryParams["code"]
                    if (authorizationCode != null) {
                        onCallback(authorizationCode)
                    } else {
                        logcat(LogPriority.ERROR) { "Google Authorization code is null" }
                    }

                    // Send a response to the browser
                    output.write("HTTP/1.1 200 OK\r\n")
                    output.write("Content-Type: text/html; charset=UTF-8\r\n")
                    output.write("Connection: close\r\n")
                    output.write("\r\n")
                    output.write("<html><body><h1>Authorization successful. You can close this window now.</h1></body></html>")
                    output.flush()
                }
            } else {
                logcat(LogPriority.ERROR) { "Request line is null" }
            }

            socket?.close()
        }

        fun stopServer() {
            running = false
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
