package app.tether.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.os.Build

/** GATT server notifications across Android versions (the value-carrying overload only exists from Android 13). */
object GattCompat {
    const val SUCCESS = 0
    const val FAILED = 3

    /** Sends [value] as a notification; returns [SUCCESS] or a failure code, like the Android 13 overload. */
    @SuppressLint("MissingPermission")
    fun notify(server: BluetoothGattServer, device: BluetoothDevice, c: BluetoothGattCharacteristic, value: ByteArray): Int {
        if (Build.VERSION.SDK_INT >= 33) return server.notifyCharacteristicChanged(device, c, false, value)
        // Below 13 the value travels in the characteristic itself: set and send under one lock.
        return synchronized(c) {
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            if (server.notifyCharacteristicChanged(device, c, false)) SUCCESS else FAILED
        }
    }
}
