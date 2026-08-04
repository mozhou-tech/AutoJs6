package org.autojs.autojs.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.gson.JsonParser
import org.autojs.autojs.theme.ThemeColorManager
import org.autojs.autojs.ui.BaseActivity
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.ActivityPreferencesBinding
import java.net.URI

class PhoneMcpSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityPreferencesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreferencesBinding.inflate(layoutInflater).also {
            setContentView(it.root)
            it.toolbar.apply {
                setTitle(R.string.phone_mcp_title)
                setSupportActionBar(this)
                setNavigationOnClickListener { finish() }
            }
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_preferences, SettingsFragment())
            .disallowAddToBackStack()
            .commit()
    }

    override fun onStart() {
        super.onStart()
        binding.toolbar.navigationIcon?.setTint(ThemeColorManager.getDayOrNightColorByLuminance(this))
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val handler = Handler(Looper.getMainLooper())
        private val refresh = object : Runnable {
            override fun run() {
                refreshStatus()
                handler.postDelayed(this, 1000)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            PhoneMcpPreferences.ensureDefaults(requireContext())
            setPreferencesFromResource(R.xml.fragment_phone_mcp_settings, rootKey)

            val enabled = findPreference<SwitchPreference>(PhoneMcpPreferences.KEY_ENABLED)!!
            val localOnly = findPreference<SwitchPreference>(PhoneMcpPreferences.KEY_LOCAL_ONLY)!!
            val controlUrl = findPreference<EditTextPreference>(PhoneMcpPreferences.KEY_CONTROL_URL)!!
            val authKey = findPreference<EditTextPreference>(PhoneMcpPreferences.KEY_AUTH_KEY)!!
            val hostname = findPreference<EditTextPreference>(PhoneMcpPreferences.KEY_HOSTNAME)!!
            val tailnetPort = findPreference<EditTextPreference>(PhoneMcpPreferences.KEY_TAILNET_PORT)!!
            val localPort = findPreference<EditTextPreference>(PhoneMcpPreferences.KEY_LOCAL_PORT)!!

            controlUrl.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            hostname.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            tailnetPort.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            localPort.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            authKey.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            authKey.text = null
            tailnetPort.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            localPort.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }

            enabled.setOnPreferenceChangeListener { _, newValue ->
                val turnOn = newValue == true
                if (turnOn) {
                    val validation = validateSettings(localOnly.isChecked, controlUrl.text, tailnetPort.text, localPort.text)
                    if (validation != null) {
                        Toast.makeText(requireContext(), validation, Toast.LENGTH_LONG).show()
                        false
                    } else {
                        handler.post { PhoneMcpService.start(requireContext()) }
                        true
                    }
                } else {
                    PhoneMcpService.stop(requireContext())
                    true
                }
            }

            authKey.setOnPreferenceChangeListener { _, newValue ->
                PhoneMcpPreferences.setAuthKey(requireContext(), newValue?.toString().orEmpty())
                handler.postDelayed(::restartIfEnabled, 200)
                false
            }

            listOf<Preference>(localOnly, controlUrl, hostname, tailnetPort, localPort).forEach { preference ->
                preference.setOnPreferenceChangeListener { _, _ ->
                    handler.postDelayed(::restartIfEnabled, 200)
                    true
                }
            }

            findPreference<Preference>("phone_mcp_pairing_token")?.setOnPreferenceClickListener {
                val token = PhoneMcpPreferences.pairingToken(requireContext())
                requireContext().getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Phone MCP pairing token", token))
                Toast.makeText(requireContext(), R.string.phone_mcp_token_copied, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("phone_mcp_regenerate_token")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.phone_mcp_regenerate_token)
                    .setMessage(R.string.phone_mcp_regenerate_token_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        PhoneMcpPreferences.regeneratePairingToken(requireContext())
                        restartIfEnabled()
                    }
                    .show()
                true
            }
            findPreference<Preference>("phone_mcp_reset_identity")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.phone_mcp_reset_identity)
                    .setMessage(R.string.phone_mcp_reset_identity_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val appContext = requireContext().applicationContext
                        PhoneMcpPreferences.setEnabled(appContext, false)
                        findPreference<SwitchPreference>(PhoneMcpPreferences.KEY_ENABLED)?.isChecked = false
                        PhoneMcpService.stop(appContext)
                        handler.postDelayed({
                            val cleared = PhoneTailnetStateStore.clear(appContext)
                            Toast.makeText(
                                appContext,
                                if (cleared) R.string.phone_mcp_reset_identity_done else R.string.phone_mcp_reset_identity_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }, 750)
                    }
                    .show()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            handler.post(refresh)
        }

        override fun onPause() {
            handler.removeCallbacks(refresh)
            super.onPause()
        }

        private fun restartIfEnabled() {
            if (!PhoneMcpPreferences.isEnabled(requireContext())) return
            PhoneMcpService.stop(requireContext())
            handler.postDelayed({ PhoneMcpService.start(requireContext()) }, 500)
        }

        private fun refreshStatus() {
            val statusPreference = findPreference<Preference>("phone_mcp_status") ?: return
            val status = runCatching { JsonParser.parseString(PhoneMcpRuntime.statusJson).asJsonObject }.getOrNull()
            val ip = status?.getAsJsonArray("tailnet_ips")?.joinToString { it.asString }.orEmpty()
            val bridgeRunning = status?.get("running")?.asBoolean == true
            val error = PhoneMcpRuntime.lastError
            statusPreference.summary = when {
                error != null -> getString(R.string.phone_mcp_status_error, error)
                bridgeRunning && PhoneMcpPreferences.localOnly(requireContext()) -> getString(
                    R.string.phone_mcp_status_local,
                    PhoneMcpPreferences.localPort(requireContext()),
                )
                PhoneMcpRuntime.running && ip.isNotBlank() -> getString(R.string.phone_mcp_status_online, ip, PhoneMcpPreferences.tailnetPort(requireContext()))
                PhoneMcpRuntime.running -> getString(R.string.phone_mcp_status_starting)
                else -> getString(R.string.phone_mcp_status_stopped)
            }
            findPreference<Preference>("phone_mcp_pairing_token")?.summary = getString(
                R.string.phone_mcp_token_summary,
                PhoneMcpPreferences.pairingToken(requireContext()).takeLast(6),
            )
        }

        private fun validateSettings(localOnly: Boolean, rawServer: String?, rawTailnetPort: String?, rawLocalPort: String?): String? {
            if (!localOnly) {
                val server = runCatching { URI(rawServer?.trim().orEmpty()) }.getOrNull()
                if (server == null || server.scheme != "https" || server.host.isNullOrBlank() ||
                    !server.userInfo.isNullOrBlank() || !server.query.isNullOrBlank() || !server.fragment.isNullOrBlank() ||
                    (server.path.orEmpty().isNotBlank() && server.path != "/")
                ) return getString(R.string.phone_mcp_error_invalid_server)
            }
            if (rawTailnetPort?.toIntOrNull() !in 1..65535 || rawLocalPort?.toIntOrNull() !in 1..65535) {
                return getString(R.string.phone_mcp_error_invalid_port)
            }
            return null
        }
    }
}
