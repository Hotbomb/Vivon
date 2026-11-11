package app.couchphone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The "Couch Phone" screen in the app list, in couch-tv's setup colours. Shows the address to open
 * on the other phone, the pairing code, and whether root and DeX are working.
 */
class SetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var state: TextView
    private val tick = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 1500) } }
    private val pinListener: (String?) -> Unit = { main.post { refresh() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        App.startService(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(28), px(28), px(28), px(28)) }
        fun text(t: String, size: Float, colour: Int = CREAM, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(colour); if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, px(4), 0, px(4))
        }
        col.addView(text("Couch Phone", 34f, CREAM, true))
        col.addView(text("TV home screen and phone remote for Samsung DeX. Based on couch-tv.", 15f, CREAM_2))
        address = text("", 20f, AMBER, true); col.addView(address)
        code = text("", 40f, CREAM, true); col.addView(code)
        state = text("", 15f, CREAM_2); col.addView(state)

        fun button(t: String, on: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 15f; setTextColor(CREAM)
            background = GradientDrawable().apply { setColor(FLAP); cornerRadius = px(6).toFloat() }
            setOnFocusChangeListener { v, has -> (v.background as GradientDrawable).setStroke(if (has) px(3) else 0, AMBER) }
            setOnClickListener { on() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) }
        }
        val first = button("Open the home screen on the TV") { Thread { Dex.openHome() }.start() }
        col.addView(first)
        col.addView(button("New phone code") { Auth.newPin(); refresh() })
        col.addView(button("Ask for root again") { Thread { Root.openShell(); RootInput.warmUp(); main.post { refresh() } }.start() })
        col.addView(button("Use as this phone's home app too") { HomeRole.request(this) })
        col.addView(button("Battery: set to Unrestricted") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        })
        col.addView(button("Forget all paired phones") { Auth.forgetAll(); refresh() })
        col.addView(text("The other phone must be on the same Wi-Fi. Each phone types the code once. " +
            "A code lasts 3 minutes and works once.", 14f, CREAM_2).apply { setPadding(0, px(18), 0, 0) })
        col.addView(text("Version ${BuildConfig.VERSION_NAME}", 13f, CREAM_2))
        setContentView(ScrollView(this).apply { setBackgroundColor(GROUND); addView(col) })
        first.requestFocus()
        if (Auth.currentPin() == null) Auth.newPin()
    }

    override fun onResume() { super.onResume(); Auth.onPin(pinListener); main.post(tick) }
    override fun onPause() { super.onPause(); Auth.offPin(pinListener); main.removeCallbacks(tick) }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); if (i.getBooleanExtra("pin", false) && Auth.currentPin() == null) Auth.newPin(); refresh() }

    private fun refresh() {
        val ip = App.lanAddress()
        address.text = if (ip != null) "On your other phone open\nhttp://$ip:${Store.port}" else "Connect this phone to Wi-Fi first."
        val p = Auth.currentPin()
        code.text = if (p != null) "Code  ${p.take(3)} ${p.drop(3)}" else "Code: press New phone code"
        code.visibility = View.VISIBLE
        val root = when (Root.available) {
            true -> if (RootInput.running) "Root: working." else "Root: allowed, starting the input helper."
            false -> "Root: NOT allowed. Open Magisk, Superuser, and allow Couch Phone, then press Ask for root again." +
                (Root.lastError?.let { "\n($it)" } ?: "")
            null -> "Root: waiting. If Magisk asks, press Grant."
        }
        val tv = Dex.tvDisplay()
        val dex = if (tv != null) "DeX: on, TV is display $tv." else "DeX: not on a TV right now. Keys go to this phone's screen."
        state.text = "$root\n$dex\nPhones paired: ${Auth.phoneCount()}"
    }

    companion object {
        private val GROUND = Color.parseColor("#0D1E19")
        private val FLAP = Color.parseColor("#131614")
        private val CREAM = Color.parseColor("#F2EAD8")
        private val CREAM_2 = Color.parseColor("#BDB6A7")
        private val AMBER = Color.parseColor("#FFB52E")
    }
}
