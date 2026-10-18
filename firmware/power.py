"""省電狀態機 (背光 PWM 階梯調光 + 時鐘休眠 + 完全熄屏 + 選用 Light Sleep)。
A 活躍 -> D 微昏(預設閒置60秒, 背光減半; 可在設定調整) -> L 低亮度(預設閒置10分鐘, 15%; 沒播歌時自動切到首頁當時鐘)
-> O 熄屏(依「自動熄屏」設定; 背光 0 且面板進入 Sleep)。按鍵或手機開始播放即喚醒。"""
import time
import gc
from config import *
from state import PL_PLAY

A, D, L, O = 0, 1, 2, 3
LOW_PCT = 15


class Power:
    def __init__(self, app):
        self.app = app
        self.state = A
        self.saved = None          # 進入時鐘休眠前的頁面
        self.off_req = False       # 快捷鍵要求立刻熄屏: 直到下一次按鍵才解除
        self.lvl = -1
        self._ls_ready = False
        self._ls_bad = False
        self.apply_level()

    def playing(self, now):
        a = self.app
        return a.st.has_track and a.st.pl == PL_PLAY and a.link.online(now)

    def pct(self):
        cfg = self.app.st.cfg
        b = cfg.get("bright")
        s = self.state
        if s == A:
            return b
        if s == D:
            return max(10, b // 2)
        if s == L:
            return min(LOW_PCT, b)
        return 0

    def apply_level(self):
        if not BL_CONTROL:
            return
        p = self.pct()
        if p != self.lvl:
            self.lvl = p
            self.app.lcd.set_bl(p)

    def target(self, now):
        a = self.app
        cfg = a.st.cfg
        if a.keys.any_down():
            a.idle_t = now
        if self.off_req:
            return O
        idle = time.ticks_diff(now, a.idle_t) // 1000
        off = cfg.get("idle")
        if off and idle >= off:
            return O
        if cfg.get("pwr"):
            if idle >= cfg.get("lowt"):
                return L
            if BL_CONTROL and idle >= cfg.get("dimt"):
                return D
        return A

    def update(self, now):
        a = self.app
        t = self.target(now)
        if t != self.state:
            old = self.state
            self.state = t
            if old == O:
                a.lcd.display(True)
                a._sig = None
            if t == O:
                a.c.fill(0)
                a.lcd.show(a.c.buf)
                a.lcd.display(False)
            if t == L:
                gc.collect()
            if t == A:
                self.restore()
        # 低亮度: 沒播歌 -> 切到首頁當大時鐘; 開始播歌 -> 回原頁
        if self.state == L:
            if self.playing(now):
                self.restore()
            elif self.saved is None and a.cur_id not in ("home", "desk") and not self._is_game(a.cur_id):
                self.saved = a.menu.src if a.cur_id == "menu" else a.cur_id
                a.go("home", now)
        self.apply_level()

    def _is_game(self, pid):
        import registry
        i = registry.info(pid)
        return i is not None and i[4] == "game"

    def restore(self):
        if self.saved is not None:
            pid = self.saved
            self.saved = None
            self.app.go(pid)

    def force_off(self):
        self.off_req = True

    def wake(self, now):
        self.off_req = False
        self.app.idle_t = now
        self.update(now)

    # ---- 主迴圈每圈的等待 ----
    def nap(self):
        s = self.state
        if s == A:
            time.sleep_ms(8)
            return
        cfg = self.app.st.cfg
        if cfg.get("lsleep") and not self._ls_bad and s >= L:
            if self._light(1000 if (s == O or self.saved is not None) else 200):
                return
        time.sleep_ms(30 if s == D else 40)

    def _light(self, ms):
        try:
            import machine
            if not self._ls_ready:
                import esp32
                from machine import Pin
                esp32.wake_on_gpio(tuple(self.app.keys._pins), esp32.WAKEUP_ALL_LOW)
                self._ls_ready = True
            machine.lightsleep(ms)
            return True
        except Exception as e:
            self._ls_bad = True
            print("lightsleep unavailable:", e)
            return False
