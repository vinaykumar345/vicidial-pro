package com.vicidial.simagent

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SipPhone(private val context: Context) {

    enum class State { IDLE, REGISTERING, REGISTERED, IN_CALL, ERROR }

    var state: State = State.IDLE
        private set
    var onStateChanged: ((State, String) -> Unit)? = null

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var socket: DatagramSocket? = null
    private var reRegisterTask: ScheduledFuture<*>? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var sipUser = ""
    private var sipPassword = ""
    private var sipServer = ""
    private var sipPort = 5060
    private var localIp = ""
    private var localPort = 5080
    private var callId = ""
    private var cseq = 1
    private var fromTag = ""

    fun register(user: String, password: String, server: String) {
        sipUser = user
        sipPassword = password
        sipServer = server.substringBefore(":")
        sipPort = server.substringAfter(":", "5060").toIntOrNull() ?: 5060
        executor.submit { doRegister() }
    }

    fun unregister() {
        reRegisterTask?.cancel(false)
        executor.submit {
            try { sendRegister(expires = 0) } catch (_: Exception) {}
            closeSocket()
            emit(State.IDLE, "Unregistered")
        }
    }

    fun makeCall(ext: String) { emit(State.IN_CALL, "Calling $ext…") }
    fun hangup() { if (state == State.IN_CALL) emit(State.REGISTERED, "Call ended") }

    fun toggleMute() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isMicrophoneMute = !am.isMicrophoneMute
    }

    fun isMuted() = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute

    private fun doRegister() {
        try {
            emit(State.REGISTERING, "Registering $sipUser@$sipServer…")
            localIp = getLocalIp()
            callId = UUID.randomUUID().toString().replace("-", "")
            fromTag = UUID.randomUUID().toString().take(8)
            cseq = 1

            closeSocket()
            socket = DatagramSocket(localPort)
            socket!!.soTimeout = 5000

            acquireWifiLock()
            sendRegister(expires = 3600)
            listenForResponse()
        } catch (e: Exception) {
            emit(State.ERROR, "SIP error: ${e.message}")
        }
    }

    private fun sendRegister(expires: Int) {
        val branch = "z9hG4bK${UUID.randomUUID().toString().take(8)}"
        val msg = buildString {
            append("REGISTER sip:$sipServer SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <sip:$sipUser@$sipServer>;tag=$fromTag\r\n")
            append("To: <sip:$sipUser@$sipServer>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq REGISTER\r\n")
            append("Contact: <sip:$sipUser@$localIp:$localPort>\r\n")
            append("Expires: $expires\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }
        sendUdp(msg)
        cseq++
    }

    private fun sendRegisterWithAuth(realm: String, nonce: String, expires: Int = 3600) {
        val ha1 = md5("$sipUser:$realm:$sipPassword")
        val ha2 = md5("REGISTER:sip:$sipServer")
        val response = md5("$ha1:$nonce:$ha2")
        val branch = "z9hG4bK${UUID.randomUUID().toString().take(8)}"
        val msg = buildString {
            append("REGISTER sip:$sipServer SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <sip:$sipUser@$sipServer>;tag=$fromTag\r\n")
            append("To: <sip:$sipUser@$sipServer>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq REGISTER\r\n")
            append("Contact: <sip:$sipUser@$localIp:$localPort>\r\n")
            append("Expires: $expires\r\n")
            append("Authorization: Digest username=\"$sipUser\",realm=\"$realm\",nonce=\"$nonce\",uri=\"sip:$sipServer\",response=\"$response\",algorithm=MD5\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }
        sendUdp(msg)
        cseq++
    }

    private fun listenForResponse() {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        try {
            socket!!.receive(packet)
            val resp = String(packet.data, 0, packet.length)
            handleResponse(resp)
        } catch (e: Exception) {
            if (state == State.REGISTERING) {
                emit(State.ERROR, "No response from $sipServer:$sipPort — check network/firewall")
            }
        }
    }

    private fun handleResponse(resp: String) {
        val statusLine = resp.lines().firstOrNull() ?: return
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return

        when (code) {
            401, 407 -> {
                val authHeader = resp.lines().firstOrNull {
                    it.startsWith("WWW-Authenticate:", ignoreCase = true) ||
                    it.startsWith("Proxy-Authenticate:", ignoreCase = true)
                } ?: run {
                    emit(State.ERROR, "Auth required but no challenge header")
                    return
                }
                val realm = extractParam(authHeader, "realm") ?: ""
                val nonce = extractParam(authHeader, "nonce") ?: ""
                sendRegisterWithAuth(realm, nonce)
                listenForResponse()
            }
            200 -> {
                emit(State.REGISTERED, "Registered ✓  ($sipUser@$sipServer)")
                scheduleReRegister()
            }
            403 -> emit(State.ERROR, "Registration forbidden — check credentials")
            404 -> emit(State.ERROR, "User not found on server")
            else -> emit(State.ERROR, "Registration failed: $code")
        }
    }

    private fun scheduleReRegister() {
        reRegisterTask?.cancel(false)
        reRegisterTask = executor.scheduleAtFixedRate({
            if (state == State.REGISTERED) {
                try {
                    sendRegister(expires = 3600)
                    listenForResponse()
                } catch (_: Exception) {}
            }
        }, 3000, 3000, TimeUnit.SECONDS)
    }

    private fun sendUdp(msg: String) {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        val addr = InetAddress.getByName(sipServer)
        val packet = DatagramPacket(bytes, bytes.size, addr, sipPort)
        socket!!.send(packet)
    }

    private fun extractParam(header: String, param: String): String? {
        val regex = Regex("""$param="([^"]+)"""")
        return regex.find(header)?.groupValues?.get(1)
    }

    private fun getLocalIp(): String = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    } catch (_: Exception) {
        InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
    }

    private fun md5(s: String) = java.security.MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun acquireWifiLock() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock?.release()
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vicidial_sip").also { it.acquire() }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        wifiLock?.release()
        wifiLock = null
    }

    private fun emit(s: State, msg: String) {
        state = s
        onStateChanged?.invoke(s, msg)
    }
}
