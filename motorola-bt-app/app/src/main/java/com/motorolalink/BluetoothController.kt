package com.motorolalink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

interface BluetoothListener {
    fun onStatusChanged(status: String)
    fun onDeviceListChanged(devices: List<BluetoothDevice>)
    fun onConnectionStateChanged(connected: Boolean, device: BluetoothDevice?)
    fun onVoiceChannelChanged(active: Boolean)
}

class BluetoothController(
    private val context: Context,
    private val listener: BluetoothListener
) {

    val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    val hasRequiredPermissions: Boolean
        get() = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var headsetProxy: BluetoothHeadset? = null
    private var lastDevice: BluetoothDevice? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                listener.onStatusChanged("Headset service ready")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!hasRequiredPermissions) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?.let { onDeviceFound(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener.onStatusChanged("Discovery finished")
                    publishDiscovered()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    when (state) {
                        BluetoothDevice.BOND_BONDED ->
                            listener.onStatusChanged("Paired: ${device?.name ?: device?.address}")
                        BluetoothDevice.BOND_NONE ->
                            listener.onStatusChanged("Pairing cancelled/failed")
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let {
                        lastDevice = it
                        listener.onConnectionStateChanged(true, it)
                        listener.onStatusChanged("Connected: ${it.name ?: it.address}")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let {
                        listener.onConnectionStateChanged(false, it)
                        listener.onStatusChanged("Disconnected: ${it.name ?: it.address}")
                    }
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    )
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        listener.onVoiceChannelChanged(true)
                        listener.onStatusChanged("Voice channel (SCO) open")
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        listener.onVoiceChannelChanged(false)
                    }
                }
            }
        }
    }

    fun register() {
        if (adapter == null) {
            listener.onStatusChanged("Bluetooth not supported on this device")
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        context.registerReceiver(receiver, filter)
        adapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        adapter?.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy ?: return)
        headsetProxy = null
    }

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun requestEnable(): Boolean = adapter?.enable() == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList().orEmpty()

    private val discovered = mutableSetOf<BluetoothDevice>()

    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        discovered.clear()
        val started = adapter?.startDiscovery() == true
        listener.onStatusChanged(if (started) "Scanning…" else "Scan failed")
        if (started) publishDiscovered()
        return started
    }

    @SuppressLint("MissingPermission")
    private fun publishDiscovered() {
        if (!hasRequiredPermissions) return
        val all = discovered + (adapter?.bondedDevices.orEmpty())
        listener.onDeviceListChanged(all.filter { it.name != null || it.bondState != -1 })
    }

    @SuppressLint("MissingPermission")
    fun onDeviceFound(device: BluetoothDevice) {
        discovered.add(device)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        if (!hasRequiredPermissions) {
            listener.onStatusChanged("Missing permissions")
            return false
        }
        lastDevice = device
        adapter?.cancelDiscovery()

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            listener.onStatusChanged("Pairing with ${device.name ?: device.address}…")
            return try {
                device.createBond()
            } catch (e: SecurityException) {
                listener.onStatusChanged("Pairing blocked: ${e.message}")
                false
            }
        }

        val proxy = headsetProxy
        if (proxy != null) {
            val ok = try {
                proxy.connect(device)
            } catch (e: SecurityException) {
                false
            }
            if (ok) {
                listener.onStatusChanged("Connecting to ${device.name ?: device.address}…")
                return true
            }
        }
        listener.onStatusChanged("Open the paired device in system settings to enable audio")
        return false
    }

    @SuppressLint("MissingPermission")
    fun disconnect(device: BluetoothDevice): Boolean {
        val proxy = headsetProxy ?: return false
        return try {
            proxy.disconnect(device)
        } catch (e: SecurityException) {
            false
        }
    }

    fun startVoiceChannel(): Boolean {
        if (audioManager.bluetoothScoOn) return true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        return true
    }

    fun stopVoiceChannel() {
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun connectedHeadset(): BluetoothDevice? {
        if (!hasRequiredPermissions) return null
        return headsetProxy?.connectedDevices?.firstOrNull()
    }

    fun activeOutputName(): String {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val sco = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val a2dp = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val dev = sco ?: a2dp ?: return "Phone speaker"
        return when (dev.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (voice)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (media)"
            else -> dev.productName?.toString()?.ifEmpty { null } ?: "Speaker"
        }
    }
}
