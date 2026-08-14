package com.vicidial.simagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vicidial.simagent.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentConsoleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var api: VicidialApi
    private lateinit var sipPhone: SipPhone

    private val RECORD_AUDIO_PERMISSION = 101

    private var leadRefreshJob: Job? = null
    private var supervisorUnlocked = false
    private var agentLoggedIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        api = VicidialApi()
        sipPhone = SipPhone(this)

        binding.buttonExitSession.setOnClickListener {
            exitToLogin()
        }

        binding.buttonMonitor.setOnClickListener {
            saveSupervisorInputs()
            supervisorControl(stage = "MONITOR")
        }

        binding.buttonBarge.setOnClickListener {
            saveSupervisorInputs()
            supervisorControl(stage = "BARGE")
        }

        binding.buttonRefreshCustomer.setOnClickListener {
            val user = prefs.load().agentUser
            if (user.isBlank()) {
                toast("Login as agent first")
            } else {
                loadActiveLead(user, notifyIfMissing = true)
            }
        }

        binding.buttonPauseAgent.setOnClickListener { performAgentAction("pause") }
        binding.buttonResumeAgent.setOnClickListener { performAgentAction("resume") }
        binding.buttonHangupAgent.setOnClickListener { performAgentAction("hangup") }
        binding.buttonSubmitDisposition.setOnClickListener {
            val dispo = binding.editDisposition.text.toString().trim()
            if (dispo.isBlank()) {
                toast("Enter disposition code")
            } else {
                performAgentAction("dispo", dispo)
            }
        }
        binding.buttonLogoutAgent.setOnClickListener { performAgentAction("logout") }

        binding.buttonSendDtmf.setOnClickListener {
            val digits = binding.editDtmfDigits.text.toString().trim()
            if (digits.isBlank()) toast("Enter DTMF digits") else performDtmfAction(digits)
        }
        binding.buttonParkCustomer.setOnClickListener { performParkAction("park") }
        binding.buttonGrabCustomer.setOnClickListener { performParkAction("grab") }
        binding.buttonBlindTransfer.setOnClickListener { performTransferAction("blind_transfer") }
        binding.buttonDialWithCustomer.setOnClickListener { performTransferAction("dial_with_customer") }
        binding.buttonLocalCloser.setOnClickListener { performTransferAction("local_closer") }
        binding.buttonLeaveVoicemail.setOnClickListener { performTransferAction("leave_vm") }
        binding.buttonLeave3Way.setOnClickListener { performTransferAction("leave_3way") }

        val supervisorMode = intent.getBooleanExtra(EXTRA_SUPERVISOR_MODE, false)
        val config = prefs.load()

        if (supervisorMode) {
            binding.textWorkspaceSubtitle.text = "Supervisor workspace for monitor and barge controls"
            setAgentWorkspaceVisible(false)
            setSupervisorControlsVisible(true)
            unlockSupervisorControls(config)
        } else {
            binding.textWorkspaceSubtitle.text = "Agent workspace for customer details and live Vicidial controls"
            setSupervisorControlsVisible(false)
            setAgentWorkspaceVisible(true)
            setupSipPhone()
            startLeadRefreshLoop(config.agentUser)
        }
    }

    override fun onDestroy() {
        sipPhone.unregister()
        leadRefreshJob?.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            registerSip()
        } else {
            toast("Microphone permission required for SIP calls")
        }
    }

    private fun setupSipPhone() {
        binding.cardSipPhone.visibility = View.VISIBLE

        sipPhone.onStateChanged = { state, message ->
            runOnUiThread {
                binding.textSipStatus.text = message
                val inCall = state == SipPhone.State.IN_CALL
                val registered = state == SipPhone.State.REGISTERED || inCall
                binding.buttonSipCall.isEnabled = registered && !inCall
                binding.buttonSipHangup.isEnabled = inCall
                binding.buttonSipMute.isEnabled = inCall
                binding.buttonSipRegister.isEnabled = state == SipPhone.State.IDLE || state == SipPhone.State.ERROR
                binding.buttonSipUnregister.isEnabled = registered
            }
        }

        binding.buttonSipRegister.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION)
            } else {
                registerSip()
            }
        }

        binding.buttonSipUnregister.setOnClickListener { sipPhone.unregister() }

        binding.buttonSipCall.setOnClickListener {
            val ext = binding.editSipDialNumber.text.toString().trim()
            if (ext.isBlank()) toast("Enter extension to dial") else sipPhone.makeCall(ext)
        }

        binding.buttonSipHangup.setOnClickListener { sipPhone.hangup() }

        binding.buttonSipMute.setOnClickListener {
            sipPhone.toggleMute()
            binding.buttonSipMute.text = if (sipPhone.isMuted()) "Unmute" else "Mute"
        }
    }

    private fun registerSip() {
        val config = prefs.load()
        val sipServer = BuildConfig.CONTROL_API_BASE_URL
            .removePrefix("http://").removePrefix("https://")
            .substringBefore(":")
        sipPhone.register(
            user = config.agentUser,
            password = config.agentPassword,
            server = sipServer
        )
    }

    private fun saveSupervisorInputs() {
        val current = prefs.load()
        prefs.save(
            current.copy(
                phoneLogin = binding.editPhoneLogin.text.toString().trim(),
                sessionId = binding.editSessionId.text.toString().trim(),
                monitorServerIp = binding.editMonitorServerIp.text.toString().trim()
            )
        )
    }

    private fun setSupervisorControlsVisible(enabled: Boolean) {
        binding.cardSupervisorControls.visibility = if (enabled) View.VISIBLE else View.GONE
        supervisorUnlocked = enabled
    }

    private fun setAgentWorkspaceVisible(enabled: Boolean) {
        binding.cardCustomerDetails.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.cardAgentFeatures.visibility = if (enabled) View.VISIBLE else View.GONE
        agentLoggedIn = enabled
        if (!enabled) {
            leadRefreshJob?.cancel()
            leadRefreshJob = null
        }
    }

    private fun unlockSupervisorControls(config: AgentConfig) {
        lifecycleScope.launch {
            setBusy(true)
            val (sessions, message) = withContext(Dispatchers.IO) {
                api.discoverLiveSessions(config.agentUser, config.agentPassword)
            }
            setBusy(false)

            val first = sessions.firstOrNull()
            if (first != null) {
                binding.editPhoneLogin.setText(first.agentUser)
                binding.editSessionId.setText(first.sessionId)
                binding.editMonitorServerIp.setText(first.serverIp)
                binding.textSupervisorHint.text = "Monitoring ${first.fullName.ifBlank { first.agentUser }} on ${first.campaignId.ifBlank { "active call" }}"
            } else {
                binding.textSupervisorHint.text = message
                toast(message)
            }
        }
    }

    private fun startLeadRefreshLoop(agentUser: String) {
        if (agentUser.isBlank()) {
            toast("Missing agent user")
            return
        }

        leadRefreshJob?.cancel()
        loadActiveLead(agentUser, notifyIfMissing = true)
        leadRefreshJob = lifecycleScope.launch {
            while (agentLoggedIn) {
                delay(5000)
                loadActiveLead(agentUser, notifyIfMissing = false)
            }
        }
    }

    private fun loadActiveLead(agentUser: String, notifyIfMissing: Boolean) {
        lifecycleScope.launch {
            setBusy(true)
            val (lead, message) = withContext(Dispatchers.IO) {
                api.fetchActiveLead(agentUser)
            }
            setBusy(false)

            if (lead == null) {
                binding.textCustomerName.text = "Waiting for active customer"
                binding.textCustomerMeta.text = "Agent logged in. Customer details will appear when a live lead is attached."
                binding.textCustomerAddress.text = "Address unavailable"
                binding.textCustomerComments.text = "Comments unavailable"
                if (notifyIfMissing) toast(message)
                return@launch
            }

            val meta = listOf(
                "Lead ${lead.leadId}",
                lead.phoneNumber.ifBlank { "No phone" },
                lead.agentStatus.ifBlank { "Unknown status" },
                lead.vendorLeadCode.ifBlank { "No vendor code" }
            ).joinToString(" • ")

            val address = listOf(
                lead.address1,
                lead.address2,
                lead.address3,
                listOf(lead.city, lead.state, lead.postalCode).filter { it.isNotBlank() }.joinToString(" ")
            ).filter { it.isNotBlank() }.joinToString("\n")

            binding.textCustomerName.text = lead.fullName.ifBlank { "Unnamed customer" }
            binding.textCustomerMeta.text = meta
            binding.textCustomerAddress.text = if (address.isBlank()) "Address unavailable" else address
            binding.textCustomerComments.text = if (lead.comments.isBlank()) "Comments unavailable" else lead.comments
        }
    }

    private fun performAgentAction(action: String, value: String = "") {
        val agentUser = prefs.load().agentUser
        if (!agentLoggedIn || agentUser.isBlank()) {
            toast("Login as agent first")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                api.performAgentAction(agentUser, action, value)
            }
            setBusy(false)
            toast(result)

            when (action) {
                "logout" -> exitToLogin()
                "hangup", "dispo", "resume", "pause" -> loadActiveLead(agentUser, notifyIfMissing = false)
            }
        }
    }

    private fun performDtmfAction(digits: String) {
        val agentUser = prefs.load().agentUser
        if (!agentLoggedIn || agentUser.isBlank()) {
            toast("Login as agent first")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) { api.sendDtmf(agentUser, digits) }
            setBusy(false)
            toast(result)
        }
    }

    private fun performParkAction(action: String) {
        val agentUser = prefs.load().agentUser
        if (!agentLoggedIn || agentUser.isBlank()) {
            toast("Login as agent first")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) { api.parkCall(agentUser, action) }
            setBusy(false)
            toast(result)
            loadActiveLead(agentUser, notifyIfMissing = false)
        }
    }

    private fun performTransferAction(action: String) {
        val agentUser = prefs.load().agentUser
        if (!agentLoggedIn || agentUser.isBlank()) {
            toast("Login as agent first")
            return
        }

        val phoneNumber = binding.editTransferNumber.text.toString().trim()
        val ingroup = binding.editTransferIngroup.text.toString().trim()
        val consultative = binding.checkConsultative.isChecked
        val dialOverride = binding.checkDialOverride.isChecked

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                api.transferConference(
                    agentUser = agentUser,
                    action = action,
                    phoneNumber = phoneNumber,
                    ingroupChoices = ingroup,
                    consultative = consultative,
                    dialOverride = dialOverride
                )
            }
            setBusy(false)
            toast(result)
            loadActiveLead(agentUser, notifyIfMissing = false)
        }
    }

    private fun supervisorControl(stage: String) {
        val c = prefs.load()
        if (!supervisorUnlocked) {
            toast("Login with supervisor credentials first")
            return
        }
        if (c.phoneLogin.isBlank() || c.sessionId.isBlank() || c.monitorServerIp.isBlank()) {
            toast("Fill phone_login, session_id, and server_ip")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                api.blindMonitor(
                    phoneLogin = c.phoneLogin,
                    sessionId = c.sessionId,
                    serverIp = c.monitorServerIp,
                    stage = stage
                )
            }
            setBusy(false)
            toast(result)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.buttonExitSession.isEnabled = !busy
        binding.buttonRefreshCustomer.isEnabled = !busy
        binding.buttonPauseAgent.isEnabled = !busy
        binding.buttonResumeAgent.isEnabled = !busy
        binding.buttonHangupAgent.isEnabled = !busy
        binding.buttonSubmitDisposition.isEnabled = !busy
        binding.buttonLogoutAgent.isEnabled = !busy
        binding.buttonSendDtmf.isEnabled = !busy
        binding.buttonParkCustomer.isEnabled = !busy
        binding.buttonGrabCustomer.isEnabled = !busy
        binding.buttonBlindTransfer.isEnabled = !busy
        binding.buttonDialWithCustomer.isEnabled = !busy
        binding.buttonLocalCloser.isEnabled = !busy
        binding.buttonLeaveVoicemail.isEnabled = !busy
        binding.buttonLeave3Way.isEnabled = !busy
        binding.buttonMonitor.isEnabled = !busy
        binding.buttonBarge.isEnabled = !busy
    }

    private fun exitToLogin() {
        leadRefreshJob?.cancel()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SUPERVISOR_MODE = "supervisor_mode"
    }
}