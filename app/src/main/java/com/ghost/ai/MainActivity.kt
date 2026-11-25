package com.ghost.ai

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.ghost.ai.R

/**
 * Plain framework Activity: no AppCompat / AndroidX dependency required.
 */
class MainActivity : Activity() {

    private lateinit var etApiKey: EditText
    private lateinit var tvToggleKey: TextView
    private lateinit var tvKeyStatus: TextView
    private lateinit var btnSaveKey: Button
    private lateinit var cardMic: View
    private lateinit var cardOverlay: View
    private lateinit var cardAccessibility: View
    private lateinit var btnActivate: Button
    private lateinit var btnStop: Button
    private lateinit var tvHint: TextView
    private lateinit var switchWake: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var keyVisible = false

    private val wakeToggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        GhostPrefs.setWakeEnabled(this, checked)
        if (checked && !hasMic()) {
            Toast.makeText(this, R.string.perm_mic_desc, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById<EditText>(R.id.etApiKey)
        tvToggleKey = findViewById<TextView>(R.id.tvToggleKey)
        tvKeyStatus = findViewById<TextView>(R.id.tvKeyStatus)
        btnSaveKey = findViewById<Button>(R.id.btnSaveKey)
        cardMic = findViewById<View>(R.id.cardMic)
        cardOverlay = findViewById<View>(R.id.cardOverlay)
        cardAccessibility = findViewById<View>(R.id.cardAccessibility)
        btnActivate = findViewById<Button>(R.id.btnActivate)
        btnStop = findViewById<Button>(R.id.btnStop)
        tvHint = findViewById<TextView>(R.id.tvHint)
        switchWake = findViewById<Switch>(R.id.switchWake)

        tvToggleKey.setOnClickListener { toggleKeyVisibility() }
        btnSaveKey.setOnClickListener { saveKey() }
        etApiKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveKey()
                true
            } else {
                false
            }
        }
        switchWake.isChecked = GhostPrefs.isWakeEnabled(this)
        switchWake.setOnCheckedChangeListener(wakeToggleListener)
        btnActivate.setOnClickListener { onActivateClicked() }
        btnStop.setOnClickListener {
            GhostOverlayService.stop(this)
            handler.postDelayed({ refreshUi() }, 300L)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> {
                if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    // Denied with "don't ask again": only the system settings page can fix it.
                    Toast.makeText(this, "Enable Microphone in app settings", Toast.LENGTH_LONG).show()
                    openAppSettings()
                }
                refreshUi()
            }

            REQ_NOTIFICATIONS -> {
                // Ghost works without notifications; just continue to activate.
                startGhost()
            }
        }
    }

    // ------------------------------------------------------------------ API key

    private fun saveKey() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.key_empty_toast, Toast.LENGTH_SHORT).show()
            return
        }
        GhostPrefs.saveApiKey(this, key)
        etApiKey.setText("")
        etApiKey.clearFocus()
        hideKeyboard()
        Toast.makeText(this, R.string.key_saved_toast, Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        etApiKey.inputType = if (keyVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        etApiKey.setSelection(etApiKey.text.length)
        tvToggleKey.setText(if (keyVisible) R.string.btn_hide else R.string.btn_show)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etApiKey.windowToken, 0)
    }

    // ------------------------------------------------------------------ permissions

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun hasAccessibility(): Boolean {
        if (GhostAccessibilityService.isConnected()) return true
        val enabled: String = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, GhostAccessibilityService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val entry: String = splitter.next()
            if (ComponentName.unflattenFromString(entry) == expected) return true
        }
        return false
    }

    private fun requestMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun requestAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find \"Ghost AI\" and turn it on", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ activate

    private fun onActivateClicked() {
        // Accept a key that was typed but not saved yet.
        val typed = etApiKey.text.toString().trim()
        if (typed.isNotEmpty()) saveKey()

        if (!GhostPrefs.hasApiKey(this)) {
            Toast.makeText(this, R.string.hint_no_key, Toast.LENGTH_LONG).show()
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.hint_missing, missing.joinToString(", ")),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        startGhost()
    }

    private fun startGhost() {
        try {
            GhostOverlayService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start Ghost: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Ghost is active. Tap the GHOST pill and speak.", Toast.LENGTH_LONG).show()
        handler.postDelayed({ refreshUi() }, 600L)
        // Move out of the way so the pill floats over the home screen.
        handler.postDelayed({ moveTaskToBack(true) }, 700L)
    }

    private fun missingPermissions(): List<String> {
        val missing = ArrayList<String>(3)
        if (!hasMic()) missing.add(getString(R.string.perm_mic_title))
        if (!hasOverlay()) missing.add(getString(R.string.perm_overlay_title))
        if (!hasAccessibility()) missing.add(getString(R.string.perm_acc_title))
        return missing
    }

    // ------------------------------------------------------------------ UI state

    private fun refreshUi() {
        val wakeOn = GhostPrefs.isWakeEnabled(this)
        if (switchWake.isChecked != wakeOn) {
            switchWake.setOnCheckedChangeListener(null)
            switchWake.isChecked = wakeOn
            switchWake.setOnCheckedChangeListener(wakeToggleListener)
        }
        val key = GhostPrefs.getApiKey(this)
        val hasKey = key.isNotBlank()
        tvKeyStatus.text = if (hasKey) {
            getString(R.string.key_status_saved, GhostPrefs.maskedKey(key))
        } else {
            getString(R.string.key_status_none)
        }
        tvKeyStatus.setTextColor(getColor(if (hasKey) R.color.ghost_cyan else R.color.ghost_text_dim))

        bindCard(cardMic, R.string.perm_mic_title, R.string.perm_mic_desc, hasMic()) { requestMic() }
        bindCard(cardOverlay, R.string.perm_overlay_title, R.string.perm_overlay_desc, hasOverlay()) {
            requestOverlay()
        }
        bindCard(
            cardAccessibility,
            R.string.perm_acc_title,
            R.string.perm_acc_desc,
            hasAccessibility()
        ) { requestAccessibility() }

        val missing = missingPermissions()
        val ready = hasKey && missing.isEmpty()
        btnActivate.alpha = if (ready) 1f else 0.45f

        tvHint.text = when {
            !hasKey -> getString(R.string.hint_no_key)
            missing.isNotEmpty() -> getString(R.string.hint_missing, missing.joinToString(", "))
            else -> getString(R.string.hint_ready)
        }
        tvHint.setTextColor(getColor(if (ready) R.color.ghost_cyan else R.color.ghost_text_dim))

        btnStop.visibility = if (GhostOverlayService.running) View.VISIBLE else View.GONE
    }

    private fun bindCard(card: View, title: Int, desc: Int, granted: Boolean, onEnable: () -> Unit) {
        card.findViewById<TextView>(R.id.permTitle).setText(title)
        card.findViewById<TextView>(R.id.permDesc).setText(desc)
        card.findViewById<View>(R.id.permDot)
            .setBackgroundResource(if (granted) R.drawable.dot_on else R.drawable.dot_off)
        card.setBackgroundResource(if (granted) R.drawable.bg_card_ok else R.drawable.bg_card_pending)

        val button = card.findViewById<Button>(R.id.permButton)
        if (granted) {
            button.setText(R.string.btn_granted)
            button.setBackgroundResource(R.drawable.bg_btn_granted)
            button.setTextColor(getColor(R.color.ghost_cyan))
            button.isEnabled = false
            button.setOnClickListener(null)
        } else {
            button.setText(R.string.btn_enable)
            button.setBackgroundResource(R.drawable.bg_btn_enable)
            button.setTextColor(getColor(R.color.ghost_bg))
            button.isEnabled = true
            button.setOnClickListener { onEnable() }
        }
    }

    companion object {
        private const val REQ_MIC = 101
        private const val REQ_NOTIFICATIONS = 102
    }
}
