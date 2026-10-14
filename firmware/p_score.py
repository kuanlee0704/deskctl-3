"""比分板核心: 選比賽、切換畫面、標題、捲動文字。畫面本身在 p_scorebb(棒球) / p_scorebk(籃球), 需要時才載入。
左右: 切換比賽   上下: 切換畫面 (棒球進行中 4 頁: 主畫面/細節/即時/戰績; 其他 2 頁)。"""
import sys
import time
import gc
from app import Page
from keys import *
from ui import *

BASE = ("MLB", "CPBL")


class ScorePage(Page):
    title = "比分板"
    reset_label = "重新同步"

    def __init__(self, app):
        Page.__init__(self, app)
        self.sel = 0
        self.view = 0
        self.lx = 70
        self.rx = 78
        self._now = 0
        self._anim = False
        self._age = -1
        self._clk = -1
        self._mqs = {}
        self._m = None
        self._mn = ""
        self._s = None
        self._sn = ""
        self._fail = 0

    # ---------- 進出 ----------
    def _tell(self):
        self.app.link.send({"c": "sc", "on": 1, "sel": self.sel})
        self.st.set_sel(self.sel)
        self._mqs = {}

    def enter(self, now):
        Page.enter(self, now)
        self._tell()

    def leave(self):
        self.app.link.send({"c": "sc", "on": 0})
        self.st.set_sel(-1)
        self._drop()

    def _drop(self):
        if self._mn:
            sys.modules.pop(self._mn, None)
        self._m = None
        self._mn = ""
        self._mqs = {}
        self._subdrop(False)
        sys.modules.pop("p_scoret", None)
        gc.collect()

    def _subdrop(self, collect=True):
        if self._sn:
            sys.modules.pop(self._sn, None)
            self._s = None
            self._sn = ""
            if collect:
                gc.collect()

    def _sub(self, name):
        """第二層模組 (棒球即時/戰績); 只在需要的那兩頁載入, 回到別頁就卸載。載入失敗回傳 None"""
        if self._sn != name:
            self._subdrop(False)               # 先卸載舊的第二層模組, 免得兩個同時佔著記憶體
            try:
                gc.collect()
                self._s = __import__(name)
                self._sn = name
            except MemoryError:
                self._s = None
                return None
        return self._s

    def _mod(self, name):
        """載入畫面模組; 記憶體不足就先丟掉別的頁面再試, 還是不行就回傳 None (畫面顯示提示, 不會每一幀都重試)"""
        if self._mn != name:
            if self._fail and time.ticks_diff(self._now, self._fail) < 3000:
                return None
            self._drop()
            try:
                try:
                    self._m = __import__(name)
                except MemoryError:
                    self.app._evict("score")
                    self._m = __import__(name)
                self._mn = name
                self._fail = 0
            except MemoryError:
                self._m = None
                self._fail = self._now or 1
                return None
        return self._m

    def on_reset(self, now):
        self.app.link.send({"c": "sync"})

    # ---------- 狀態 ----------
    def _sc(self):
        return self.st.scores.get(self.sel)

    def _nv(self):
        """目前這場比賽有幾個畫面 (MLB 進行中 5 / 完賽 4 / 未開賽 2; NBA 開賽後 5; 其他 2)"""
        sc = self._sc()
        n = len(self.st.games)
        if not sc or not n:
            return 1
        lg = self.st.games[min(self.sel, n - 1)][0]
        s = sc.get("st")
        if lg == "MLB":
            return 5 if s == "L" else (4 if s == "F" else 2)
        if lg == "CPBL":
            return 1 if s == "P" else 2
        return 1 if s == "P" else 5

    def _v(self):
        return self.view if self.view < self._nv() else 0

    def _age_s(self):
        """距離上次收到這場資料幾秒 (還沒收到資料回傳 -1)。不分比賽狀態, 讓你隨時知道資料新不新"""
        t = self.st.score_t.get(self.sel)
        if t is None or not self._sc():
            return -1
        return time.ticks_diff(self._now, t) // 1000

    def get_tag(self):
        a = self._age_s()
        if a < 0:
            return ""
        sc = self._sc()
        if sc and sc.get("dl"):                 # 延遲播放中: 顯示的是 (收到資料的秒數 + 延遲秒數) 之前的實況
            a += sc["dl"]
        return "(:%02d)" % a if a < 60 else ("(%02d:)" % (a // 60) if a < 6000 else "(--)")

    def get_title(self):
        n = len(self.st.games)
        if not n:
            return "比分板"
        v = self._v()
        sc = self._sc()
        vl = ""
        if v:
            lg = self.st.games[min(self.sel, n - 1)][0]
            s = sc.get("st") if sc else None
            if lg == "MLB":
                lab = {"L": " 詳即戰得", "F": " 詳得星", "P": " 詳先"}.get(s, " 詳")
            else:
                lab = " 詳投助員"
            vl = lab[v] if v < len(lab) else ""
        return "%s%d/%d%s" % (self.st.games[min(self.sel, n - 1)][0], self.sel + 1, n, vl)

    # ---------- 籃球計時器: 手機每幾秒給一次剩餘時間, 中間在這裡往下推算 ----------
    def clk(self, sc, now):
        """剩餘時間 (0.1 秒); 沒有計時資料回傳 -1"""
        cs = sc.get("cs")
        if cs is None:
            return -1
        if sc.get("dl") is not None:
            # 延遲播放: 手機把畫面延後, 並告訴我們下一筆真實資料的時間 (cn) 與相隔毫秒 (cd);
            # 這裡只在兩筆已知資料之間線性插值, 不猜未來; 下一筆還沒到就停在目前值
            cn = sc.get("cn")
            cd = sc.get("cd") or 0
            if cn is None or cn >= cs or cd <= 0:
                return cs
            e = time.ticks_diff(now, self.st.clk_t.get(self.sel, now))
            if e >= cd:
                return cn
            return cs - (cs - cn) * e // cd
        d = 0
        if sc.get("cr"):
            d = min(time.ticks_diff(now, self.st.clk_t.get(self.sel, now)), 20000) // 100
        return cs - d if cs > d else 0

    def buf_left(self, sc, now):
        """延遲播放剛開始, 還要緩衝幾秒 (0 = 緩衝完成)"""
        t = self.st.score_t.get(self.sel)
        if t is None:
            return 0
        return max(0, sc.get("bf", 0) - time.ticks_diff(now, t) // 1000)

    def tick(self, now):
        self._now = now
        d = self.dirty or self._anim
        a = self._age_s()
        if a != self._age:
            self._age = a
            d = True
        sc = self._sc()
        if sc and sc.get("bf"):                              # 緩衝倒數 (每秒重畫)
            k = self.buf_left(sc, now) + 20000
            if k != self._clk:
                self._clk = k
                d = True
        elif sc and sc.get("st") == "L" and "cs" in sc and not sc.get("brk"):
            r = self.clk(sc, now)
            k = r // 10 if r >= 600 else r + 10000        # 一分鐘以上每秒更新, 最後一分鐘每 0.1 秒
            if k != self._clk:
                self._clk = k
                d = True
        self.dirty = False
        return d

    def on_key(self, k, ev, now):
        n = len(self.st.games)
        self.dirty = True
        if k in (K_LEFT, K_RIGHT) and n > 1:
            if ev == EV_CLICK:
                self.sel = (self.sel + (1 if k == K_RIGHT else -1)) % n
                self._tell()
                return True
            if ev in (EV_PRESS, EV_RELEASE, EV_DOUBLE, EV_TRIPLE, EV_LONG, EV_HOLD):
                return True
        if k in (K_UP, K_DOWN):
            if ev == EV_CLICK:
                nv = self._nv()
                self.view = (self._v() + (1 if k == K_UP else -1)) % nv
                self._mqs = {}
                return True
            return ev in (EV_PRESS, EV_RELEASE, EV_DOUBLE, EV_TRIPLE, EV_LONG, EV_HOLD)
        return False

    # ---------- 共用畫法 ----------
    def _txt(self, c, key, s, x, y, w, col):
        """文字放得下就直接畫; 太長就在固定寬度內來回捲動 (像歌名); 同時最多 3 條捲動以保護記憶體"""
        if c.text_w(s) <= w:
            c.text(s, x, y, col)
            return
        m = self._mqs.get(key)
        if m is not None and m.w != w:
            m = None
        if m is None:
            if len(self._mqs) >= 3 and key not in self._mqs:
                c.text(clip_text(c, s, w), x, y, col)
                return
            m = self._mqs[key] = Marquee(self.app.mq[0].cv.font, w, col)
        m.draw(c, x, y, s, self._now, BG, col)
        self._anim = True

    def draw(self, c, now):
        games = self.st.games
        n = len(games)
        if not n:
            c.text("尚未追蹤比賽", 6, 20, DIM)
            c.text("到手機 App 的「比分」", 6, 38, FAINT)
            c.text("分頁選擇要追蹤的比賽", 6, 52, FAINT)
            return
        if self.sel >= n:
            self.sel = 0
        self._now = now
        self._anim = False
        g = games[self.sel]
        sc = self.st.scores.get(self.sel)
        if not sc:
            c.text("%s  vs  %s" % (g[1], g[2]), 6, 22, FG)
            c.text("等待手機傳送比分…", 6, 40, DIM)
            return
        m = self._mod("p_scorebb" if g[0] in BASE else "p_scorebk")
        if m is None:
            c.text("記憶體不足, 稍後重試", 6, 30, WARN)
            return
        m.draw(self, c, sc, self._v(), g)
