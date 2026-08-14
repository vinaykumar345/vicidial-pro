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
    data class UiAuthResult(val ok: Boolean, val message: String, val fullName: String, val userLevel: Int)
    data class ActiveLead(
        val leadId: Int,
        val fullName: String,
        val phoneNumber: String,
        val altPhone: String,
        val address1: String,
        val address2: String,
        val address3: String,
        val city: String,
        val state: String,
        val postalCode: String,
        val vendorLeadCode: String,
        val sourceId: String,
        val comments: String,
        val agentStatus: String
    )
    data class LiveSession(
        val agentUser: String,
        val fullName: String,
        val campaignId: String,
        val serverIp: String,
        val sessionId: String,
        val status: String
    )

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

    fun authenticateUiUser(username: String, password: String, panel: String): UiAuthResult {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/ui-auth",
            apiKey = apiKey,
            payload = mapOf(
                "username" to username,
                "password" to password,
                "panel" to panel
            )
        )

        return try {
            val root = JSONObject(raw)
            UiAuthResult(
                ok = root.optBoolean("ok", false),
                message = root.optString("message").ifBlank { if (root.optBoolean("ok", false)) "Authenticated" else "Authentication failed" },
                fullName = root.optString("fullName"),
                userLevel = root.optInt("userLevel", 0)
            )
        } catch (_: Throwable) {
            UiAuthResult(ok = false, message = raw, fullName = "", userLevel = 0)
        }
    }

    fun discoverLiveSessions(adminUser: String, adminPassword: String): Pair<List<LiveSession>, String> {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/live-sessions",
            apiKey = apiKey,
            payload = mapOf(
                "adminUser" to adminUser,
                "adminPassword" to adminPassword,
                "agentUser" to "",
                "limit" to "10"
            )
        )

        return try {
            val root = JSONObject(raw)
            val sessions = mutableListOf<LiveSession>()
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                sessions.add(
                    LiveSession(
                        agentUser = item.optString("agentUser").trim(),
                        fullName = item.optString("fullName").trim(),
                        campaignId = item.optString("campaignId").trim(),
                        serverIp = item.optString("serverIp").trim(),
                        sessionId = item.optString("sessionId").trim(),
                        status = item.optString("status").trim()
                    )
                )
            }
            sessions to root.optString("message").ifBlank { if (sessions.isEmpty()) "No live sessions found" else "Live sessions loaded" }
        } catch (_: Throwable) {
            emptyList<LiveSession>() to raw
        }
    }

    fun fetchActiveLead(agentUser: String): Pair<ActiveLead?, String> {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/agent-active-lead",
            apiKey = apiKey,
            payload = mapOf("agentUser" to agentUser)
        )

        return try {
            val root = JSONObject(raw)
            val lead = root.optJSONObject("lead")
            if (!root.optBoolean("ok", false) || lead == null) {
                null to root.optString("message").ifBlank { "No active customer found" }
            } else {
                ActiveLead(
                    leadId = lead.optInt("leadId", 0),
                    fullName = lead.optString("fullName").trim(),
                    phoneNumber = lead.optString("phoneNumber").trim(),
                    altPhone = lead.optString("altPhone").trim(),
                    address1 = lead.optString("address1").trim(),
                    address2 = lead.optString("address2").trim(),
                    address3 = lead.optString("address3").trim(),
                    city = lead.optString("city").trim(),
                    state = lead.optString("state").trim(),
                    postalCode = lead.optString("postalCode").trim(),
                    vendorLeadCode = lead.optString("vendorLeadCode").trim(),
                    sourceId = lead.optString("sourceId").trim(),
                    comments = lead.optString("comments").trim(),
                    agentStatus = lead.optString("agentStatus").trim()
                ) to root.optString("message").ifBlank { "Active customer loaded" }
            }
        } catch (_: Throwable) {
            null to raw
        }
    }

    fun performAgentAction(agentUser: String, action: String, value: String = ""): String {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/agent-action",
            apiKey = apiKey,
            payload = mapOf(
                "agentUser" to agentUser,
                "action" to action,
                "value" to value
            )
        )

        return try {
            val root = JSONObject(raw)
            root.optJSONObject("upstream")?.optString("body")?.ifBlank { raw } ?: root.optString("message").ifBlank { raw }
        } catch (_: Throwable) {
            raw
        }
    }

    fun sendDtmf(agentUser: String, digits: String): String {
        return performAgentAction(agentUser = agentUser, action = "dtmf", value = digits)
    }

    fun parkCall(agentUser: String, action: String): String {
        return performAgentAction(agentUser = agentUser, action = action)
    }

    fun transferConference(
        agentUser: String,
        action: String,
        phoneNumber: String = "",
        ingroupChoices: String = "",
        consultative: Boolean = false,
        dialOverride: Boolean = false
    ): String {
        val raw = callJsonApi(
            baseUrl = baseUrl,
            path = "/api/agent-action",
            apiKey = apiKey,
            payload = mapOf(
                "agentUser" to agentUser,
                "action" to action,
                "phoneNumber" to phoneNumber,
                "ingroupChoices" to ingroupChoices,
                "consultative" to if (consultative) "YES" else "NO",
                "dialOverride" to if (dialOverride) "YES" else "NO"
            )
        )

        return try {
            val root = JSONObject(raw)
            root.optJSONObject("upstream")?.optString("body")?.ifBlank { raw } ?: root.optString("message").ifBlank { raw }
        } catch (_: Throwable) {
            raw
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
        return try {
            val endpoint = baseUrl.trimEnd('/') + path
            val json = JSONObject(payload as Map<*, *>).toString()
            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()?.trim().orEmpty()
                if (!response.isSuccessful) "HTTP ${response.code}: $text"
                else text.ifBlank { "No response body" }
            }
        } catch (e: Throwable) {
            "{\"ok\":false,\"message\":\"Network error: ${e.message?.replace("\"", "'")}\"}" 
        }
    }
}
