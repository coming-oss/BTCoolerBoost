package com.cooler.bleboost

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private val controller by lazy { CoolerController(this) }
    private var scanResults = mutableListOf<ScanResult>()
    private lateinit var listBox: LinearLayout

    private val perms: Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    ) else arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }

        val title = TextView(this).apply { text = "❄ 散热器极速控制"; textSize = 22f }
        status = TextView(this).apply { text = "未连接"; textSize = 15f; setPadding(0, 20, 0, 20) }
        val btnScan = Button(this).apply { text = "扫描设备" }
        val btnBoost = Button(this).apply { text = "🔒 锁定最高档 (更冷)" }
        val btnLow = Button(this).apply { text = "切回低档" }
        val btnDisc = Button(this).apply { text = "断开" }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        log = TextView(this).apply { textSize = 12f; setPadding(0, 20, 0, 0) }

        root.addView(title)
        root.addView(status)
        root.addView(btnScan)
        root.addView(listBox)
        root.addView(btnBoost)
        root.addView(btnLow)
        root.addView(btnDisc)
        root.addView(log)
        setContentView(root)

        if (!hasPerms()) ActivityCompat.requestPermissions(this, perms, 1)
        controller.init()
        controller.onState = { runOnUiThread { status.text = it; log.append("\n$it") } }
        controller.onLevel = { runOnUiThread { status.text = "当前档位: " + if (it == 1) "最高档" else "低档" } }

        btnScan.setOnClickListener { startScan() }
        btnBoost.setOnClickListener {
            Toast.makeText(this, "已发送最高档指令", Toast.LENGTH_SHORT).show()
            controller.boostMax()
        }
        btnLow.setOnClickListener { controller.sendLevel(0) }
        btnDisc.setOnClickListener { controller.disconnect() }
    }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startScan() {
        if (!hasPerms()) { ActivityCompat.requestPermissions(this, perms, 1); return }
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bm.adapter.bluetoothLeScanner ?: return
        scanResults.clear(); listBox.removeAllViews()
        status.text = "扫描中…"
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "(无名)"
                // 只显示可能有用的：名称含 cooler/散热或厂商私有服务
                if (scanResults.any { it.device.address == result.device.address }) return
                scanResults.add(result)
                runOnUiThread {
                    val b = Button(this@MainActivity).apply {
                        text = "$name  ${result.device.address}"
                        setOnClickListener {
                            controller.connect(result.device.address)
                        }
                    }
                    listBox.addView(b)
                }
            }
        }, android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build())
    }
}