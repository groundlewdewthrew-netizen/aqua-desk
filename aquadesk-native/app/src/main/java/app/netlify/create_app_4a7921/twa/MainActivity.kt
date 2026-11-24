package app.netlify.create_app_4a7921.twa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import org.json.JSONObject
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Hosts the AquaDesk web app in a WebView (replaces the old Trusted Web Activity)
 * and adds native features on top: wake word + PC remote viewer.
 *
 * The web app can talk to native code through `window.AquaNative`:
 *   AquaNative.startWakeWord() / stopWakeWord() / setWakePhrase("hey aqua")
 *   AquaNative.openRemote()
 *   AquaNative.pcOpen("chrome") / pcClose("spotify") / pcList() / pcCommand("open chrome")
 *     -> result event: window "aquadesk-pc" {action, app, ok, message}
 * and receives `window.dispatchEvent(new CustomEvent("aquadesk-voice", {detail:{action,text}}))`.
 */
class MainActivity : AppCompatActivity() {

    companion object { const val HOME = "https://create-app-4a7921.netlify.app/" }

    private var web: WebView? = null
    private lateinit var prefs: Prefs
    private var pendingAfterPerm: (() -> Unit)? = null
    private var webPermRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val wv = try { WebView(this) } catch (e: Throwable) { null }
        web = wv
        wv?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)   // GPU-composited page
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(Bridge(), "AquaNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.host == Uri.parse(HOME).host) return false
                    startActivity(Intent(Intent.ACTION_VIEW, r.url)); return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Lets the web app use getUserMedia() (mic) once Android permission is granted.
                override fun onPermissionRequest(req: PermissionRequest) {
                    runOnUiThread {
                        if (req.origin.host != Uri.parse(HOME).host) { req.deny(); return@runOnUiThread }
                        if (hasMic()) req.grant(req.resources)
                        else { webPermRequest = req; askMic { webPermRequest?.grant(webPermRequest?.resources); webPermRequest = null } }
                    }
                }
            }
        }

        val root = FrameLayout(this)
        if (wv != null) root.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        else root.addView(TextView(this).apply {
            text = "This phone's web view (Android System WebView / Chrome) is missing or disabled.\n\nEnable or update it in Play Store / Settings > Apps, then reopen AquaDesk. The ⚙ button still works for the PC remote."
            setPadding(48, 160, 48, 48); textSize = 16f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(fab("⚙") { showSettings() }, FrameLayout.LayoutParams(130, 130, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 36, 36) })
        setContentView(root)

        if (savedInstanceState == null) wv?.loadUrl(intent?.data?.toString() ?: HOME) else wv?.restoreState(savedInstanceState)
        if (prefs.wakeEnabled && hasMic()) startWake()
    }

    private fun fab(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC0B3C5D.toInt()) }
        elevation = 4f; setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- settings --
    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val wakeSw = Switch(this).apply { text = "Wake word listening"; isChecked = prefs.wakeEnabled }
        val phrase = EditText(this).apply { hint = "Wake phrase"; setText(prefs.wakePhrase) }
        val host = EditText(this).apply { hint = "Computer IP / hostname (e.g. 192.168.1.20)"; setText(prefs.pcHost) }
        val port = EditText(this).apply { hint = "Port"; setText(prefs.pcPort.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val pin = EditText(this).apply { hint = "PIN shown by PC agent"; setText(prefs.pcPin); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val tip = TextView(this).apply { text = "Say \"${prefs.wakePhrase}, connect to my computer\" or \"${prefs.wakePhrase}, open app\"."; textSize = 12f }
        listOf(wakeSw, phrase, tip, host, port, pin).forEach { col.addView(it) }

        AlertDialog.Builder(this).setTitle("AquaDesk – voice & PC").setView(col)
            .setPositiveButton("Save") { _, _ -> saveSettings(wakeSw.isChecked, phrase.text.toString(), host.text.toString(), port.text.toString(), pin.text.toString()) }
            .setNeutralButton("Connect to PC") { _, _ ->
                saveSettings(wakeSw.isChecked, phrase.text.toString(), host.text.toString(), port.text.toString(), pin.text.toString())
                openRemote()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveSettings(wake: Boolean, ph: String, h: String, p: String, pn: String) {
        prefs.wakePhrase = ph; prefs.pcHost = h; prefs.pcPin = pn
        prefs.pcPort = p.toIntOrNull() ?: 8765
        if (wake) startWakeWord() else stopWake()
    }

    // ----------------------------------------------------------- wake word --
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic(then: () -> Unit) {
        pendingAfterPerm = then
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 42) {
            val then = pendingAfterPerm; pendingAfterPerm = null
            if (hasMic()) then?.invoke()
            else { webPermRequest?.deny(); webPermRequest = null
                   Toast.makeText(this, "Microphone permission is required for wake word", Toast.LENGTH_LONG).show() }
        }
    }

    private fun startWakeWord() { if (hasMic()) startWake() else askMic { startWake() } }
    private fun startWake() {
        try { prefs.wakeEnabled = true; WakeWordService.start(this) }
        catch (e: Exception) { prefs.wakeEnabled = false; Toast.makeText(this, "Could not start wake word on this phone", Toast.LENGTH_LONG).show() }
    }
    private fun stopWake() { prefs.wakeEnabled = false; WakeWordService.stop(this) }

    private fun openRemote() {
        startActivity(Intent(this, RemoteDesktopActivity::class.java).putExtra("autoconnect", true))
    }

    private fun onVoice(action: String, text: String) = runOnUiThread {
        if (action == WakeWordService.ACTION_OPEN_REMOTE) openRemote()
        val js = "window.dispatchEvent(new CustomEvent('aquadesk-voice',{detail:{action:${JSONObject.quote(action)},text:${JSONObject.quote(text)}}}))"
        web?.evaluateJavascript(js, null)
    }

    /** Exposed to the web page as window.AquaNative (only reachable from our own origin). */
    inner class Bridge {
        @JavascriptInterface fun startWakeWord() = runOnUiThread { this@MainActivity.startWakeWord() }
        @JavascriptInterface fun stopWakeWord() = runOnUiThread { stopWake() }
        @JavascriptInterface fun setWakePhrase(p: String) { prefs.wakePhrase = p }
        @JavascriptInterface fun openRemote() = runOnUiThread { this@MainActivity.openRemote() }
        @JavascriptInterface fun isWakeActive() = WakeWordService.running

        /** Control the PC from chat. Result arrives as a window "aquadesk-pc" event: detail {action, app, ok, message}. */
        @JavascriptInterface fun pcOpen(app: String) = pc("open", app)
        @JavascriptInterface fun pcClose(app: String) = pc("close", app)
        @JavascriptInterface fun pcList() = pc("list", "")
        /** Power: shutdown/restart wait 30 s on the PC and can be cancelled; sleep/lock are immediate. */
        @JavascriptInterface fun pcShutdown() = pc("shutdown", "")
        @JavascriptInterface fun pcRestart() = pc("restart", "")
        @JavascriptInterface fun pcSleep() = pc("sleep", "")
        @JavascriptInterface fun pcLock() = pc("lock", "")
        @JavascriptInterface fun pcCancel() = pc("cancel", "")
        /** Same, but takes natural text: "open chrome", "close spotify on my pc". Returns false if it isn't an app command. */
        @JavascriptInterface fun pcCommand(text: String): Boolean {
            val (a, app) = PcCommand.parse(text) ?: return false
            pc(a, app); return true
        }
        private fun pc(action: String, app: String) {
            PcCommand.run(prefs, action, app) { ok, msg ->
                val js = "window.dispatchEvent(new CustomEvent('aquadesk-pc',{detail:{action:${JSONObject.quote(action)}," +
                    "app:${JSONObject.quote(app)},ok:$ok,message:${JSONObject.quote(msg)}}}))"
                runOnUiThread { web?.evaluateJavascript(js, null) }
            }
        }
    }

    override fun onPause() { web?.onPause(); super.onPause() }      // stop page timers/animations in background
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onStart() { super.onStart(); WakeWordService.foregroundHandler = ::onVoice }
    override fun onStop() { WakeWordService.foregroundHandler = null; super.onStop() }
    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); web?.saveState(o) }
    @Deprecated("") override fun onBackPressed() { if (web?.canGoBack() == true) web?.goBack() else super.onBackPressed() }
}
