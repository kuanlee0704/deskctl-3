"""手機控制器頁: 五向鍵 = 手機的方向/觸控按鈕, 每個方向的功能由手機 App 定義, 並顯示在螢幕上。
上下左右: 單擊 / 連按兩下 / 長按(不放會連續) 都會傳給手機, 手機依目前「模式」執行 (滾動、點擊、滑動、開 App、音量…)。
中央: 單擊 = 手機定義的動作 (例如點指定位置);  兩下 = 把四個方向的功能順時針轉 90° (控制器轉向時用);
      三下 = 回首頁 (全域);  長按 = 目錄 (全域)。切換模式由手機把某個手勢設成「切換模式」。"""
import time
from app import Page
from keys import *
from ui import *

DIR_OF = {K_UP: 0, K_RIGHT: 1, K_DOWN: 2, K_LEFT: 3}      # 實體按鍵 -> 位置 (上 右 下 左, 順時針)
KEY_OF = (K_UP, K_RIGHT, K_DOWN, K_LEFT)
EVS = {EV_CLICK: "c", EV_DOUBLE: "d", EV_LONG: "l", EV_HOLD: "h"}


class PadPage(Page):
    title = "手機控制"
    reset_label = "重新同步"

    def __init__(self, app):
        Page.__init__(self, app)
        self.rot = (self.st.cfg.get("padrot") or 0) % 4
        self._fl = -1
        self._ft = 0
        self._pv = -1
        self._now = 0
        self._warn = 0

    def on_reset(self, now):
        self.app.link.send({"c": "pad", "e": "hi"})

    # ---------- 進出 ----------
    def _fast(self):
        """沒有設「兩下」手勢的方向不必等連按判定, 單擊就立刻送出 (滾動才不會慢半拍)"""
        pad = self.st.pad
        mask = pad[4] if pad else 0
        f = ()
        for p in range(4):
            if not (mask >> ((p - self.rot) % 4)) & 1:
                f += (KEY_OF[p],)
        self.fast = f
        self.app.keys.set_fast(f)

    def enter(self, now):
        Page.enter(self, now)
        self._fast()
        self.app.link.send({"c": "pad", "e": "hi"})

    def tick(self, now):
        self._now = now
        d = self.dirty
        self.dirty = False
        if self.st.pad_v != self._pv:
            self._pv = self.st.pad_v
            self._fast()
            d = True
        if self._fl >= 0:
            d = True
            if time.ticks_diff(now, self._ft) >= 0:
                self._fl = -1
        return d

    # ---------- 按鍵 ----------
    def _send(self, d, e, now):
        self._fl = d
        self._ft = time.ticks_add(now, 220)
        if not self.app.link.send({"c": "pad", "d": d, "e": e}):
            if time.ticks_diff(now, self._warn) > 1500:
                self._warn = now
                self.app.toast("手機未連線")

    def on_key(self, k, ev, now):
        self.dirty = True
        if k == K_CENTER:
            if ev == EV_CLICK:
                self._send(4, "c", now)
            elif ev == EV_DOUBLE:
                self.rot = (self.rot + 1) % 4
                self.st.cfg.set("padrot", self.rot)
                self._fast()
                self.app.toast("功能已順時針轉 %d°" % (self.rot * 90) if self.rot else "功能已回到原位", 1200)
            return ev in (EV_PRESS, EV_RELEASE, EV_CLICK, EV_DOUBLE)
        p = DIR_OF.get(k)
        if p is None:
            return False
        e = EVS.get(ev)
        if e:
            self._send((p - self.rot) % 4, e, now)
            self._fl = p                       # 閃爍的是實體位置
        return True

    # ---------- 畫面 ----------
    def get_title(self):
        pad = self.st.pad
        if not pad:
            return self.title
        return "%s %d/%d" % (pad[0], pad[1] + 1, pad[2])

    def get_tag(self):
        return "R%d" % (self.rot * 90) if self.rot else ""

    def draw(self, c, now):
        pad = self.st.pad
        if not pad:
            c.text("尚未收到手機設定", 6, 22, DIM)
            c.text("連線後到手機 App 的", 6, 40, FAINT)
            c.text("「控制」分頁設定按鍵", 6, 54, FAINT)
            return
        lab = pad[3]
        r = self.rot
        for p in range(4):
            s = lab[(p - r) % 4] or "-"
            col = GOOD if self._fl == p else FG
            if p == 0:
                c.text_c(s, 80, 14, col)
            elif p == 1:
                c.text_r(s, 156, 33, col)
            elif p == 2:
                c.text_c(s, 80, 54, col)
            else:
                c.text(s, 4, 33, col)
        hit = self._fl == 4
        c.frect(56, 27, 48, 24, PANEL)
        c.rect(56, 27, 48, 24, GOOD if hit else ACC)
        c.text_c(lab[4] or "-", 80, 33, GOOD if hit else FG)
        c.text("中央x2 轉向", 4, 68, FAINT)
