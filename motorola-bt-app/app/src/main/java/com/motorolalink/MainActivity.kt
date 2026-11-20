package com.motorolalink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), BluetoothListener {

    private lateinit var controller: BluetoothController
    private lateinit var statusText: TextView
    private lateinit var audioRouteText: TextView
    private lateinit var listDevices: ListView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnVoice: MaterialButton

    private var devices: List<BluetoothDevice> = emptyList()
    private var voiceActive = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                onPermissionsGranted()
            } else {
                statusText.text = getString(R.string.status_permissions_needed)
            }
        }

    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleVoiceChannel() else toast(R.string.toast_mic_required)
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                onPermissionsGranted()
            } else {
                statusText.text = getString(R.string.status_bt_disabled)
            }
        }

    private lateinit var adapter: ArrayAdapter<String>

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controller = BluetoothController(this, this)

        statusText = findViewById(R.id.textStatus)
        audioRouteText = findViewById(R.id.textAudioRoute)
        listDevices = findViewById(R.id.listDevices)
        btnScan = findViewById(R.id.btnScan)
        btnVoice = findViewById(R.id.btnVoice)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listDevices.adapter = adapter
        listDevices.setOnItemClickListener { _, _, position, _ ->
            val device = devices.getOrNull(position) ?: return@setOnItemClickListener
            controller.connect(device)
        }

        btnScan.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            if (!controller.isEnabled()) {
                enableBtLauncher.launch(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                return@setOnClickListener
            }
            controller.startDiscovery()
        }

        btnVoice.setOnClickListener {
            if (voiceActive) {
                controller.stopVoiceChannel()
                voiceActive = false
                btnVoice.text = getString(R.string.btn_voice_open)
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    toggleVoiceChannel()
                } else {
                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        if (!ensurePermissions()) {
            statusText.text = getString(R.string.status_permissions_needed)
            return
        }
        onPermissionsGranted()
    }

    @SuppressLint("MissingPermission")
    private fun onPermissionsGranted() {
        if (!controller.isEnabled()) {
            statusText.text = getString(R.string.status_bt_disabled)
            return
        }
        devices = controller.pairedDevices()
        updateList()
        statusText.text = getString(R.string.status_ready)
    }

    private fun ensurePermissions(): Boolean {
        val missing = controller.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) true else {
            permissionLauncher.launch(missing.toTypedArray())
            false
        }
    }

    private fun toggleVoiceChannel() {
        controller.startVoiceChannel()
        voiceActive = true
        btnVoice.text = getString(R.string.btn_voice_close)
    }

    private fun updateList() {
        adapter.clear()
        @SuppressLint("MissingPermission")
        val items = devices.map { d ->
            val paired = if (d.bondState == BluetoothDevice.BOND_BONDED) " (paired)" else ""
            (d.name ?: getString(R.string.label_unknown)) + paired
        }
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        controller.register()
    }

    override fun onStop() {
        super.onStop()
        controller.unregister()
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread { statusText.text = status }
    }

    override fun onDeviceListChanged(devices: List<BluetoothDevice>) {
        runOnUiThread {
            this.devices = devices
            updateList()
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, device: BluetoothDevice?) {
        runOnUiThread {
            audioRouteText.text = getString(
                if (connected) R.string.route_connected_prefix else R.string.route_disconnected
            )
        }
    }

    override fun onVoiceChannelChanged(active: Boolean) {
        runOnUiThread {
            voiceActive = active
            btnVoice.text = getString(
                if (active) R.string.btn_voice_close else R.string.btn_voice_open
            )
            audioRouteText.text = if (active) {
                getString(R.string.route_voice_active)
            } else {
                getString(R.string.route_waiting)
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
