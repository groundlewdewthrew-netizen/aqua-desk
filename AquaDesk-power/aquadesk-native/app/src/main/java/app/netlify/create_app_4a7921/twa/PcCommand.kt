package app.netlify.create_app_4a7921.twa

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends one short command ("open" / "close" / "list" an app) to the PC agent over a
 * throw-away WebSocket. Works from anywhere (chat, voice service) and never interferes
 * with the screen viewer, which uses its own connection.
 */
object PcCommand {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    /** [done] is called exactly once, on a background thread. */
    fun run(prefs: Prefs, action: String, app: String, done: (ok: Boolean, message: String) -> Unit) {
        if (prefs.pcHost.isBlank() || prefs.pcPin.isBlank()) {
            done(false, "Set the computer address and PIN in AquaDesk settings first."); return
        }
        val finished = AtomicBoolean(false)
        fun finish(ok: Boolean, m: String) { if (finished.compareAndSet(false, true)) done(ok, m) }

        val req = Request.Builder().url("ws://${prefs.pcHost}:${prefs.pcPort}/").build()
        http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("t", "auth").put("pin", prefs.pcPin).put("mode", "cmd").toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                val j = JSONObject(text)
                when (j.optString("t")) {
                    "auth" -> if (j.optBoolean("ok"))
                        w.send(JSONObject().put("t", "cmd").put("a", action).put("app", app).toString())
                    else { finish(false, "Wrong PIN."); w.close(1000, null) }
                    "cmdres" -> { finish(j.optBoolean("ok"), j.optString("m")); w.close(1000, null) }
                    "error" -> { finish(false, "PC says: ${j.optString("m")}"); w.close(1000, null) }
                }
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { finish(false, "PC closed the connection.") }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                finish(false, "Can't reach the PC (${t.message}). Is the agent running on the same network?")
            }
        })
    }

    /** "open chrome on my computer" -> ("open","chrome"). Returns null if it isn't an app command. */
    private val verbs = mapOf("open" to "open", "launch" to "open", "start" to "open", "run" to "open",
        "close" to "close", "quit" to "close", "exit" to "close", "kill" to "close")
    private val tail = Regex("\\s+(on|in|from|at)\\s+(my\\s+|the\\s+)?(computer|pc|desktop|laptop|machine)$")
    private val ownApp = setOf("app", "the app", "aquadesk", "aqua desk", "aqua", "this app")

    private val pcWord = Regex("\\b(computer|pc|laptop|machine|desktop)\\b")
    private val power = listOf(
        "shutdown" to Regex("^(shut\\s?down|power off|turn off|switch off)\\b"),
        "restart" to Regex("^(restart|reboot)\\b"),
        "sleep" to Regex("^(sleep|put\\b.*\\bto sleep)\\b|\\bto sleep$"),
        "lock" to Regex("^lock\\b"))
    private val cancel = Regex("^(cancel|abort|stop)\\b.*(shut\\s?down|restart|reboot|power)")

    fun parse(text: String): Pair<String, String>? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?').replace(tail, "").trim()
        // Power commands: cancel is always allowed; the rest only when you name the computer,
        // so "turn off the lights" can never shut down your PC.
        if (cancel.containsMatchIn(t)) return "cancel" to ""
        if (pcWord.containsMatchIn(text.lowercase()))
            power.firstOrNull { it.second.containsMatchIn(t) }?.let { return it.first to "" }
        val words = t.split(Regex("\\s+"), limit = 2)
        val action = verbs[words.firstOrNull()] ?: return null
        val app = words.getOrNull(1)?.trim().orEmpty()
        if (app.isEmpty() || app in ownApp) return null
        if (Regex("^(my |the )?(computer|pc|desktop|laptop|screen|remote)").containsMatchIn(app)) return null  // = "connect to PC"
        return action to app
    }
}
