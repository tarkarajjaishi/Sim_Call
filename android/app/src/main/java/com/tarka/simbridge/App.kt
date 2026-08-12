package com.tarka.simbridge

// SimBridge: one app for both calls and SMS.
//
//   VPS queue_server.py  <──long-poll GET /next──  PollService  ──> SIM
//                        ──POST /result──────────>
//
// The phone only ever makes outbound connections, so nothing needs to reach it.
// Jobs are {"id":N,"type":"call"|"sms","to":...,"sim":N,"seconds":N|"text":...}

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val TAG = "SimBridge"
private const val PREFS = "simbridge"
private const val K_URL = "url"
private const val K_FROM = "from"   // the SIM number: doubles as caller AND login token

private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private val NEEDED: Array<String>
    get() = mutableListOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.ANSWER_PHONE_CALLS,
    ).also {
        if (Build.VERSION.SDK_INT >= 26) it.add(android.Manifest.permission.READ_PHONE_NUMBERS)
        if (Build.VERSION.SDK_INT >= 33) it.add(android.Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

/** Active SIMs, or empty if the permission isn't granted yet. */
@SuppressLint("MissingPermission")
fun sims(c: Context): List<SubscriptionInfo> = try {
    c.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
} catch (e: SecurityException) {
    emptyList()
}

/**
 * Resolve whatever the user typed in "Call from" to a subscriptionId.
 * Accepts the SIM's own number, the carrier name ("Ncell"), or a slot number.
 * Numbers are matched on their last 9 digits because carriers format the
 * stored MSISDN inconsistently (+977…, 977…, or bare).
 */
fun subIdFor(c: Context, pick: String): Int {
    val p = pick.trim()
    val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    if (p.isEmpty() || p == "0") return invalid
    val list = sims(c)

    val digits = p.filter { it.isDigit() }
    if (digits.length >= 6) {
        val tail = digits.takeLast(9)
        list.firstOrNull { s ->
            s.number?.filter { it.isDigit() }?.takeLast(9) == tail
        }?.let { return it.subscriptionId }
    }
    list.firstOrNull {
        it.displayName?.toString().equals(p, true) ||
            it.carrierName?.toString().equals(p, true)
    }?.let { return it.subscriptionId }
    p.toIntOrNull()?.let { n ->
        list.firstOrNull { it.simSlotIndex == n - 1 }?.let { return it.subscriptionId }
    }
    // Don't log p itself -- it's the owner's own phone number.
    Log.w(TAG, "no SIM matched the configured \"call from\"; using phone default")
    return invalid
}


class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var from: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad, 0, 0) }

        url = EditText(this).apply {
            hint = "https://calls.yourdomain.com/next"
            setText(p.getString(K_URL, ""))
            setSingleLine()
        }
        from = EditText(this).apply {
            hint = "your SIM's number, e.g. 98XXXXXXXX"
            setText(p.getString(K_FROM, ""))
            setSingleLine()
        }
        root.addView(label("Queue URL")); root.addView(url)
        root.addView(label("Your number (calls from this SIM, and is your login)"))
        root.addView(from)
        root.addView(TextView(this).apply {
            text = "Detected: " + (sims(this@MainActivity)
                .joinToString("; ") {
                    "slot ${it.simSlotIndex + 1} ${it.displayName}" +
                        (it.number?.takeIf(String::isNotBlank)?.let { n -> " ($n)" } ?: "")
                }.ifEmpty { "no SIMs yet - grant Phone permission" })
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(Button(this).apply {
            text = "Save & start"
            setOnClickListener { saveAndStart() }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PollService::class.java))
                Toast.makeText(this@MainActivity, "Stopped", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(label(
            "Runs until you tap Stop, including with the screen off. Set this app " +
            "to Unrestricted under Settings > Apps > Battery, and enable SimBridge " +
            "under Settings > Accessibility so calls hang up automatically."))
        setContentView(root, ViewGroup.LayoutParams(-1, -1))

        requestPermissions(NEEDED, 1)
        configureFromIntent()
    }

    private fun saveAndStart() {
        val u = url.text.toString().trim()
        val f = from.text.toString().trim()
        // The number is sent as the bearer token on every request; over plain HTTP
        // anyone on the path can lift it and drive your SIM. Refuse rather than
        // warn. Loopback is the one safe exception (use `adb reverse`).
        val loopback = u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")
        if (!u.startsWith("https://") && !loopback) {
            return toast("URL must be https:// (or http://127.0.0.1 for local testing)")
        }
        if (f.isEmpty()) return toast("Your number is required -- it is your login")
        // Soft-check: if no SIM matches, the login still works but calls fall back
        // to the phone's default SIM. Tell them so it isn't a silent surprise.
        val matched = subIdFor(this, f) != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        prefs(this).edit().putString(K_URL, u).putString(K_FROM, f).apply()
        startForegroundService(Intent(this, PollService::class.java))
        toast(if (matched) "Running -- calling from this SIM"
              else "Running, but no SIM matched that number; using default SIM")
    }

    /**
     * Debug-only headless setup, because MIUI revokes INJECT_EVENTS from the adb
     * shell user so `input tap`/`input text` cannot fill these fields:
     *
     *   adb shell am start -n com.tarka.simbridge/.MainActivity \
     *       --es url http://127.0.0.1:8777/next --es number 9744802942
     *   adb shell am start -n com.tarka.simbridge/.MainActivity --es action stop
     *
     * Gated on the debuggable flag so a release build can't be driven by any
     * other app on the device sending this exported activity an intent.
     */
    private fun configureFromIntent() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent?.getStringExtra("action") == "stop") {
            stopService(Intent(this, PollService::class.java))
            return toast("Stopped")
        }
        val u = intent?.getStringExtra("url") ?: return
        val n = intent?.getStringExtra("number") ?: return
        url.setText(u)
        from.setText(n)
        saveAndStart()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}


class PollService : Service() {
    @Volatile private var running = false
    private var defaultFrom = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (!running) {
            running = true
            startForeground(1, notification())
            Thread(::loop, "simbridge-poll").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
    }

    /**
     * Android requires a foreground service to post a notification -- there is no
     * way to hide it, and dropping it means the OS (MIUI especially) kills the
     * service. IMPORTANCE_MIN is as quiet as it gets: silent, no status-bar icon,
     * parked at the bottom of the shade.
     */
    private fun notification(): Notification {
        val ch = "simbridge"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "SimBridge", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) })
        return Notification.Builder(this, ch)
            .setContentTitle("SimBridge")
            .setContentText("Running until you tap Stop")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .build()
    }

    private fun loop() {
        val p = prefs(this)
        val url = p.getString(K_URL, "").orEmpty()
        defaultFrom = p.getString(K_FROM, "").orEmpty()
        val token = defaultFrom     // the number authenticates AND selects the SIM
        while (running) {
            // A failed *poll* and a failed *job* are different things: the first
            // means back off and retry, the second means report it and move on.
            val job = try {
                val body = fetch(url, token)
                if (body.isEmpty()) continue
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "poll failed: ${e.message}")
                Thread.sleep(5_000)
                continue
            }
            var error: String? = null
            try {
                handle(job)
            } catch (e: Exception) {
                error = e.toString()
                Log.w(TAG, "job ${job.optInt("id")} failed: $e")
            }
            report(url, token, job.optInt("id", 0), error)
        }
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun fetch(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.connectTimeout = 15_000
            c.readTimeout = 45_000        // must exceed the server's 25s long-poll hold
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText().trim()
        } finally {
            c.disconnect()
        }
    }

    /** Tell the server what happened, so a job the phone cannot run isn't silent. */
    private fun report(url: String, token: String, id: Int, error: String?) {
        val target = url.removeSuffix("/next") + "/result"
        val body = JSONObject()
            .put("id", id).put("ok", error == null).put("error", error ?: "")
            .toString()
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) Log.w(TAG, "report -> HTTP ${c.responseCode}")
        } catch (e: Exception) {
            Log.w(TAG, "report failed: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    private fun handle(job: JSONObject) {
        val to = job.getString("to")
        // Per-job "sim" wins; otherwise the number/carrier configured in the app.
        val pick = job.optString("sim", "").ifEmpty { defaultFrom }
        val subId = subIdFor(this, pick)
        when (job.getString("type")) {
            "call" -> call(to, subId, job.optInt("seconds", 45))
            "sms" -> sms(to, job.getString("text"), subId)
            else -> Log.w(TAG, "unknown job type in $job")
        }
    }

    @SuppressLint("MissingPermission")
    private fun call(to: String, subId: Int, seconds: Int) {
        val i = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", to, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        phoneAccount(subId)?.let { i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        startActivity(i)
        Thread.sleep(seconds * 1000L)
        hangUp()
    }

    /**
     * Match the phone account by subscriptionId rather than by list position --
     * callCapablePhoneAccounts is not ordered by slot, so indexing it picks the
     * wrong SIM. Telecom registers each account with id == subId.
     */
    @SuppressLint("MissingPermission")
    private fun phoneAccount(subId: Int): PhoneAccountHandle? {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return try {
            getSystemService(TelecomManager::class.java)
                .callCapablePhoneAccounts.firstOrNull { it.id == subId.toString() }
                .also { if (it == null) Log.w(TAG, "no phone account for subId $subId") }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot list phone accounts: ${e.message}"); null
        }
    }

    private fun hangUp() {
        // 1. The proper API. Android 10+ refuses it unless we're the default phone
        //    app, which would mean taking over every call on the device.
        if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION") @SuppressLint("MissingPermission")
            val ended = try {
                getSystemService(TelecomManager::class.java).endCall()
            } catch (e: SecurityException) {
                false
            }
            if (ended) return
        }
        // 2. Fallback: the accessibility service presses End for us. Enabled once,
        //    then fully automatic -- no interaction per call.
        if (HangupService.instance?.endCall() == true) return
        Log.w(TAG, "could not end call -- enable SimBridge under Settings > Accessibility")
    }

    @SuppressLint("MissingPermission")
    private fun sms(to: String, text: String, subId: Int) {
        val m = smsManager(subId)
        // divideMessage handles >160 chars; sendTextMessage would silently truncate.
        m.sendMultipartTextMessage(to, null, m.divideMessage(text), null, null)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subId: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
                   else SmsManager.getDefault()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base
        return if (Build.VERSION.SDK_INT >= 31) base.createForSubscriptionId(subId)
               else SmsManager.getSmsManagerForSubscriptionId(subId)
    }
}


/**
 * Ends calls by pressing the dialer's End button. Needed because endCall() is
 * restricted to the default phone app, and becoming the default phone app would
 * route every personal call through this app too.
 *
 * Enable once: Settings > Accessibility > Downloaded apps > SimBridge.
 */
class HangupService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: HangupService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "hangup service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun endCall(): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "hangup: no active window"); return false
        }
        val node = find(root)
        if (node == null) {
            Log.w(TAG, "hangup: no End button found")
            // Dumping node text can capture whatever is on screen, so only do it
            // in debug builds -- it exists to identify this vendor's dialer ids.
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                dump(root, 0)
            }
            return false
        }
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "hangup: clicked ${node.viewIdResourceName} -> $clicked")
        return clicked
    }

    private fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val id = n.viewIdResourceName.orEmpty()
        val desc = n.contentDescription?.toString().orEmpty()
        val matches = id.contains("end", true) || id.contains("hang", true) ||
            desc.contains("end call", true) || desc.contains("hang up", true)
        if (matches && n.isClickable) return n
        for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
        return null
    }

    private fun dump(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 12) return
        if (n.isClickable) {
            Log.i(TAG, "  node id=${n.viewIdResourceName} desc=${n.contentDescription} text=${n.text}")
        }
        for (i in 0 until n.childCount) dump(n.getChild(i), depth + 1)
    }
}


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (prefs(c).getString(K_URL, "").isNullOrEmpty()) return
        c.startForegroundService(Intent(c, PollService::class.java))
    }
}
