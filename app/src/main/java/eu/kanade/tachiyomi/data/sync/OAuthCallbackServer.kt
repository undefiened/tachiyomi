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

/**
 * OAuthCallbackServer is a lightweight HTTP server that listens for an authorization code from Google OAuth.
 * It is used to handle the authorization flow for Google Drive API access.
 */
class OAuthCallbackServer {

    private val port = 53682
    var onCallback: (String) -> Unit = {}
        private set

    /**
     * Sets the callback function to be called when the authorization code is received.
     * @param onCallbackListener The callback function.
     */
    fun setOnCallbackListener(onCallbackListener: (String) -> Unit) {
        onCallback = onCallbackListener
    }

    private val serverThread = ServerThread()

    /**
     * Starts the OAuthCallbackServer if it hasn't been started yet.
     */
    fun start() {
        if (serverThread.state == Thread.State.NEW) {
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

        override fun run() {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val socket = serverSocket?.accept()
                    handleClient(socket)
                }
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Error in ServerThread: ${e.localizedMessage}" }
                e.printStackTrace()
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
                        logcat(LogPriority.INFO) { "Received authorization code: $authorizationCode" }
                    } else {
                        logcat(LogPriority.ERROR) { "Google Authorization code is null" }
                        // Send a response to the browser
                        output.write("HTTP/1.1 200 OK\r\n")
                        output.write("Content-Type: text/html; charset=UTF-8\r\n")
                        output.write("Connection: close\r\n")
                        output.write("\r\n")
                        output.write("<html><body><h1>Authorization failed Google Authorization code is null. You can close this window now.</h1></body></html>")
                        output.flush()
                        socket?.close()
                        logcat(LogPriority.INFO) { "Client socket closed: Google Authorization code is null " }
                        return
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
            logcat(LogPriority.INFO) { "Client socket closed" }
        }

        fun stopServer() {
            running = false
            try {
                serverSocket?.close()
                logcat(LogPriority.INFO) { "OAuthCallbackServer stopped" }
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Error stopping ServerThread: ${e.localizedMessage}" }
                e.printStackTrace()
            }
        }
    }
}
