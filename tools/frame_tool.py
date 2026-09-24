#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
散热器控制帧工具 (协议来自 btsnoop 抓包解析)
用途：验证 AA...DD 私有帧的编码/解码逻辑，不依赖蓝牙硬件。
设备：红魔磁吸散热器 6 Pro 类 BLE 设备 (固件 V6.1.5)
"""

HEAD = 0xAA
TAIL = 0xDD
CMD_LEVEL = 0x28   # 档位命令

def checksum(payload: bytes) -> int:
    """简单累加校验（占位：实际以抓包反推为准）"""
    return sum(payload) & 0xFF

def build_level_frame(level: int) -> bytes:
    """
    构造档位帧：AA 02 28 <lv> <chk> DD
      lv = 0 低档 / 1 高档（抓包中的 28 00 / 28 01）
    """
    lv = 1 if level else 0
    body = bytes([CMD_LEVEL, lv])
    chk = checksum(body)
    return bytes([HEAD, len(body)]) + body + bytes([chk, TAIL])

def parse_frame(data: bytes):
    """解析一条私有帧，返回 (cmd, value, raw)"""
    if len(data) < 4 or data[0] != HEAD:
        return None
    ln = data[1]
    body = data[2:2+ln]
    if len(body) < 2:
        return None
    return body[0], body[1], data.hex()

def decode_notify_hex(hexstr: str):
    """
    解析抓包里的 Notify 载荷（可含多条/带私有段）
    例: 1b2900aa002d4d002d4d280000000000bc23dd
    找出其中 AA...DD 段并解析 28 xx 档位
    """
    raw = bytes.fromhex(hexstr)
    out = []
    i = 0
    while i < len(raw):
        if raw[i] == HEAD:
            j = raw.find(TAIL, i)
            if j == -1:
                break
            seg = raw[i:j+1]
            out.append(seg)
            i = j + 1
        else:
            i += 1
    return out

if __name__ == "__main__":
    print("=== 档位帧生成 ===")
    for lv in (0, 1):
        f = build_level_frame(lv)
        print(f"  level={lv} -> {f.hex(' ').upper()}")

    print("\n=== 帧解析 ===")
    for lv in (0, 1):
        f = build_level_frame(lv)
        print(f"  {f.hex(' ').upper()} -> cmd=0x{parse_frame(f)[0]:02X} value={parse_frame(f)[1]}")

    print("\n=== 抓包 Notify 载荷解析 ===")
    samples = [
        "1b2900aa002d4d002d4d280000000000bc23dd",
        "1b2900aa001c49001c48280101000000f121dd",
    ]
    for s in samples:
        segs = decode_notify_hex(s)
        print(f"  {s}")
        for seg in segs:
            print(f"     -> 私有段: {seg.hex(' ').upper()}")
            # 在段内找 28 xx
            for k in range(len(seg)-1):
                if seg[k] == 0x28:
                    print(f"        档位字节: 28 {seg[k+1]:02X}  (={'高档' if seg[k+1] else '低档'})")