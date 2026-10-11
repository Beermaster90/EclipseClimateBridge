package fi.mitsukanny.phevbridge

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * The real, proven implementation: RC4 key-schedule direct-socket protocol (see
 * [Rc4CarClient] and `docs/MitsubishiEclipseCrossReverseEngineerWitifProtocol.md`).
 * Model: join the car's Wi-Fi once per command -- no persistent
 * session, no VIN/ping wait.
 *
 * Also the settings screen for call-triggered control: allowlisted numbers and the
 * default mode/duration a call triggers are managed here; the actual triggering
 * happens in [PhevCallScreeningService], sharing this activity's saved credentials
 * via [CarCredentials] and the same [CarController]/[Rc4CarClient] path a manual
 * button press uses -- a call behaves exactly like tapping the button would.
 */
class Rc4TestActivity : Activity() {
    private val LOCATION_PERMISSION_REQUEST = 100
    private val CALL_SCREENING_ROLE_REQUEST = 101
    private val BATTERY_EXEMPTION_REQUEST = 102
    private val FIX_ALL_PERMISSIONS_REQUEST = 103
    private val LOCATION_SETTINGS_REQUEST = 104

    private lateinit var ssidInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var rememberPasswordCheck: CheckBox
    private lateinit var connectButton: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var durationGroup: RadioGroup
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var statusView: TextView

    private lateinit var callTriggerEnabledCheck: CheckBox
    private lateinit var allowlistInput: EditText
    private lateinit var allowlistRows: LinearLayout
    private lateinit var requestRoleButton: Button
    private lateinit var requestBatteryExemptionButton: Button
    private lateinit var fixAllPermissionsButton: Button

    private lateinit var detailedLogCheck: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    /** Set once Wi-Fi credentials are confirmed usable (saved or freshly entered). */
    private var pendingCommand: (() -> Unit)? = null

    /** True while [fixAllPermissions]'s chain is stepping through each requirement in turn. */
    private var fixingAllPermissions = false

    /** True while [loadSavedFields] is programmatically restoring mode/duration selection. */
    private var restoringSelection = false

    private val logListener: (BridgeLog.Entry) -> Unit = { entry ->
        runOnUiThread { if (shouldShow(entry)) appendLogLine(entry) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeLog.attachFileLogging(applicationContext)
        setContentView(buildContentView())
        loadSavedFields()
        BridgeLog.addListener(logListener)
        renderLogFromScratch()
    }

    private fun buildContentView(): View {
        val padding = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(text: String) = TextView(this).also { it.text = text; it.setPadding(0, dp(16), 0, dp(4)) }
        fun field(hint: String) = EditText(this).apply { this.hint = hint; setSingleLine(true) }

        root.addView(label("PHEV bridge -- RC4 (proven)"))

        ssidInput = field("Car Wi-Fi SSID")
        root.addView(ssidInput, matchWrap())
        passwordInput = field("Wi-Fi password").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passwordInput, matchWrap())
        hostInput = field("Car host (default 192.168.8.46)")
        root.addView(hostInput, matchWrap())
        portInput = field("Port (default 8080)")
        root.addView(portInput, matchWrap())
        rememberPasswordCheck = CheckBox(this).apply {
            text = "Remember password securely on this phone"
            isChecked = true
        }
        root.addView(rememberPasswordCheck, matchWrap())
        connectButton = Button(this).apply {
            text = "Save credentials"
            setOnClickListener { onSaveCredentialsClicked() }
        }
        root.addView(connectButton, matchWrap())

        root.addView(label("Mode"))
        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(Rc4CarClient.Mode.HEAT, Rc4CarClient.Mode.COOL, Rc4CarClient.Mode.WINDSCREEN).forEach { mode ->
            modeGroup.addView(RadioButton(this).apply { text = mode.name; id = View.generateViewId(); tag = mode })
        }
        (modeGroup.getChildAt(0) as RadioButton).isChecked = true
        // Persist the moment the selection changes -- not just on Save/Send -- so a call
        // arriving before either button is next tapped still uses what's actually selected,
        // not a stale default (this is exactly how the earlier COOL-vs-HEAT bug happened).
        modeGroup.setOnCheckedChangeListener { _, _ -> if (!restoringSelection) CallAllowlist.setDefaultCommand(this, selectedMode(), selectedDuration()) }
        root.addView(modeGroup, matchWrap())

        root.addView(label("Duration"))
        durationGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(Rc4CarClient.Duration.MIN10, Rc4CarClient.Duration.MIN20, Rc4CarClient.Duration.MIN30).forEach { duration ->
            val text = when (duration) { Rc4CarClient.Duration.MIN10 -> "10 min"; Rc4CarClient.Duration.MIN20 -> "20 min"; else -> "30 min" }
            durationGroup.addView(RadioButton(this).apply { this.text = text; id = View.generateViewId(); tag = duration })
        }
        (durationGroup.getChildAt(0) as RadioButton).isChecked = true
        durationGroup.setOnCheckedChangeListener { _, _ -> if (!restoringSelection) CallAllowlist.setDefaultCommand(this, selectedMode(), selectedDuration()) }
        root.addView(durationGroup, matchWrap())

        sendButton = Button(this).apply {
            text = "Send command"
            setOnClickListener { onSendClicked() }
        }
        root.addView(sendButton, matchWrap())

        cancelButton = Button(this).apply {
            text = "Cancel (stop heat/cool)"
            setOnClickListener { onCancelClicked() }
        }
        root.addView(cancelButton, matchWrap())

        statusView = TextView(this).apply {
            text = "Ready."
            setPadding(0, dp(20), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(statusView, matchWrap())

        root.addView(label("Call-triggered control"))
        callTriggerEnabledCheck = CheckBox(this).apply {
            text = "Enable: allowlisted incoming calls trigger the default command below"
            setOnCheckedChangeListener { _, checked -> CallAllowlist.setCallTriggerEnabled(this@Rc4TestActivity, checked) }
        }
        root.addView(callTriggerEnabledCheck, matchWrap())

        fixAllPermissionsButton = Button(this).apply {
            text = "Fix all permissions (Wi-Fi/location, call-screening, battery)"
            setOnClickListener { fixAllPermissions() }
        }
        root.addView(fixAllPermissionsButton, matchWrap())

        requestRoleButton = Button(this).apply {
            text = "Set as call-screening app (required, one-time)"
            setOnClickListener { requestCallScreeningRole() }
        }
        root.addView(requestRoleButton, matchWrap())

        requestBatteryExemptionButton = Button(this).apply {
            text = "Ignore battery optimizations (required, one-time)"
            setOnClickListener { requestBatteryOptimizationExemption() }
        }
        root.addView(requestBatteryExemptionButton, matchWrap())

        val allowlistEntryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        allowlistInput = field("Phone number (any format)")
        allowlistEntryRow.addView(allowlistInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        allowlistEntryRow.addView(Button(this).apply {
            text = "+"
            setOnClickListener {
                val number = allowlistInput.text.toString()
                if (CallAllowlist.normalize(number).isNotEmpty()) {
                    CallAllowlist.add(this@Rc4TestActivity, number)
                    allowlistInput.setText("")
                    renderAllowlist()
                }
            }
        })
        root.addView(allowlistEntryRow, matchWrap())

        allowlistRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(allowlistRows, matchWrap())

        detailedLogCheck = CheckBox(this).apply {
            text = "Detailed log"
            setOnCheckedChangeListener { _, _ -> renderLogFromScratch() }
        }
        root.addView(detailedLogCheck, matchWrap())

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply {
            addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)))

        // The whole form is taller than most screens, and without this the software
        // keyboard's ADJUST_RESIZE clips out everything below the fold -- including
        // fields/buttons you'd need to reach while the keyboard is up (e.g. the phone
        // number entry's own "+" button right next to it).
        return ScrollView(this).apply { addView(root, matchWrap()) }
    }

    private fun selectedMode(): Rc4CarClient.Mode =
        (modeGroup.findViewById<RadioButton>(modeGroup.checkedRadioButtonId)?.tag as? Rc4CarClient.Mode) ?: Rc4CarClient.Mode.HEAT

    private fun selectedDuration(): Rc4CarClient.Duration =
        (durationGroup.findViewById<RadioButton>(durationGroup.checkedRadioButtonId)?.tag as? Rc4CarClient.Duration) ?: Rc4CarClient.Duration.MIN10

    private fun onSaveCredentialsClicked() {
        val ssid = ssidInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim()

        if (ssid.isEmpty()) { setStatus("Enter the car's SSID."); return }
        CarCredentials.save(this, ssid, host, port)
        if (password.isNotEmpty() && rememberPasswordCheck.isChecked) {
            CarCredentials.savePassword(this, ssid, password)
        }
        setStatus("Saved. Ready to send commands or receive triggering calls.")
    }

    private fun onSendClicked() {
        withCredentials { creds ->
            val mode = selectedMode()
            val duration = selectedDuration()
            CarController.runCommand(this, creds, Rc4CarClient.climateCommand(mode, duration), "${mode.name.lowercase()}") { }
        }
    }

    private fun onCancelClicked() {
        withCredentials { creds ->
            CarController.runCommand(this, creds, Rc4CarClient.cancelCommand(), "cancel") { }
        }
    }

    private fun withCredentials(action: (CarCredentials.Saved) -> Unit) {
        var creds = CarCredentials.load(this)
        if (creds == null) {
            // Nothing saved yet -- fall back to whatever is currently typed in, same
            // as tapping "Save credentials" first would produce.
            val ssid = ssidInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (ssid.isEmpty() || password.length < 8) {
                setStatus("Enter SSID + an 8+ char password and tap Save credentials first.")
                return
            }
            creds = CarCredentials.Saved(
                ssid, password,
                hostInput.text.toString().trim().ifEmpty { "192.168.8.46" },
                portInput.text.toString().trim().toIntOrNull() ?: 8080,
            )
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingCommand = { action(creds) }
            requestPermissions(requiredWifiPermissions().toTypedArray(), LOCATION_PERMISSION_REQUEST)
            return
        }
        action(creds)
    }

    private fun requiredWifiPermissions(): List<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (granted) pendingCommand?.invoke() else setStatus("Nearby Wi-Fi devices / location permission is required.")
                pendingCommand = null
            }
            FIX_ALL_PERMISSIONS_REQUEST -> {
                if (granted) advanceFixAllPermissions() else stopFixAllPermissions("Wi-Fi/phone/call-log permission was denied.")
            }
        }
    }

    /** True if held, false if unavailable/not held -- never launches anything itself. */
    private fun hasCallScreeningRole(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            setStatus("Call-screening role not available on this device/Android version.")
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            setStatus("Already the call-screening app.")
            return
        }
        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), CALL_SCREENING_ROLE_REQUEST)
    }

    /**
     * The background thread in [CarController.runCommand] (Wi-Fi join + RC4 send, triggered
     * from [PhevCallScreeningService] with the screen possibly off) needs this exemption to
     * avoid being throttled or killed mid-command by Doze/App Standby -- the standard,
     * user-consented Android mechanism for exactly this case, not a workaround.
     */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            setStatus("Already exempt from battery optimizations.")
            return
        }
        startActivityForResult(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            BATTERY_EXEMPTION_REQUEST,
        )
    }

    /**
     * One button that steps through every requirement this app needs, in order, one dialog
     * at a time -- for recovering after something got revoked (a settings reset, an OS
     * update resetting permissions, etc.) without having to know which of the several
     * separate grant flows is the one that broke. Each step is idempotent (already-granted
     * steps are skipped), so re-tapping this after returning from any single dialog just
     * continues from wherever it left off.
     */
    private fun fixAllPermissions() {
        fixingAllPermissions = true
        advanceFixAllPermissions()
    }

    private fun stopFixAllPermissions(message: String) {
        fixingAllPermissions = false
        setStatus(message)
    }

    private fun advanceFixAllPermissions() {
        if (!fixingAllPermissions) return

        val missingRuntimePermissions = (
            requiredWifiPermissions() + listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missingRuntimePermissions.isNotEmpty()) {
            requestPermissions(missingRuntimePermissions.toTypedArray(), FIX_ALL_PERMISSIONS_REQUEST)
            return
        }

        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager != null && !locationManager.isLocationEnabled) {
            setStatus("Turn on Location in the screen that opens, then come back and tap this button again.")
            startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), LOCATION_SETTINGS_REQUEST)
            return
        }

        if (!hasCallScreeningRole()) {
            requestCallScreeningRole()
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            requestBatteryOptimizationExemption()
            return
        }

        fixingAllPermissions = false
        setStatus("All required permissions are granted.")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            CALL_SCREENING_ROLE_REQUEST -> {
                setStatus(if (resultCode == RESULT_OK) "Call-screening role granted." else "Call-screening role not granted.")
                if (fixingAllPermissions) advanceFixAllPermissions()
            }
            BATTERY_EXEMPTION_REQUEST -> {
                if (fixingAllPermissions) advanceFixAllPermissions()
            }
            LOCATION_SETTINGS_REQUEST -> {
                if (fixingAllPermissions) advanceFixAllPermissions()
            }
        }
    }

    private fun renderAllowlist() {
        allowlistRows.removeAllViews()
        val numbers = CallAllowlist.all(this).sorted()
        if (numbers.isEmpty()) {
            allowlistRows.addView(TextView(this).apply { text = "No allowlisted numbers yet." })
            return
        }
        numbers.forEach { number ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                TextView(this).apply { text = number; setTextIsSelectable(true) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(Button(this).apply {
                text = "-"
                setOnClickListener {
                    CallAllowlist.remove(this@Rc4TestActivity, number)
                    renderAllowlist()
                }
            })
            allowlistRows.addView(row, matchWrap())
        }
    }

    private fun loadSavedFields() {
        CarCredentials.savedSsid(this)?.let { ssidInput.setText(it) }
        hostInput.setText(CarCredentials.savedHost(this))
        portInput.setText(CarCredentials.savedPort(this))
        if (CarCredentials.hasSavedPassword(this)) {
            setStatus("Saved credentials found for \"${CarCredentials.savedSsid(this)}\".")
        }
        callTriggerEnabledCheck.isChecked = CallAllowlist.isCallTriggerEnabled(this)
        // Without this, the radio groups always reset to HEAT/10min on every fresh
        // Activity instance (app restart, process death, etc.) regardless of what was
        // last saved -- and the *next* selection change would then silently overwrite the
        // real saved default with whatever the reset UI happened to show. Guarded by
        // restoringSelection because each call below fires its group's own
        // onCheckedChangeListener, which would otherwise re-save the combination using
        // whichever group hasn't been restored yet (mode restored + duration still at its
        // pre-restore default) -- clobbering the real saved duration with a stale one.
        restoringSelection = true
        selectRadioByTag(modeGroup, CallAllowlist.defaultMode(this))
        selectRadioByTag(durationGroup, CallAllowlist.defaultDuration(this))
        restoringSelection = false
        renderAllowlist()
    }

    private fun selectRadioByTag(group: RadioGroup, tag: Any) {
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as RadioButton
            if (button.tag == tag) { button.isChecked = true; return }
        }
    }

    private fun setStatus(message: String) { statusView.text = message }

    private fun shouldShow(entry: BridgeLog.Entry): Boolean =
        entry.level == BridgeLog.Level.SUMMARY || detailedLogCheck.isChecked

    private fun appendLogLine(entry: BridgeLog.Entry) {
        logView.append(BridgeLog.format(entry) + "\n")
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun renderLogFromScratch() {
        logView.text = BridgeLog.snapshot().filter(::shouldShow).joinToString("\n", transform = BridgeLog::format)
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    override fun onDestroy() {
        BridgeLog.removeListener(logListener)
        super.onDestroy()
    }
}
