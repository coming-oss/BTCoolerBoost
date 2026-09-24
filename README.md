# 散热器极速控制 (BTCoolerBoost)

一个基于**真实 BLE 抓包**（btsnoop）解析出的协议，控制蓝牙磁吸散热器
（抓包来自「红魔磁吸散热器 6 Pro」）锁定最高档的 Android App。

## 先说清楚：这不是“超频”

- 蓝牙散热器是**被动 BLE 设备**，手机只能「写档位 + 读状态」。
- 抓包显示设备只有两档：`28 01`（高档）/ `28 00`（低档）。
- **不存在能让 TEC 半导体制冷片功率超过出厂硬件的“超频寄存器”。**
- 本 App 能做到的物理极限 = **强制锁定厂商最高档 + 不被系统自动降频**。

## 抓包解析出的协议

| 项目 | 值 |
|---|---|
| 设备名 0x2A00 | handle 0x0037 |
| 固件 0x2A26 | handle 0x0014 → "V6.1.5" |
| 电池 0x2A19 | handle 0x001f |
| 控制/通知 | handle 0x001B，私有帧 `AA ... DD` |
| 档位 | 帧内 `28 <lv>`，lv=0 低 / lv=1 高 |

> 注意：厂商私有服务 UUID 未在 HCI 层完整暴露，
> 代码里 `onServicesDiscovered` 做了 **handle / 可写特征回退匹配**，
> 若你的设备 UUID 与此不同，请用 nRF Connect 读出实际 UUID 后替换 companion object 中的常量。

## 编译

需要 Android Studio / Gradle：
```
cd BTCoolerBoost
./gradlew assembleDebug
```

## 目录
```
app/src/main/java/com/cooler/bleboost/CoolerController.kt   # BLE 核心
app/src/main/java/com/cooler/bleboost/MainActivity.kt       # UI
app/src/main/AndroidManifest.xml
app/build.gradle
build.gradle
```

## 使用
1. 打开 App，授权蓝牙权限。
2. 「扫描设备」→ 点选你的散热器。
3. 连接后点「锁定最高档 (更冷)」，即发送厂商最高档指令。
4. 想省电点「切回低档」。