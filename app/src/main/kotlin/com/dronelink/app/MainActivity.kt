package com.dronelink.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etSsid: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var btnJoin: Button
    private lateinit var btnStop: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSsid = findViewById(R.id.et_ssid)
        tvStatus = findViewById(R.id.tv_status)
        tvNetwork = findViewById(R.id.tv_network)
        btnJoin = findViewById(R.id.btn_join)
        btnStop = findViewById(R.id.btn_stop)

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

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        tvStatus.text = when (JoinStatus.state) {
            JoinState.IDLE -> "Idle. Enter SSID and tap Join."
            JoinState.REQUESTING -> "Requesting join: ${JoinStatus.detail}"
            JoinState.CONNECTED -> "Joined: ${JoinStatus.detail}"
            JoinState.LOST -> "Lost: ${JoinStatus.detail}"
            JoinState.UNAVAILABLE -> "Unavailable: ${JoinStatus.detail}"
        }
        tvNetwork.text = activeNetworkSummary()
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
