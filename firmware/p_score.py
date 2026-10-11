"""比分板: 手機 App 設定要追蹤的比賽 (NBA / MLB / CPBL), 手機抓資料傳來這裡顯示。
左右: 切換比賽   上下: 主畫面 <-> 細節數據。"""
from app import Page
from keys import *
from ui import *


def diamond(c, cx, cy, r, col, fill):
    for dy in range(-r, r + 1):
        w = r - abs(dy)
        if fill:
            c.frect(cx - w, cy + dy, 2 * w + 1, 1, col)
        else:
            c.pixel(cx - w, cy + dy, col)
            c.pixel(cx + w, cy + dy, col)


def num(v):
    return str(v) if v < 10 else "+"


class ScorePage(Page):
    title = "比分板"
    reset_label = "重新同步"

    def __init__(self, app):
        Page.__init__(self, app)
        self.sel = 0
        self.view = 0
        self.lx = 70
        self.rx = 78

    def enter(self, now):
        Page.enter(self, now)
        self.app.link.send({"c": "sc", "on": 1})       # 通知手機: 比分頁開著, 請加快更新

    def leave(self):
        self.app.link.send({"c": "sc", "on": 0})

    def on_reset(self, now):
        self.app.link.send({"c": "sync"})

    def get_title(self):
        n = len(self.st.games)
        if not n:
            return "比分板"
        return "%s %d/%d%s" % (self.st.games[min(self.sel, n - 1)][0], self.sel + 1, n, " 詳" if self.view else "")

    def on_key(self, k, ev, now):
        n = len(self.st.games)
        self.dirty = True
        if k in (K_LEFT, K_RIGHT) and n > 1:
            if ev == EV_CLICK:
                self.sel = (self.sel + (1 if k == K_RIGHT else -1)) % n
                return True
            if ev in (EV_PRESS, EV_RELEASE, EV_DOUBLE, EV_TRIPLE, EV_LONG, EV_HOLD):
                return True
        if k in (K_UP, K_DOWN):
            if ev == EV_CLICK:
                self.view = 1 - self.view
                return True
            return ev in (EV_PRESS, EV_RELEASE, EV_DOUBLE, EV_TRIPLE, EV_LONG, EV_HOLD)
        return False

    # ---------- 共用 ----------
    def _teams(self, c, sc, a, h, aS, hS, bat=-1, live=True, sub=None):
        """左側兩列: 隊名 + 大比分。bat: 0=客隊攻擊 1=主隊攻擊 (棒球)。sub: 兩隊各一行小字 (隊名下方)"""
        done = sc.get("st") == "F"
        for r, (nm, s, o) in enumerate(((a, aS, hS), (h, hS, aS))):
            y = 15 + r * 27
            col = FG
            if done:
                col = GOOD if s > o else DIM
            elif live and s < o:
                col = DIM
            if bat == r:
                c.frect(0, y + 4, 2, 16, ACC)
            nc = FG if bat == r or not live else col
            if sub:
                c.text(nm, 4, y + 1, nc)
                c.text(sub[r], 4, y + 13, DIM)
            else:
                c.text(nm, 4, y + 6, nc)
            st = str(s) if s is not None else "-"
            c.text_big(st, self.lx - len(st) * 12, y, col, 2, 6)
        c.hline(4, 41, self.lx - 4, FAINT)
        c.vline(self.lx + 3, 14, 66, FAINT)

    def _pre_final(self, c, sc):
        """未開賽的右側資訊 (日期+開賽時間); 回傳 True 表示已處理"""
        R = self.rx
        if sc.get("st") == "P":
            tm = sc.get("tm", "--:--")
            d, _, t = tm.rpartition(" ")
            c.text("未開賽", R, 14, ACC)
            if d:
                c.text(d, R, 28, FG)
            c.text_big(t, R, 42, FG, 2, 6) if len(t) <= 5 else c.text(t, R, 46, FG)
            return True
        if sc.get("st") == "F":
            c.text("比賽結束", R, 14, ACC)
        return False

    # ---------- 棒球 ----------
    def base_main(self, c, sc):
        self.lx, self.rx = 70, 78
        R = self.rx
        a, h = sc.get("a", "?"), sc.get("h", "?")
        aS, hS = sc.get("as"), sc.get("hs")
        st = sc.get("st")
        bat = (sc.get("hf", 0)) if st == "L" else -1
        sub = None
        if st in ("L", "F"):
            sub = ("H%d E%d" % (sc.get("ah", 0), sc.get("ae", 0)), "H%d E%d" % (sc.get("hh", 0), sc.get("he", 0)))
        self._teams(c, sc, a, h, aS if aS is not None else 0, hS if hS is not None else 0, bat, st == "L", sub)
        if self._pre_final(c, sc):
            return
        if st == "F":
            c.text("%d局" % sc.get("in", 9), R, 28, FG)
            c.text("安打 %d:%d" % (sc.get("ah", 0), sc.get("hh", 0)), R, 42, DIM)
            c.text("失誤 %d:%d" % (sc.get("ae", 0), sc.get("he", 0)), R, 56, DIM)
            return
        inn = sc.get("in", 1)
        tx = sc.get("tx") or ("%d局" % inn)
        c.text(tx, R, 14, ACC)
        w = c.text_w(tx)
        if not sc.get("mid"):
            c.icon(IC["down" if sc.get("hf", 0) else "up"], R + w + 3, 18, ACC)
        bs = sc.get("bs", 0)
        cx, cy = R + 66, 36
        diamond(c, cx, cy - 8, 4, WARN if bs & 2 else FAINT, bs & 2)
        diamond(c, cx + 9, cy + 1, 4, WARN if bs & 1 else FAINT, bs & 1)
        diamond(c, cx - 9, cy + 1, 4, WARN if bs & 4 else FAINT, bs & 4)
        # 好壞球 / 出局: 小方塊
        for k, (lab, v, mx, col) in enumerate((("B", sc.get("b", 0), 4, GOOD), ("S", sc.get("s", 0), 3, WARN), ("O", sc.get("o", 0), 3, BAD))):
            y = 27 + k * 10
            c.text(lab, R, y - 3, DIM)
            for i in range(mx):
                c.frect(R + 12 + i * 9, y, 6, 6, col if i < v else FAINT)
        # 打者 / 投手 (簡短版; 姓名在細節頁)
        if sc.get("bavg"):
            c.text("打", R, 56, DIM)
            c.text(sc.get("bavg", "") + (" " + sc["bhab"] if sc.get("bhab") else ""), R + 16, 56, FG)
        if sc.get("pc") is not None:
            c.text("投", R, 68, DIM)
            c.text("%d球" % sc["pc"] + (" %s局" % sc["pip"] if sc.get("pip") else ""), R + 16, 68, FG)

    def base_detail(self, c, sc):
        a, h = sc.get("a", "?"), sc.get("h", "?")
        ai, hi = sc.get("ai", []), sc.get("hi", [])
        n = max(len(ai), len(hi), 9)
        first = max(0, n - 9)
        x0 = 26
        for i in range(9):
            inn = first + i
            c.text_c(str((inn + 1) % 10), x0 + i * 10 + 5, 14, FAINT)
        for lab, x in (("R", 124), ("H", 138), ("E", 150)):
            c.text_c(lab, x, 14, ACC)
        for r, (nm, arr, R, H, E) in enumerate(((a, ai, sc.get("as", 0), sc.get("ah", 0), sc.get("ae", 0)),
                                                  (h, hi, sc.get("hs", 0), sc.get("hh", 0), sc.get("he", 0)))):
            y = 27 + r * 13
            c.text(nm, 2, y, FG)
            for i in range(9):
                inn = first + i
                v = arr[inn] if inn < len(arr) else None
                if v is None:
                    c.text_c("-" if inn >= len(arr) and sc.get("st") != "P" else "", x0 + i * 10 + 5, y, FAINT)
                else:
                    c.text_c(num(v), x0 + i * 10 + 5, y, FG if v else DIM)
            c.text_c(str(R), 124, y, GOOD)
            c.text_c(str(H), 138, y, FG)
            c.text_c(str(E), 150, y, WARN if E else DIM)
        c.hline(2, 53, 156, FAINT)
        if sc.get("st") == "F":
            c.text("勝", 2, 55, GOOD)
            c.text(clip_text(c, sc.get("wp") or "--", 70), 16, 55, FG)
            if sc.get("sv"):
                c.text_r("救 " + clip_text(c, sc["sv"], 60), 156, 55, WARN)
            c.text("敗", 2, 67, BAD)
            c.text(clip_text(c, sc.get("lp") or "--", 70), 16, 67, FG)
            c.text_r("%d局" % sc.get("in", 9), 156, 67, DIM)
            return
        c.text("打", 2, 55, DIM)
        t = sc.get("bt", "") or "--"
        x = c.text(clip_text(c, t, 70), 16, 55, FG)
        ex = ((sc.get("bavg", "") + " " + sc["bhab"]).strip() if sc.get("bhab") or sc.get("bavg") else "")
        if ex:
            c.text_r(ex, 156, 55, FG)
        c.text("投", 2, 67, DIM)
        t = sc.get("pt", "") or "--"
        c.text(clip_text(c, t, 70), 16, 67, FG)
        ex = ""
        if sc.get("pc") is not None:
            ex = "%d球" % sc["pc"]
            if sc.get("pip"):
                ex += " %s局" % sc["pip"]
            if sc.get("per") is not None:
                ex += " %d責" % sc["per"]
        if ex:
            c.text_r(ex, 156, 67, FG)

    def _bk(self):
        import p_scorebk
        return p_scorebk

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
        lg, a, h = games[self.sel]
        sc = self.st.scores.get(self.sel)
        if not sc:
            c.text("%s  vs  %s" % (a, h), 6, 22, FG)
            c.text("等待手機傳送比分…", 6, 40, DIM)
            return
        base = lg in ("MLB", "CPBL")
        if self.view and sc.get("st") != "P":
            self.base_detail(c, sc) if base else self._bk().detail(self, c, sc)
        else:
            self.base_main(c, sc) if base else self._bk().main(self, c, sc)
