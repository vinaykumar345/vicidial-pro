package com.vicidial.simagent

import android.content.Context

data class AgentConfig(
    val agentUser: String = "",
    val agentPassword: String = "",
    val selectedCampaign: String = "",
    val supervisorMode: Boolean = false,
    val phoneLogin: String = "",
    val sessionId: String = "",
    val monitorServerIp: String = ""
)

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("vicidial_agent_prefs", Context.MODE_PRIVATE)

    fun save(config: AgentConfig) {
        prefs.edit()
            .putString("agentUser", config.agentUser)
            .putString("agentPassword", config.agentPassword)
            .putString("selectedCampaign", config.selectedCampaign)
            .putBoolean("supervisorMode", config.supervisorMode)
            .putString("phoneLogin", config.phoneLogin)
            .putString("sessionId", config.sessionId)
            .putString("monitorServerIp", config.monitorServerIp)
            .apply()
    }

    fun load(): AgentConfig {
        return AgentConfig(
            agentUser = prefs.getString("agentUser", "") ?: "",
            agentPassword = prefs.getString("agentPassword", "") ?: "",
            selectedCampaign = prefs.getString("selectedCampaign", "") ?: "",
            supervisorMode = prefs.getBoolean("supervisorMode", false),
            phoneLogin = prefs.getString("phoneLogin", "") ?: "",
            sessionId = prefs.getString("sessionId", "") ?: "",
            monitorServerIp = prefs.getString("monitorServerIp", "") ?: ""
        )
    }
}
