"""系統總覽: 開發板自己看得到的即時狀態 (手機 + 開發板), 上下切換 4 頁: 總覽 / 運作 / 記憶體 / 連線。"""
from app import Page
from keys import *
from ui import *
import time
import gc

NAMES = ("總覽", "運作", "記憶體", "連線")
N = 4
PM_NAMES = ("正常", "微暗", "低亮度", "熄屏")
RESET = {1: "上電", 2: "按RST", 3: "看門狗", 4: "睡眠喚醒", 5: "軟重啟"}
STALE_S = 60


def dur(s):
    if s >= 86400:
        return "%d天%d時" % (s // 86400, s % 86400 // 3600)
    if s >= 3600:
        return "%d時%d分" % (s // 3600, s % 3600 // 60)
    if s >= 60:
        return "%d分%d秒" % (s // 60, s % 60)
    return "%d秒" % s


def kb(n):
    return "%dKB" % (n // 1024)


def size(n):
    if n >= 1073741824:
        return "%.0fGB" % (n / 1073741824)
    return "%.1fMB" % (n / 1048576)


def mcu_temp():
    try:
        import esp32
        return esp32.mcu_temperature()
    except Exception:
        return None


def reset_cause():
    try:
        import machine
        return RESET.get(machine.reset_cause(), "未知")
    except Exception:
        return "--"


def flash_free():
    try:
        import os
        s = os.statvfs("/")
        return s[1] * s[3]
    except Exception:
        return None


class DevPage(Page):
    title = "系統總覽"
    reset_label = "重新同步"

    def __init__(self, app):
        Page.__init__(self, app)
        self.sub = 0
        self._t = 0

    def on_reset(self, now):
        self.app.link.send({"c": "sync"})

    def get_title(self):
        return "系統·%s %d/%d" % (NAMES[self.sub], self.sub + 1, N)

    def tick(self, now):
        d = self.dirty
        self.dirty = False
        if time.ticks_diff(now, self._t) >= 1000:      # 每秒自動更新 (即時數字)
            self._t = now
            d = True
        return d

    def on_key(self, k, ev, now):
        self.dirty = True
        if k in (K_UP, K_DOWN) and ev in (EV_CLICK, EV_HOLD, EV_LONG):
            self.sub = (self.sub + (1 if k == K_DOWN else -1)) % N
            return True
        if k == K_CENTER and self.sub == 2 and ev == EV_CLICK:
            self.app.free_mem()
            return True
        if k in (K_UP, K_DOWN, K_CENTER):
            return ev in (EV_PRESS, EV_RELEASE, EV_CLICK, EV_DOUBLE, EV_TRIPLE)
        return False

    def _row(self, c, y, name, val, col=FG):
        c.text(name, 6, y, DIM)
        c.text_r(val, 154, y, col)

    # ---------- 各分頁 ----------
    def pg_all(self, c, now):
        st = self.st
        app = self.app
        s = st.sys
        ok = bool(s) and time.ticks_diff(now, st.sys_t) // 1000 < STALE_S
        g = s.get if s else (lambda k, d=0: d)
        c.vline(80, TOP_H + 1, 66, FAINT)
        c.text("手機", 4, 14, ACC)
        c.text("開發板", 86, 14, ACC)
        # --- 手機 ---
        b = st.batt
        bc = DIM if not ok else (GOOD if b > 40 else (WARN if b > 20 else BAD))
        c.text("電量", 4, 28, DIM)
        c.text_r(("%d%%" % b) if b >= 0 else "--", 76, 28, bc)
        nt = g("nt", 0)
        c.text("網路", 4, 41, DIM)
        c.text_r(("無", "Wi-Fi", "行動", "其他")[nt] if nt < 4 else "?", 76, 41, (BAD if nt == 0 else GOOD) if ok else DIM)
        bad = g("br", 0) or g("bk", 10) > 30
        c.text("背景", 4, 54, DIM)
        c.text_r(("受限" if bad else "正常") if ok else "--", 76, 54, (BAD if bad else GOOD) if ok else DIM)
        ra = g("ra", 0)
        c.text("RAM", 4, 67, DIM)
        c.text_r(("%d%%" % ra) if ok else "--", 76, 67, (BAD if ra >= 90 else (WARN if ra >= 80 else FG)) if ok else DIM)
        # --- 開發板 ---
        up = time.ticks_diff(now, app.t_boot) // 1000
        c.text("運作", 86, 28, DIM)
        c.text_r(dur(up), 154, 28, FG)
        f = gc.mem_free()
        tot = f + gc.mem_alloc()
        pct = f * 100 // max(1, tot)
        c.text("RAM", 86, 41, DIM)
        c.text_r("剩%d%%" % pct, 154, 41, BAD if pct < 15 else (WARN if pct < 30 else GOOD))
        on = app.link.online(now)
        c.text("藍牙", 86, 54, DIM)
        c.text_r("已連線" if on else "斷線", 154, 54, GOOD if on else BAD)
        t = mcu_temp()
        c.text("溫度", 86, 67, DIM)
        c.text_r(("%.0f°C" % t) if t is not None else "--", 154, 67, (FG if t < 60 else WARN) if t is not None else DIM)

    def pg_run(self, c, now):
        app = self.app
        from config import VERSION
        up = time.ticks_diff(now, app.t_boot) // 1000
        self._row(c, 14, "韌體版本", "v" + VERSION)
        self._row(c, 27, "已運作", dur(up))
        self._row(c, 40, "目前頁面", registry_name(app.cur_id))
        self._row(c, 53, "螢幕狀態", PM_NAMES[min(app.pm.state, 3)])
        self._row(c, 66, "重啟原因", reset_cause(), DIM)

    def pg_mem(self, c, now):
        f = gc.mem_free()
        a = gc.mem_alloc()
        tot = max(1, f + a)
        used = a * 100 // tot
        col = BAD if used >= 85 else (WARN if used >= 70 else GOOD)
        c.text("已用 %d%%" % used, 6, 14, col)
        c.text_r("剩 " + kb(f), 154, 14, FG)
        c.frect(6, 28, 148, 6, FAINT)
        c.frect(6, 28, max(2, 148 * used // 100), 6, col)
        c.text("已用 " + kb(a), 6, 40, DIM)
        c.text_r("總共 " + kb(tot), 154, 40, DIM)
        c.text("程式頁面 %d 個在記憶體中" % len(self.app.insts), 6, 52, DIM)
        ff = flash_free()
        c.text("最低 " + kb(self.app.mem_min), 6, 66, DIM)
        if (now // 3000) % 2 == 0 or ff is None:
            c.text_r("中鍵:清理", 154, 66, ACC)
        else:
            c.text_r("儲存 " + size(ff), 154, 66, DIM)

    def pg_link(self, c, now):
        lk = self.app.link
        on = lk.online(now)
        if on:
            stt, col = "已連線", GOOD
        elif lk.connected:
            stt, col = "App未回應", WARN
        else:
            stt, col = "等待連線", BAD
        self._row(c, 14, "藍牙狀態", stt, col)
        self._row(c, 27, "連線次數", "%d" % lk.n_conn)
        self._row(c, 40, "斷線次數", "%d" % lk.n_disc, WARN if lk.n_disc > 3 else FG)
        self._row(c, 53, "上次連線時長", dur(lk.last_up) if lk.n_disc else "--")
        age = time.ticks_diff(now, self.st.sys_t) // 1000 if self.st.sys else -1
        self._row(c, 66, "手機資料", ("%d秒前" % age) if age >= 0 else "尚未收到", (GOOD if age < 30 else WARN) if age >= 0 else DIM)

    def draw(self, c, now):
        (self.pg_all, self.pg_run, self.pg_mem, self.pg_link)[self.sub](c, now)


def registry_name(pid):
    import registry
    return registry.name_of(pid) or pid
