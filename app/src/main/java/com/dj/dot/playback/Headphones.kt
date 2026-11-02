package com.dj.dot.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The connected Bluetooth earphones and the battery level they report. Android only exposes one
 * overall level to regular apps; per-earbud and case levels are reserved for system apps.
 */
@SuppressLint("MissingPermission")
class Headphones(private val context: Context) {
    data class Info(val name: String, val battery: Int?)

    private val _connected = MutableStateFlow<Info?>(null)
    val connected: StateFlow<Info?> = _connected
    private var started = false

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started || !hasPermission()) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_BATTERY_LEVEL_CHANGED) {
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                val current = _connected.value
                if (current != null && level in 0..100) {
                    _connected.value = current.copy(battery = level)
                    return
                }
            }
            refresh()
        }
    }

    /** Asks the A2DP profile which device is playing and reads its battery. */
    fun refresh() {
        if (!hasPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val device = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                    _connected.value = device?.let { d ->
                        Info(runCatching { d.alias ?: d.name }.getOrNull() ?: "Earphones", batteryOf(d))
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }, BluetoothProfile.A2DP)
        }
    }

    /** The level the earphones report over Bluetooth, if they report one. */
    private fun batteryOf(device: BluetoothDevice): Int? = runCatching {
        device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrNull()?.takeIf { it in 0..100 }

    private companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
