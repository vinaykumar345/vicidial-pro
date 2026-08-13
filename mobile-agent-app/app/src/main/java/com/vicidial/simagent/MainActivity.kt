package com.vicidial.simagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.vicidial.simagent.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var api: VicidialApi

    private var pendingDialNumber: String? = null
    private val auditLines = mutableListOf<String>()
    private val campaignIds = mutableListOf<String>()
    private var autoLoadJob: Job? = null
    private var lastCampaignFetchKey: String = ""

    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingDialNumber?.let { performSimCall(it) }
            } else {
                toast("Call permission denied")
            }
            pendingDialNumber = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        api = VicidialApi()
        setupCampaignSpinner()
        loadSaved()

        binding.buttonSave.setOnClickListener {
            saveInputs()
            toast("Saved")
        }

        binding.buttonVerifyLogin.setOnClickListener {
            saveInputs()
            loginToVicidial()
        }

        binding.editAgentUser.doAfterTextChanged {
            scheduleAutoCampaignLoad()
        }
        binding.editAgentPassword.doAfterTextChanged {
            scheduleAutoCampaignLoad()
        }

        binding.buttonCallFromSim.setOnClickListener {
            val number = binding.editDialNumber.text.toString().trim()
            if (number.isBlank()) {
                toast("Enter number to call")
                return@setOnClickListener
            }
            dialUsingSim(number)
        }

        binding.buttonVicidialDial.setOnClickListener {
            saveInputs()
            externalDialOnVicidial()
        }

        binding.buttonMonitor.setOnClickListener {
            saveInputs()
            supervisorControl(stage = "MONITOR")
        }

        binding.buttonBarge.setOnClickListener {
            saveInputs()
            supervisorControl(stage = "BARGE")
        }

        binding.checkSupervisorMode.setOnCheckedChangeListener { _, enabled ->
            updateSupervisorControls(enabled)
        }
    }

    private fun setupCampaignSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("Select campaign"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCampaign.adapter = adapter
    }

    private fun scheduleAutoCampaignLoad() {
        autoLoadJob?.cancel()

        val user = binding.editAgentUser.text.toString().trim()
        val pass = binding.editAgentPassword.text.toString().trim()

        if (user.isBlank() || pass.isBlank()) {
            campaignIds.clear()
            setupCampaignSpinner()
            return
        }

        val key = "$user::$pass"
        if (key == lastCampaignFetchKey) {
            return
        }

        autoLoadJob = lifecycleScope.launch {
            delay(500)
            loadAssignedCampaigns(selectSavedCampaign = true, showMissingToast = false)
        }
    }

    private fun loadSaved() {
        val c = prefs.load()
        binding.editAgentUser.setText(c.agentUser)
        binding.editAgentPassword.setText(c.agentPassword)
        binding.checkSupervisorMode.isChecked = c.supervisorMode
        binding.editPhoneLogin.setText(c.phoneLogin)
        binding.editSessionId.setText(c.sessionId)
        binding.editMonitorServerIp.setText(c.monitorServerIp)
        updateSupervisorControls(c.supervisorMode)
        binding.textAudit.text = "Audit log:"

        if (c.agentUser.isNotBlank() && c.agentPassword.isNotBlank()) {
            loadAssignedCampaigns(selectSavedCampaign = true, showMissingToast = false)
        }
    }

    private fun saveInputs() {
        prefs.save(
            AgentConfig(
                agentUser = binding.editAgentUser.text.toString().trim(),
                agentPassword = binding.editAgentPassword.text.toString().trim(),
                selectedCampaign = getSelectedCampaignId(),
                supervisorMode = binding.checkSupervisorMode.isChecked,
                phoneLogin = binding.editPhoneLogin.text.toString().trim(),
                sessionId = binding.editSessionId.text.toString().trim(),
                monitorServerIp = binding.editMonitorServerIp.text.toString().trim()
            )
        )
    }

    private fun getSelectedCampaignId(): String {
        val selectedIndex = binding.spinnerCampaign.selectedItemPosition - 1
        return if (selectedIndex in campaignIds.indices) campaignIds[selectedIndex] else ""
    }

    private fun loadAssignedCampaigns(selectSavedCampaign: Boolean, showMissingToast: Boolean = true) {
        val enteredUser = binding.editAgentUser.text.toString().trim()
        val enteredPassword = binding.editAgentPassword.text.toString().trim()
        val savedConfig = prefs.load()

        if (enteredUser.isBlank() || enteredPassword.isBlank()) {
            if (showMissingToast) {
                toast("Enter agent user ID and password")
            }
            return
        }

        val currentKey = "$enteredUser::$enteredPassword"

        lifecycleScope.launch {
            setBusy(true)
            val (campaigns, message) = withContext(Dispatchers.IO) {
                api.fetchAssignedCampaigns(
                    agentUser = enteredUser,
                    agentPassword = enteredPassword
                )
            }
            setBusy(false)

            if (campaigns.isEmpty()) {
                lastCampaignFetchKey = ""
                binding.textResult.text = message
                addAudit("load_campaigns", "no campaigns: $message")
                if (showMissingToast) {
                    toast("No assigned campaigns found")
                }
                return@launch
            }

            lastCampaignFetchKey = currentKey

            campaignIds.clear()
            campaignIds.addAll(campaigns.map { it.id })

            val labels = mutableListOf("Select campaign")
            labels.addAll(campaigns.map { "${it.id} - ${it.name}" })

            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCampaign.adapter = adapter

            if (campaignIds.size == 1) {
                binding.spinnerCampaign.setSelection(1)
            } else if (selectSavedCampaign && savedConfig.selectedCampaign.isNotBlank()) {
                val idx = campaignIds.indexOf(savedConfig.selectedCampaign)
                if (idx >= 0) {
                    binding.spinnerCampaign.setSelection(idx + 1)
                }
            }

            binding.textResult.text = message
            addAudit("load_campaigns", message)
        }
    }

    private fun updateSupervisorControls(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.editPhoneLogin.visibility = visibility
        binding.editSessionId.visibility = visibility
        binding.editMonitorServerIp.visibility = visibility
        binding.buttonMonitor.visibility = visibility
        binding.buttonBarge.visibility = visibility
    }

    private fun loginToVicidial() {
        val c = prefs.load()
        val campaignId = getSelectedCampaignId()

        if (c.agentUser.isBlank() || c.agentPassword.isBlank()) {
            toast("Enter agent user ID and password")
            return
        }
        if (campaignId.isBlank()) {
            toast("Select an assigned campaign")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                api.loginAgent(
                    agentUser = c.agentUser,
                    agentPassword = c.agentPassword,
                    campaignId = campaignId
                )
            }
            setBusy(false)

            val finalMessage = if (result.ok) {
                "Connected to Vicidial. ${result.message}"
            } else {
                "Login failed: ${result.message}"
            }

            binding.textResult.text = finalMessage
            addAudit("agent_login", finalMessage)

            if (result.ok) {
                saveInputs()
            }
        }
    }

    private fun externalDialOnVicidial() {
        val c = prefs.load()
        val number = binding.editDialNumber.text.toString().trim()
        if (number.isBlank()) {
            toast("Enter number to dial")
            return
        }
        if (c.agentUser.isBlank()) {
            toast("Enter agent user ID first")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                api.externalDial(
                    agentUser = c.agentUser,
                    phoneNumber = number
                )
            }
            setBusy(false)
            binding.textResult.text = result
            addAudit("external_dial", result)
        }
    }

    private fun supervisorControl(stage: String) {
        val c = prefs.load()
        if (!c.supervisorMode) {
            toast("Enable Supervisor Mode first")
            addAudit(stage.lowercase(Locale.US), "blocked: supervisor mode disabled")
            return
        }
        if (c.phoneLogin.isBlank() || c.sessionId.isBlank() || c.monitorServerIp.isBlank()) {
            toast("Fill phone_login, session_id, and server_ip")
            addAudit(stage.lowercase(Locale.US), "blocked: missing monitor fields")
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
            binding.textResult.text = result
            val lowered = result.lowercase(Locale.US)
            val auditResult = if (lowered.contains("permission") || lowered.startsWith("error")) {
                "denied_or_error: $result"
            } else {
                result
            }
            addAudit(stage.lowercase(Locale.US), auditResult)
        }
    }

    private fun dialUsingSim(number: String) {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED -> {
                performSimCall(number)
            }
            else -> {
                pendingDialNumber = number
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
        }
    }

    private fun performSimCall(number: String) {
        val normalized = number.filter { it.isDigit() || it == '+' }
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$normalized")
        }
        startActivity(callIntent)
    }

    private fun setBusy(busy: Boolean) {
        binding.buttonVerifyLogin.isEnabled = !busy
        binding.buttonVicidialDial.isEnabled = !busy
        binding.buttonSave.isEnabled = !busy
        binding.buttonCallFromSim.isEnabled = !busy
        binding.buttonMonitor.isEnabled = !busy
        binding.buttonBarge.isEnabled = !busy
    }

    private fun addAudit(action: String, result: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        auditLines.add(0, "$ts | $action | $result")
        if (auditLines.size > 20) {
            auditLines.removeLast()
        }
        val joined = auditLines.joinToString(separator = "\n")
        binding.textAudit.text = "Audit log:\n$joined"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
