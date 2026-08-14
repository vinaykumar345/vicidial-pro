package com.vicidial.simagent

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.vicidial.simagent.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: AppPrefs
    private lateinit var api: VicidialApi

    private val campaignIds = mutableListOf<String>()
    private var autoLoadJob: Job? = null
    private var lastCampaignFetchKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
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
            authenticateAndRoute()
        }

        binding.editAgentUser.doAfterTextChanged { scheduleAutoCampaignLoad() }
        binding.editAgentPassword.doAfterTextChanged { scheduleAutoCampaignLoad() }
    }

    private fun setupCampaignSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("Select campaign"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCampaign.adapter = adapter
    }

    private fun loadSaved() {
        val c = prefs.load()
        binding.editAgentUser.setText(c.agentUser)
        binding.editAgentPassword.setText(c.agentPassword)
        if (c.agentUser.isNotBlank() && c.agentPassword.isNotBlank()) {
            loadAssignedCampaigns(selectSavedCampaign = true, showMissingToast = false)
        }
    }

    private fun saveInputs() {
        val current = prefs.load()
        prefs.save(
            current.copy(
                agentUser = binding.editAgentUser.text.toString().trim(),
                agentPassword = binding.editAgentPassword.text.toString().trim(),
                selectedCampaign = getSelectedCampaignId()
            )
        )
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

    private fun getSelectedCampaignId(): String {
        val selectedIndex = binding.spinnerCampaign.selectedItemPosition - 1
        return if (selectedIndex in campaignIds.indices) campaignIds[selectedIndex] else ""
    }

    private fun loadAssignedCampaigns(selectSavedCampaign: Boolean, showMissingToast: Boolean = true) {
        val enteredUser = binding.editAgentUser.text.toString().trim()
        val enteredPassword = binding.editAgentPassword.text.toString().trim()
        val savedConfig = prefs.load()

        if (enteredUser.isBlank() || enteredPassword.isBlank()) {
            if (showMissingToast) toast("Enter agent user ID and password")
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
                if (showMissingToast) toast(message)
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

            if (showMissingToast) toast(message)
        }
    }

    private fun authenticateAndRoute() {
        val config = prefs.load()
        if (config.agentUser.isBlank() || config.agentPassword.isBlank()) {
            toast("Enter agent user ID and password")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            val supervisorAuth = withContext(Dispatchers.IO) {
                api.authenticateUiUser(
                    username = config.agentUser,
                    password = config.agentPassword,
                    panel = "admin"
                )
            }

            if (supervisorAuth.ok && supervisorAuth.userLevel >= 8) {
                setBusy(false)
                openConsole(supervisorMode = true)
                return@launch
            }

            val campaignId = getSelectedCampaignId()
            if (campaignId.isBlank()) {
                setBusy(false)
                toast("Select an assigned campaign")
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                api.loginAgent(
                    agentUser = config.agentUser,
                    agentPassword = config.agentPassword,
                    campaignId = campaignId
                )
            }
            setBusy(false)

            if (result.ok) {
                saveInputs()
                openConsole(supervisorMode = false)
            } else {
                toast("Login failed: ${result.message}")
            }
        }
    }

    private fun openConsole(supervisorMode: Boolean) {
        startActivity(Intent(this, AgentConsoleActivity::class.java).apply {
            putExtra(AgentConsoleActivity.EXTRA_SUPERVISOR_MODE, supervisorMode)
        })
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.buttonVerifyLogin.isEnabled = !busy
        binding.buttonSave.isEnabled = !busy
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}