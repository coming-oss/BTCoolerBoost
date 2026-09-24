package com.cooler.bleboost

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BLE 散热器控制核心
 *
 * 协议来自 btsnoop 抓包解析：
 *  - 设备名特征 0x2A00 @ handle 0x0037
 *  - 固件       0x2A26 @ handle 0x0014 ("V6.1.5")
 *  - 控制写入   0x001B（私有通道，帧格式 AA ... DD）
 *  - 档位字节   帧内偏移见 sendLevel()，28 01=高档 / 28 00=低档
 *
 * 说明：这是被动设备，只有厂商既定档位，不存在真正的“超频寄存器”。
 * 本类提供的最大能力 = 锁定厂商最高档 + 持续保持，不做降频。
 */
class CoolerController(private val context: Context) {

    companion object {
        const val TAG = "CoolerBoost"

        // 标准服务/特征
        val SVC_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val CHR_FIRMWARE:   UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val SVC_BATTERY:    UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CHR_BATTERY:    UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // 私有控制通道（抓包得到的 handle 0x001B 对应特征）
        // 若厂商 UUID 为随机值，App 内会按 handle 回退匹配（见 onServicesDiscovered）
        val SVC_PRIVATE:    UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val CHR_CONTROL:    UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val CHR_NOTIFY:     UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

        // 帧头帧尾（抓包确认）
        const val FRAME_HEAD = 0xAA.toByte()
        const val FRAME_TAIL = 0xDD.toByte()

        const val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val main = Handler(Looper.getMainLooper())

    var onState: ((String) -> Unit)? = null
    var onLevel: ((Int) -> Unit)? = null       // 上报当前档位
    var onBattery: ((Int) -> Unit)? = null

    fun init(): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bm.adapter
        return adapter != null
    }

    fun connect(address: String) {
        val dev = adapter?.getRemoteDevice(address) ?: run {
            onState?.invoke("地址无效")
            return
        }
        onState?.invoke("连接中…")
        gatt = dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** 发送档位：level 0=低档, 1=高档（抓包中的 28 00 / 28 01） */
    fun sendLevel(level: Int) {
        val c = controlChar ?: return
        val v = level.coerceIn(0, 1)
        // 帧格式：AA 02 28 <lv> checksum DD
        val payload = byteArrayOf(
            FRAME_HEAD, 0x02, 0x28, v.toByte(),
            (0x28 + v).toByte(), FRAME_TAIL
        )
        c.value = payload
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(c)
        }
        onState?.invoke("已发送档位 $v")
    }

    /** 锁定最高档：持续保持 */
    fun boostMax() = sendLevel(1)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState?.invoke("已连接，发现服务…")
                g.discoverServices()
            } else {
                onState?.invoke("已断开")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            g.services.forEach { svc ->
                svc.characteristics.forEach { ch ->
                    when {
                        ch.uuid == CHR_CONTROL -> controlChar = ch
                        ch.uuid == CHR_NOTIFY -> notifyChar = ch
                    }
                }
            }
            // 回退：按 handle 定位私有控制特征（抓包 handle 0x001B）
            if (controlChar == null) {
                g.services.forEach { svc ->
                    svc.characteristics.firstOrNull {
                        it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                        it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    }?.let { if (controlChar == null) controlChar = it }
                }
            }
            notifyChar?.let { c ->
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CCCD)
                if (d != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                }
            }
            onState?.invoke("就绪 (控制特征: ${controlChar?.uuid})")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            parseNotify(value)
        }

        @Deprecated("for API<33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            parseNotify(c.value ?: return)
        }
    }

    private fun parseNotify(v: ByteArray) {
        if (v.isEmpty()) return
        Log.d(TAG, "notify=" + v.joinToString("") { "%02x".format(it) })
        // 帧内查找 28 <lv> 上报档位
        for (i in 0 until v.size - 1) {
            if (v[i] == 0x28.toByte()) {
                val lv = v[i + 1].toInt()
                if (lv == 0 || lv == 1) onLevel?.invoke(lv)
            }
        }
    }

    fun disconnect() {
        gatt?.close(); gatt = null
    }
}