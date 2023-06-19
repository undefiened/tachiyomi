package eu.kanade.tachiyomi.data.sync

import android.net.Uri
import kotlinx.coroutines.*
import logcat.LogPriority
import tachiyomi.core.util.system.logcat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI

/**
 * OAuthCallbackServer is a lightweight HTTP server that listens for an authorization code from Google OAuth.
 * It is used to handle the authorization flow for Google Drive API access.
 */
class OAuthCallbackServer {

    private val port = 53682
    var onCallback: (String) -> Unit = {}
        private set

    private var serverThread = ServerThread()

    /**
     * Starts the OAuthCallbackServer if it hasn't been started yet.
     */
    fun start() {
        if (serverThread.state != Thread.State.RUNNABLE) {
            serverThread = ServerThread()
            serverThread.start()
            logcat(LogPriority.INFO) { "OAuthCallbackServer started on port $port" }
        }
    }

    /**
     * Stops the OAuthCallbackServer.
     */
    fun stop() {
        serverThread.stopServer()
    }

    /**
     * ServerThread is an inner class that extends Thread and is responsible for managing the server socket and handling incoming connections.
     */
    inner class ServerThread : Thread() {

        private var serverSocket: ServerSocket? = null
        private var running = true
        private var timeoutMillis = 180000L // e.g. 180 seconds / 3 min
        private var timeoutJob: Job? = null

        private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

        override fun run() {
            try {
                serverSocket = ServerSocket(port)

                // Start the timeout job
                timeoutJob = coroutineScope.launch {
                    delay(timeoutMillis)

                    // This block will run if no response is received within timeoutMillis
                    logcat(LogPriority.INFO) { "User abandoned login process" }
                    stopServer()
                }

                while (running) {
                    val socket = serverSocket?.accept()
                    handleClient(socket)
                }
            } catch (e: IOException) {
                if (e is SocketException && e.message == "Socket closed") {
                    logcat(LogPriority.INFO) { "OAuthCallbackServer was intentionally closed." }
                } else {
                    logcat(LogPriority.ERROR) { "Error in ServerThread: ${e.localizedMessage}" }
                    e.printStackTrace()
                }
            } finally {
                serverSocket?.close()
            }
        }

        /**
         * Handles an incoming client connection and processes the HTTP request.
         * If the request is for the "/auth" path and contains an authorization code, it calls the callback function.
         * In case the authorization code is null, it sends an error response to the client.
         * If the request is successful, it sends a success response to the client.
         *
         * @param socket The client socket.
         */
        private fun handleClient(socket: Socket?) {
            timeoutJob?.cancel()
            timeoutJob = coroutineScope.launch {
                delay(timeoutMillis)

                // This block will run if no response is received within timeoutMillis
                logcat(LogPriority.INFO) { "User abandoned login process" }
                socket?.close()
                logcat(LogPriority.INFO) { "Client socket closed due to timeout" }
                stopServer()
            }

            val input = BufferedReader(InputStreamReader(socket?.getInputStream()))
            val output = BufferedWriter(OutputStreamWriter(socket?.getOutputStream()))

            val requestLine = input.readLine()

            if (requestLine != null) {
                val requestUri = requestLine.split(" ")[1]
                val uri = URI(requestUri)

                if (uri.path == "/auth") {
                    val queryParams = uri.query.split("&").map { it.split("=") }.associate { it[0] to it[1] }
                    val authorizationCode = queryParams["code"]
                    val errorQuery = queryParams["error"]
                    if (authorizationCode != null) {
                        onCallback(authorizationCode)
                        logcat(LogPriority.INFO) { "Received authorization code: $authorizationCode" }

                        timeoutJob?.cancel()
                        timeoutJob = null
                    } else {
                        logcat(LogPriority.ERROR) { "Google Authorization code is null: access refused." }
                        val redirectUri = Uri.parse("tachiyomi://googledrive-auth").buildUpon()
                            .appendQueryParameter("error", errorQuery)
                            .build()
                        output.write("HTTP/1.1 302 Found\r\n")
                        output.write("Location: $redirectUri\r\n")
                        output.write("\r\n")
                        output.flush()
                        socket?.close()
                        logcat(LogPriority.INFO) { "Client socket closed: Google Authorization code is null " }
                        return
                    }

                    val redirectUri = Uri.parse("tachiyomi://googledrive-auth").buildUpon()
                        .appendQueryParameter("code", authorizationCode)
                        .build()

                    output.write("HTTP/1.1 302 Found\r\n")
                    output.write("Location: $redirectUri\r\n")
                    output.write("\r\n")
                    output.flush()
                }
            } else {
                logcat(LogPriority.ERROR) { "Request line is null" }
            }

            socket?.close()
            logcat(LogPriority.INFO) { "Client socket closed" }
        }

        fun stopServer() {
            running = false
            try {
                serverSocket?.close()
                logcat(LogPriority.INFO) { "OAuthCallbackServer stopped" }
                timeoutJob?.cancel()
                coroutineScope.cancel()
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Error stopping ServerThread: ${e.localizedMessage}" }
                e.printStackTrace()
            }
        }
    }
}
