package com.tarka.simbridge

// SimBridge: one app for both calls and SMS.
//
//   VPS queue_server.py  <──long-poll GET /next──  PollService  ──> SIM
//
// The phone only ever makes outbound connections, so nothing needs to reach it.
// Jobs are {"type":"call"|"sms","to":...,"sim":N,"seconds":N|"text":...}

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.ViewGroup
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
private const val K_TOKEN = "token"

private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private val NEEDED: Array<String>
    get() = mutableListOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    ).also {
        if (Build.VERSION.SDK_INT >= 33) it.add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()


class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var token: EditText

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
        token = EditText(this).apply {
            hint = "QUEUE_TOKEN from the VPS"
            setText(p.getString(K_TOKEN, ""))
            setSingleLine()
        }
        root.addView(label("Queue URL")); root.addView(url)
        root.addView(label("Token")); root.addView(token)
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
            "Grant Phone and SMS permissions when asked, then set this app to " +
            "Unrestricted under Settings > Apps > Battery, or Android will kill it."))
        setContentView(root, ViewGroup.LayoutParams(-1, -1))

        requestPermissions(NEEDED, 1)
    }

    private fun saveAndStart() {
        val u = url.text.toString().trim()
        val t = token.text.toString().trim()
        // The token is sent on every request; over plain HTTP anyone on the path
        // can lift it and drive your SIM. Refuse rather than warn. Loopback is the
        // one safe exception -- it never leaves the device (use `adb reverse`).
        val loopback = u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")
        if (!u.startsWith("https://") && !loopback) {
            return toast("URL must be https:// (or http://127.0.0.1 for local testing)")
        }
        if (t.isEmpty()) return toast("Token required")
        prefs(this).edit().putString(K_URL, u).putString(K_TOKEN, t).apply()
        startForegroundService(Intent(this, PollService::class.java))
        toast("Running")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}


class PollService : Service() {
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (!running) {
            running = true
            startForeground(1, notification("waiting for jobs"))
            Thread(::loop, "simbridge-poll").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
    }

    private fun notification(text: String): Notification {
        val ch = "simbridge"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "SimBridge", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, ch)
            .setContentTitle("SimBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .build()
    }

    private fun status(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    @Suppress("DEPRECATION")
    private fun loop() {
        val p = prefs(this)
        val url = p.getString(K_URL, "").orEmpty()
        val token = p.getString(K_TOKEN, "").orEmpty()
        while (running) {
            try {
                val body = fetch(url, token)
                if (body.isNotEmpty()) handle(JSONObject(body))
            } catch (e: Exception) {
                // Network blips and VPS restarts are normal; back off and retry.
                Log.w(TAG, "poll failed: ${e.message}")
                status("retrying: ${e.message}")
                Thread.sleep(5_000)
            }
        }
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

    private fun handle(job: JSONObject) {
        val to = job.getString("to")
        val sim = job.optInt("sim", 0)
        when (job.getString("type")) {
            "call" -> { status("calling $to"); call(to, sim, job.optInt("seconds", 45)) }
            "sms" -> { status("texting $to"); sms(to, job.getString("text"), sim) }
            else -> Log.w(TAG, "unknown job type in $job")
        }
        status("waiting for jobs")
    }

    @SuppressLint("MissingPermission")
    private fun call(to: String, sim: Int, seconds: Int) {
        val i = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", to, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        phoneAccount(sim)?.let { i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        startActivity(i)
        Thread.sleep(seconds * 1000L)
        hangUp()
    }

    @SuppressLint("MissingPermission")
    private fun phoneAccount(sim: Int) = try {
        if (sim <= 0) null
        else getSystemService(TelecomManager::class.java)
            .callCapablePhoneAccounts.getOrNull(sim - 1)
    } catch (e: SecurityException) {
        Log.w(TAG, "cannot list phone accounts: ${e.message}"); null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun hangUp() {
        if (Build.VERSION.SDK_INT < 28) return    // no endCall API before Android 9
        val ended = try {
            getSystemService(TelecomManager::class.java).endCall()
        } catch (e: SecurityException) {
            false
        }
        // ponytail: Android 10+ may restrict endCall to the default phone app. If
        // this logs, the fix is to request the dialer role -- not more retries.
        if (!ended) Log.w(TAG, "endCall refused; set SimBridge as the default phone app")
    }

    @SuppressLint("MissingPermission")
    private fun sms(to: String, text: String, sim: Int) {
        val m = smsManager(sim)
        // divideMessage handles >160 chars; sendTextMessage would silently truncate.
        m.sendMultipartTextMessage(to, null, m.divideMessage(text), null, null)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(sim: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
                   else SmsManager.getDefault()
        val id = subId(sim)
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base
        return if (Build.VERSION.SDK_INT >= 31) base.createForSubscriptionId(id)
               else SmsManager.getSmsManagerForSubscriptionId(id)
    }

    @SuppressLint("MissingPermission")
    private fun subId(sim: Int): Int {
        if (sim <= 0) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return try {
            getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList?.getOrNull(sim - 1)?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (e: SecurityException) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }
}


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (prefs(c).getString(K_URL, "").isNullOrEmpty()) return
        c.startForegroundService(Intent(c, PollService::class.java))
    }
}
