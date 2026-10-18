import sys, types
from harness import *
class UUID:
    def __init__(s, v): s.v = v
    def __bytes__(s): return b"\x00" * 16
class BLE:
    def __init__(s): s.buf = b""; s.notified = []; s.adv = None
    def active(s, a=None): pass
    def config(s, *a, **k): pass
    def irq(s, f): s.f = f
    def gatts_register_services(s, svcs): return [(10, 11)]
    def gatts_set_buffer(s, h, n, a=False): pass
    def gatts_read(s, h): b, s.buf = s.buf, b""; return b
    def gatts_notify(s, c, h, d): s.notified.append(d)
    def gap_advertise(s, *a, **k): s.adv = k
    def gap_disconnect(s, c): pass
m = types.ModuleType("bluetooth"); m.UUID = UUID; m.BLE = BLE
m.FLAG_NOTIFY = 16; m.FLAG_WRITE = 8; m.FLAG_WRITE_NO_RESPONSE = 4
sys.modules["bluetooth"] = m
import ble
L = ble.Link()
ok = True
def chk(n, c):
    global ok; print(("PASS " if c else "FAIL ") + n); ok &= bool(c)
L._irq(1, (1, 0, b"x"))
chk("connected", L.connected)
def write(b): L.ble.buf += b; L._irq(3, (1, 11))
msg = '{"k":"track","t":"晴天","a":"周杰倫"}\n'.encode()
write(msg[:7]); chk("partial -> nothing", len(L.poll(0)) == 0)
write(msg[7:30]); write(msg[30:] + b'{"k":"ping"}\n{"k":"vo')
r = L.poll(100)
chk("two msgs parsed across fragments", [x["k"] for x in r] == ["track", "ping"] and r[0]["t"] == "晴天")
write(b'l","v":3}\n'); chk("tail completed", L.poll(200)[0]["k"] == "vol")
chk("online after rx", L.online(300)); chk("offline after timeout", not L.online(300 + 26000))
# ---- 大訊息分成 20 位元組片段、多則連發、壞資料 ----
import json as _j
big = _j.dumps({"k": "score", "i": 0, "ev": [["12", 0, "g", "Haaland-%d" % i] for i in range(70)], "sr": {"t": "系列賽", "r": [["戰績", "1-1", "2-2"]] * 4}}, ensure_ascii=False).encode() + b"\n"
chk("big message is > 1.5KB", len(big) > 1500)
for i in range(0, len(big), 20): write(big[i:i + 20])
r = L.poll(400)
chk("big message reassembled from 20B fragments", len(r) == 1 and r[0]["k"] == "score" and len(r[0]["ev"]) == 70)
chk("buffer empty after", L._rxn == 0 and not L._nl and L._rx == [])
for i in range(30): write(('{"k":"vol","v":%d,"m":15}\n' % i).encode())
r = L.poll(500)
chk("30 messages in one poll, in order", [x["v"] for x in r] == list(range(30)))
write(b'{"k":"a"}\n\n\n{"k":"b"}\r\n'); r = L.poll(600)
chk("blank lines and CRLF ok", [x["k"] for x in r] == ["a", "b"])
write(b'{"k":"c","v":1}\n{"k":"d","'); r = L.poll(700)
chk("partial tail kept", [x["k"] for x in r] == ["c"] and L._rxn == len(b'{"k":"d","'))
write(b'v":2}\n'); r = L.poll(800)
chk("partial tail completed later", len(r) == 1 and r[0]["k"] == "d" and r[0]["v"] == 2)
write(b'not json at all\n{"k":"ok"}\n'); r = L.poll(900)
chk("garbage line dropped, next one kept", [x["k"] for x in r] == ["ok"])
write(b"x" * 3000); write(b"y" * 3000)
chk("overflow without newline drops buffer", L._rxn < 4096)
L.drop_rx(); chk("drop_rx clears", L._rxn == 0 and not L._nl)
write(b'{"k":"z"}\n'); chk("works after overflow", L.poll(1000)[0]["k"] == "z")
L.mtu = 23
L.send({"c": "seek", "d": 5000, "pad": "x" * 40})
n = 0
while L._txq and n < 20: L.pump(); n += 1
data = b"".join(L.ble.notified)
chk("chunks <= 20B and reassemble", all(len(c) <= 20 for c in L.ble.notified) and data.endswith(b"\n") and b'"seek"' in data)
L._irq(2, (1, 0, b"x")); chk("disconnect -> advertising again", not L.connected and L.ble.adv is not None)
print("OK" if ok else "FAILED"); sys.exit(0 if ok else 1)
