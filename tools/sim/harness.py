"""桌面模擬器: 在 CPython 上跑韌體 UI, 輸出截圖。"""
import sys, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
FW = os.path.join(HERE, "..", "..", "firmware")
sys.path.insert(0, HERE)
sys.path.insert(0, FW)
os.chdir(FW)

# ---- MicroPython time 擴充 ----
class Clock:
    now = 1_000_000
clock = Clock()
time.ticks_ms = lambda: clock.now & 0x3FFFFFFF
time.ticks_diff = lambda a, b: ((a - b + 2**29) % 2**30) - 2**29
time.ticks_add = lambda a, b: (a + b) & 0x3FFFFFFF
time.sleep_ms = lambda ms: None
import gc as _gc
_gc.mem_free = lambda: 100000
_gc.mem_alloc = lambda: 50000

import numpy as np
from PIL import Image
import config, keys as K
config.BL_CONTROL = True      # 模擬器測試背光狀態機
from gfx import Font, Canvas
import state as S


class FakeLCD:
    def __init__(self):
        self.frame = None
        self.frames = 0
    def show(self, buf):
        self.frame = bytes(buf)
        self.frames += 1
    def backlight(self, on): pass
    def set_bl(self, pct): self.bl = pct
    def display(self, on): self.disp = on
    def png(self, path, scale=4):
        a = np.frombuffer(self.frame, dtype=">u2").reshape(80, 160).astype(np.uint32)
        r = ((a >> 11) & 31) << 3; g = ((a >> 5) & 63) << 2; b = (a & 31) << 3
        im = Image.fromarray(np.dstack([r, g, b]).astype(np.uint8), "RGB")
        im = im.resize((160 * scale, 80 * scale), Image.NEAREST)
        im.save(path)
        return im


class FakeLink:
    def __init__(self):
        self.is_online = True
        self.connected = True
        self.inbox = []
        self.sent = []
        self.mtu = 23
        self.n_conn = self.n_disc = self.last_up = 0
    def online(self, now): return self.is_online
    def poll(self, now):
        m, self.inbox = self.inbox, []
        return m
    def send(self, d):
        self.sent.append(d); return True
    def pump(self): pass
    def drop_rx(self): self.rx_drops = getattr(self, "rx_drops", 0) + 1
    def disconnect(self): self.sent.append("disconnect")


class Rig:
    def __init__(self, settings_override=None):
        self.lcd = FakeLCD()
        self.link = FakeLink()
        self.raw = [False] * 5
        self.keys = K.Keys(pins=[None] * 5)
        self.keys.read_raw = lambda: list(self.raw)
        st = S.State()
        st.cfg.d.update({"calib": 1})
        st.cfg.save = lambda: None
        if settings_override:
            st.cfg.d.update(settings_override)
        self.st = st
        font = Font(os.path.join(FW, config.FONT_FILE))
        self.canvas = Canvas(160, 80, font)
        import app as A
        self.rebooted = False
        self.app = A.App(self.canvas, self.lcd, self.keys, self.link, st, clock.now,
                         reboot=lambda: setattr(self, "rebooted", True))

    def run(self, ms, step=10):
        end = clock.now + ms
        while clock.now < end:
            clock.now += step
            self.app.step(clock.now)

    def msg(self, **m):
        self.link.inbox.append(m)

    # 按鍵動作
    def press(self, k, hold_ms=80):
        self.raw[k] = True
        self.run(hold_ms)
        self.raw[k] = False
        self.run(40)

    def click(self, k, wait=True):
        self.press(k, 80)
        if wait:
            self.run(config.DBL_MS + 60)

    def double(self, k, wait=True):
        self.press(k, 70); self.run(70); self.press(k, 70)
        if wait: self.run(config.DBL_MS + 60)

    def triple(self, k):
        for _ in range(3):
            self.press(k, 60); self.run(60)
        self.run(config.DBL_MS + 60)

    def hold(self, k, ms):
        self.raw[k] = True
        self.run(ms)
        self.raw[k] = False
        self.run(60)

    def shot(self, name, outdir):
        self.run(60)
        self.app.draw(clock.now) if False else None
        # 強制重畫
        self.app._last_draw = -10**6
        self.app._sig = None
        self.app.cur.dirty = True
        self.run(50)
        return self.lcd.png(os.path.join(outdir, name + ".png"))
