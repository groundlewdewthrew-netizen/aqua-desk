package app.netlify.create_app_4a7921.twa

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Shows your computer's screen on the phone and sends touch input back to it.
 * Talks to pc-agent/aquadesk_agent.py over a WebSocket.
 *
 * Gestures: tap = left click | double-tap = double click | long-press = right click
 *           one-finger drag = move pointer | two-finger drag = scroll
 *           "Drag" toggle: next one-finger drag holds the left button (select / move windows)
 */
class RemoteDesktopActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var typer: EditText
    private var ws: WebSocket? = null
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .socketFactory(object : SocketFactory() {   // TCP_NODELAY: send taps/moves immediately
            private val d = SocketFactory.getDefault()
            private fun Socket.fast() = apply { tcpNoDelay = true }
            override fun createSocket() = d.createSocket().fast()
            override fun createSocket(h: String, p: Int) = d.createSocket(h, p).fast()
            override fun createSocket(h: String, p: Int, a: java.net.InetAddress, lp: Int) = d.createSocket(h, p, a, lp).fast()
            override fun createSocket(a: java.net.InetAddress, p: Int) = d.createSocket(a, p).fast()
            override fun createSocket(a: java.net.InetAddress, p: Int, la: java.net.InetAddress, lp: Int) = d.createSocket(a, p, la, lp).fast()
        }).build()
    private val frameOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }  // half the memory, faster
    private val latest = AtomicReference<Bitmap?>(null)   // newest decoded frame; older ones are dropped
    private val matrixVals = FloatArray(9)
    private var connected = false
    private var dragMode = false
    private var dragging = false
    private var lastMove = 0L
    private var pcRes = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); setPadding(24, 8, 24, 8); textSize = 12f }
        typer = EditText(this).apply { alpha = 0f; setWidth(1); setHeight(1); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF0B3C5D.toInt()) }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; textSize = 11f; isAllCaps = false; setOnClickListener { a() } }
        bar.addView(btn("⌨ Keys") { toggleKeyboard() }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Esc") { send("""{"t":"key","k":"esc"}""") }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Enter") { send("""{"t":"key","k":"enter"}""") }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Drag") { dragMode = !dragMode; toast(if (dragMode) "Drag: ON for next swipe" else "Drag: off") }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Quality") { cycleQuality() }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("✕") { finish() }, LinearLayout.LayoutParams(0, -2, 0.6f))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(status, -1, -2)
        col.addView(FrameLayout(this).apply { addView(image, -1, -1); addView(typer) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(bar, -1, -2)
        setContentView(col)

        setupTouch(); setupTyping()
        if (prefs.pcHost.isBlank() || prefs.pcPin.isBlank()) {
            status.text = "Set computer address and PIN in AquaDesk ⚙ settings first."
        } else connect()
    }

    // ------------------------------------------------------------ connection --
    private fun connect() {
        status.text = "Connecting to ${prefs.pcHost}:${prefs.pcPort}…"
        val req = Request.Builder().url("ws://${prefs.pcHost}:${prefs.pcPort}/").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("t", "auth").put("pin", prefs.pcPin).toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                val j = JSONObject(text)
                when (j.optString("t")) {
                    "auth" -> if (!j.optBoolean("ok")) ui { status.text = "Wrong PIN."; }
                    "hello" -> { connected = true; pcRes = "${j.optInt("w")}×${j.optInt("h")}"; ui { status.text = "Connected – $pcRes" } }
                    "stat" -> ui { status.text = "$pcRes · ${j.optInt("fps")} fps · ${j.optInt("rtt")} ms · ${j.optInt("kb")} KB/frame · ${j.optInt("q")}%" }
                    "error" -> ui { status.text = "PC says: ${j.optString("m")}" }
                }
            }
            override fun onMessage(w: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, frameOpts)
                w.send(ACK)                                   // lets the PC send the next frame
                if (bmp == null) return
                // Latest-frame-wins: if the UI hasn't drawn the previous frame yet, just replace it.
                if (latest.getAndSet(bmp) == null) ui { latest.getAndSet(null)?.let { image.setImageBitmap(it) } }
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { connected = false; ui { if (!isFinishing) status.text = "Disconnected." } }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                connected = false
                ui { status.text = "Can't reach PC (${t.message}). Is the agent running and on the same network?" }
            }
        })
    }

    private companion object { const val ACK = """{"t":"ack"}""" }

    private fun ui(f: () -> Unit) = runOnUiThread { if (!isDestroyed) f() }
    private fun send(s: String) { if (connected) ws?.send(s) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private var q = 1
    private fun cycleQuality() {
        q = (q + 1) % 3
        val (n, fps, quality, scale) = when (q) { 0 -> listOf("Low", 5, 35, 0.4); 1 -> listOf("Medium", 8, 55, 0.6); else -> listOf("High", 12, 75, 0.9) }
        send("""{"t":"cfg","fps":$fps,"q":$quality,"scale":$scale}"""); toast("Quality: $n")
    }

    // ----------------------------------------------------------------- input --
    /** Maps a touch point to 0..1 coordinates of the actual picture (accounts for FIT_CENTER letterboxing). */
    private fun norm(x: Float, y: Float): Pair<Float, Float>? {
        val d = image.drawable ?: return null
        val m = matrixVals; image.imageMatrix.getValues(m)
        val sx = m[android.graphics.Matrix.MSCALE_X]; val sy = m[android.graphics.Matrix.MSCALE_Y]
        val nx = (x - m[android.graphics.Matrix.MTRANS_X]) / (d.intrinsicWidth * sx)
        val ny = (y - m[android.graphics.Matrix.MTRANS_Y]) / (d.intrinsicHeight * sy)
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { click(e, "left", 1); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { click(e, "left", 2); return true }
            override fun onLongPress(e: MotionEvent) { if (!dragMode) click(e, "right", 1) }
        })
        var scrollY0 = 0f
        image.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { scrollY0 = (e.getY(0) + e.getY(1)) / 2 }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                    val y = (e.getY(0) + e.getY(1)) / 2
                    val dy = ((y - scrollY0) / 40f).toInt()
                    if (dy != 0) { send("""{"t":"scroll","dy":${-dy}}"""); scrollY0 = y }
                } else moveTo(e)
                MotionEvent.ACTION_DOWN -> if (dragMode) { norm(e.x, e.y)?.let { send(ev("down", it, "left")); dragging = true } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    norm(e.x, e.y)?.let { send(ev("up", it, "left")) }; dragging = false; dragMode = false
                }
            }
            true
        }
    }

    private fun ev(t: String, p: Pair<Float, Float>, b: String) = """{"t":"$t","x":${p.first},"y":${p.second},"b":"$b"}"""
    private fun click(e: MotionEvent, b: String, n: Int) { norm(e.x, e.y)?.let { send("""{"t":"click","x":${it.first},"y":${it.second},"b":"$b","n":$n}""") } }
    private fun moveTo(e: MotionEvent) {
        val now = System.currentTimeMillis(); if (now - lastMove < 25) return; lastMove = now
        norm(e.x, e.y)?.let { send("""{"t":"move","x":${it.first},"y":${it.second}}""") }
    }

    private fun setupTyping() {
        typer.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString().orEmpty(); if (t.isEmpty()) return
                send(JSONObject().put("t", "text").put("s", t).toString()); s?.clear()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        typer.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DEL -> { send("""{"t":"key","k":"backspace"}"""); true }
                KeyEvent.KEYCODE_ENTER -> { send("""{"t":"key","k":"enter"}"""); true }
                else -> false
            }
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        typer.requestFocus(); imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    override fun onDestroy() { ws?.close(1000, "bye"); http.dispatcher.executorService.shutdown(); super.onDestroy() }
}
