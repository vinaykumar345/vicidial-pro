package com.vicidial.simagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VicidialApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl = BuildConfig.CONTROL_API_BASE_URL
    private val apiKey = BuildConfig.CONTROL_API_KEY

    data class CampaignOption(val id: String, val name: String)
    data class AgentLoginResult(val ok: Boolean, val message: String)

    fun fetchAssignedCampaigns(agentUser: String, agentPassword: String): Pair<List<CampaignOption>, String> {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/agent-campaigns",
            apiKey = apiKey,
            payload = mapOf(
                "agentUser" to agentUser,
                "agentPassword" to agentPassword
            )
        )

        return try {
            val root = JSONObject(raw)
            val campaigns = mutableListOf<CampaignOption>()
            val arr = root.optJSONArray("campaigns") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isNotBlank()) {
                    campaigns.add(CampaignOption(id = id, name = if (name.isBlank()) id else name))
                }
            }
            val message = root.optString("message").ifBlank { "Campaigns loaded" }
            campaigns to message
        } catch (_: Throwable) {
            emptyList<CampaignOption>() to raw
        }
    }

    fun loginAgent(agentUser: String, agentPassword: String, campaignId: String): AgentLoginResult {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/agent-login",
            apiKey = apiKey,
            payload = mapOf(
                "agentUser" to agentUser,
                "agentPassword" to agentPassword,
                "campaignId" to campaignId
            )
        )

        return try {
            val root = JSONObject(raw)
            val ok = root.optBoolean("ok", false)
            val message = root.optString("message").ifBlank {
                if (ok) "Login successful" else "Login failed"
            }
            AgentLoginResult(ok = ok, message = message)
        } catch (_: Throwable) {
            AgentLoginResult(ok = false, message = raw)
        }
    }

    fun checkAgentInfo(agentUser: String): String {
        return callJsonApi(
            baseUrl = baseUrl,
            path = "/api/login-check",
            apiKey = apiKey,
            payload = mapOf("agentUser" to agentUser)
        )
    }

    fun externalDial(agentUser: String, phoneNumber: String): String {
        val normalized = phoneNumber.filter { it.isDigit() }
        return callJsonApi(
            baseUrl = baseUrl,
            path = "/api/external-dial",
            apiKey = apiKey,
            payload = mapOf(
                "agentUser" to agentUser,
                "phoneNumber" to normalized,
                "phoneCode" to "1"
            )
        )
    }

    fun blindMonitor(
        phoneLogin: String,
        sessionId: String,
        serverIp: String,
        stage: String
    ): String {
        val endpoint = if (stage.uppercase() == "BARGE") "/api/barge" else "/api/monitor"
        return callJsonApi(
            baseUrl = baseUrl,
            path = endpoint,
            apiKey = apiKey,
            payload = mapOf(
                "phoneLogin" to phoneLogin,
                "sessionId" to sessionId,
                "serverIp" to serverIp
            )
        )
    }

    private fun callJsonApi(
        baseUrl: String,
        path: String,
        apiKey: String,
        payload: Map<String, String>
    ): String {
        val endpoint = baseUrl.trimEnd('/') + path
        val json = JSONObject(payload as Map<*, *>).toString()

        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("X-Api-Key", apiKey)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                return "HTTP ${response.code}: $text"
            }
            text.ifBlank { "No response body" }
        }
    }
}
