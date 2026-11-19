package dev.omshutter.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;

final class GattCompat {
    private GattCompat() {}

    @SuppressLint("MissingPermission") // The service checks CONNECT and catches revocation races.
    @SuppressWarnings("deprecation")
    static boolean writeCharacteristic(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothStatusCodes.SUCCESS;
        }
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!characteristic.setValue(value)) return false;
        return gatt.writeCharacteristic(characteristic);
    }

    @SuppressLint("MissingPermission") // The service checks CONNECT and catches revocation races.
    @SuppressWarnings("deprecation")
    static boolean writeDescriptor(
            BluetoothGatt gatt,
            BluetoothGattDescriptor descriptor,
            byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
        }
        if (!descriptor.setValue(value)) return false;
        return gatt.writeDescriptor(descriptor);
    }

}
