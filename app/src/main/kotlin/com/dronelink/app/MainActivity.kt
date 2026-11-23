package com.dronelink.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dronelink.app.debug.DebugLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class MainActivity : AppCompatActivity() {

    private lateinit var etSsid: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvDebug: TextView
    private lateinit var svDebug: ScrollView
    private lateinit var imgSnapshot: ImageView
    private lateinit var btnJoin: Button
    private lateinit var btnStop: Button
    private lateinit var btnEmergencyLand: Button

    /** Auto-refresh ticker: keeps status + debug log live without a button. */
    private val uiTicker = object : Runnable {
        override fun run() {
            refresh()
            uiHandler.postDelayed(this, 750)
        }
    }
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSsid = findViewById(R.id.et_ssid)
        tvStatus = findViewById(R.id.tv_status)
        tvNetwork = findViewById(R.id.tv_network)
        tvDebug = findViewById(R.id.tv_debug)
        svDebug = findViewById(R.id.sv_debug)
        imgSnapshot = findViewById(R.id.img_snapshot)
        btnJoin = findViewById(R.id.btn_join)
        btnStop = findViewById(R.id.btn_stop)
        btnEmergencyLand = findViewById(R.id.btn_emergency_land)

        val prefs = getSharedPreferences("dronelink", Context.MODE_PRIVATE)
        etSsid.setText(prefs.getString("ssid", getString(R.string.default_ssid)))

        btnJoin.setOnClickListener {
            if (!hasPermissions()) {
                permissionLauncher.launch(requiredPermissions())
                return@setOnClickListener
            }
            val ssid = etSsid.text.toString().trim()
            if (ssid.isEmpty()) {
                tvStatus.text = "SSID cannot be empty"
                return@setOnClickListener
            }
            prefs.edit().putString("ssid", ssid).apply()
            val intent = Intent(this, DroneLinkService::class.java)
                .putExtra(DroneLinkService.EXTRA_SSID, ssid)
            startForegroundService(intent)
            tvStatus.text = "Requesting join to \"$ssid\" — tap the system consent panel."
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, DroneLinkService::class.java))
        }

        btnEmergencyLand.setOnClickListener {
            // Toggle: "Emergency Land" -> land + hold + gate AI control (MCP
            // refuses tools until re-armed); "Enable MCP" -> re-arm (gate cleared).
            // The drone control link stays connected in both directions.
            val tool = if (btnEmergencyLand.text.toString() == "Enable MCP") "rearm_mcp" else "emergency_land"
            val args = if (tool == "emergency_land") JSONObject().put("gate", true) else null
            mcpCall(tool, args) { ok ->
                runOnUiThread {
                    if (ok) {
                        btnEmergencyLand.text =
                            if (tool == "emergency_land") "Enable MCP" else "Emergency Land"
                        refresh()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_flyhome).setOnClickListener { mcpCall("fly_home", null) }
        findViewById<Button>(R.id.btn_snapshot).setOnClickListener { snapshot() }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.postDelayed(uiTicker, 750)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiTicker)
        super.onPause()
    }

    // ------------------------------------------------------------------ status

    private fun refresh() {
        tvStatus.text = when (JoinStatus.state) {
            JoinState.IDLE -> "Idle. Enter SSID and tap Join."
            JoinState.REQUESTING -> "Requesting join: ${JoinStatus.detail}"
            JoinState.CONNECTED -> "Joined: ${JoinStatus.detail}"
            JoinState.LOST -> "Lost: ${JoinStatus.detail}"
            JoinState.UNAVAILABLE -> "Unavailable: ${JoinStatus.detail}"
            JoinState.KILLED -> "KILL SWITCH: ${JoinStatus.detail}"
        }
        tvNetwork.text = activeNetworkSummary()
        // Reflect the kill-switch state on the emergency button.
        btnEmergencyLand.text =
            if (JoinStatus.state == JoinState.KILLED) "Enable MCP" else "Emergency Land"
        // Debug log panel: last 80 entries.
        val lines = DebugLog.entries().takeLast(80).joinToString("\n") { "${it.stamp} [${it.tag}] ${it.message}" }
        tvDebug.text = lines
        svDebug.post { svDebug.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun activeNetworkSummary(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "no active network"
        val caps = cm.getNetworkCapabilities(active) ?: return "active ${active} (no caps)"
        val transports = buildList {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (isEmpty()) add(caps.toString())
        }
        val internet = if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) "internet" else "no-internet"
        return "active ${active} [${transports.joinToString(",")}] $internet"
    }

    // ------------------------------------------------------- MCP + snapshot UI

    /** Call a tool on the local MCP server (127.0.0.1:8000). */
    private fun mcpCall(tool: String, args: JSONObject?, onResult: ((ok: Boolean) -> Unit)? = null) {
        DebugLog.log("ui", "mcp call: $tool")
        Thread {
            try {
                val req = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "tools/call")
                    .put("params", JSONObject()
                        .put("name", tool)
                        .put("arguments", args ?: JSONObject()))
                val conn = URL("http://127.0.0.1:8000/mcp").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(req.toString().toByteArray()) }
                val code = conn.responseCode
                val body = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 200) {
                    val res = JSONObject(body)
                    if (res.has("error")) {
                        DebugLog.log("ui", "$tool error: ${res.getJSONObject("error").optString("message")}")
                        onResult?.invoke(false)
                    } else {
                        val text = res.getJSONObject("result")
                            .getJSONArray("content").getJSONObject(0).getString("text")
                        DebugLog.log("ui", "$tool -> $text")
                        onResult?.invoke(true)
                        if (tool == "snapshot") showSnapshot(text)
                    }
                } else {
                    DebugLog.log("ui", "$tool http $code: ${body.take(120)}")
                    onResult?.invoke(false)
                }
            } catch (e: Exception) {
                DebugLog.log("ui", "$tool failed: ${e.message}")
                onResult?.invoke(false)
            } finally {
                runOnUiThread { refresh() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun snapshot() {
        DebugLog.log("ui", "snapshot requested")
        mcpCall("snapshot", null)
    }

    private fun showSnapshot(resultText: String) {
        try {
            val call = JSONObject(resultText)
            if (!call.optBoolean("ok", false)) {
                DebugLog.log("ui", "snapshot: ${call.optString("error")}")
                return
            }
            val bytes = Base64.getDecoder().decode(call.getString("jpeg_base64"))
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            runOnUiThread {
                if (bmp != null) {
                    imgSnapshot.setImageBitmap(bmp)
                    imgSnapshot.visibility = android.view.View.VISIBLE
                }
            }
        } catch (e: Exception) {
            DebugLog.log("ui", "snapshot parse failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------ log export

    private fun copyLog() {
        val text = DebugLog.exportText()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dronelink-log", text))
        Toast.makeText(this, "Log copied (${text.length} chars)", Toast.LENGTH_SHORT).show()
        DebugLog.log("ui", "log copied")
    }

    private fun shareLog() {
        try {
            val text = DebugLog.exportText()
            val f = File(cacheDir, "dronelink-log.txt")
            FileOutputStream(f).use { it.write(text.toByteArray()) }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "DroneLink debug log")
                putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider", f,
                ))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export DroneLink log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ permissions

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.toTypedArray()
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
