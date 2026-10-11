"""比分板: 籃球畫面 (獨立檔案, 需要時才載入以節省記憶體)"""
from ui import *


def main(self, c, sc):
    self.lx, self.rx = 76, 84
    R = self.rx
    a, h = sc.get("a", "?"), sc.get("h", "?")
    live = sc.get("st") == "L"
    sub = None
    if live or sc.get("st") == "F":
        sub = ("F%d T%d" % (sc.get("af", 0), sc.get("at", 0)), "F%d T%d" % (sc.get("hf2", 0), sc.get("ht", 0)))
    self._teams(c, sc, a, h, sc.get("as", 0), sc.get("hs", 0), -1, live, sub)
    if self._pre_final(c, sc):
        return
    q = sc.get("q", 1)
    if sc.get("st") == "F":
        c.text("第%d節" % q if q <= 4 else "加時賽", R, 30, FG)
        c.text("犯規 %d:%d" % (sc.get("af", 0), sc.get("hf2", 0)), R, 44, DIM)
        return
    c.text(("第%d節" % q) if q <= 4 else ("加時%d" % (q - 4)), R, 14, ACC)
    clk = sc.get("c", "")
    low = clk and ":" in clk and clk.split(":")[0] == "0"
    if sc.get("brk"):
        c.text("休息", R, 34, WARN)
    else:
        c.text_big(clk or "--", R, 28, WARN if low else FG, 2, 0)
    mk = lambda b: "*" if b else ""
    c.text("犯規 %d%s/%d%s" % (sc.get("af", 0), mk(sc.get("ab")), sc.get("hf2", 0), mk(sc.get("hb"))), R, 54, FG)
    c.text("暫停 %d/%d" % (sc.get("at", 0), sc.get("ht", 0)), R, 66, DIM)

def detail(self, c, sc):
    a, h = sc.get("a", "?"), sc.get("h", "?")
    aq, hq = sc.get("aq", []), sc.get("hq", [])
    cols = (32, 52, 72, 92)
    for i, x in enumerate(cols):
        c.text_c("Q%d" % (i + 1), x + 8, 14, FAINT)
    c.text_c("OT", 120, 14, FAINT)
    c.text_c("總", 148, 14, ACC)
    for r, (nm, arr, T) in enumerate(((a, aq, sc.get("as", 0)), (h, hq, sc.get("hs", 0)))):
        y = 27 + r * 13
        c.text(nm, 2, y, FG)
        for i, x in enumerate(cols):
            if i < len(arr):
                c.text_c(str(arr[i]), x + 8, y, FG)
            else:
                c.text_c("-", x + 8, y, FAINT)
        ot = sum(arr[4:]) if len(arr) > 4 else 0
        c.text_c(str(ot) if len(arr) > 4 else "-", 120, y, FG if ot else FAINT)
        c.text_c(str(T), 148, y, GOOD)
    c.hline(2, 53, 156, FAINT)
    c.text("犯規 %d:%d" % (sc.get("af", 0), sc.get("hf2", 0)), 4, 55, FG)
    c.text_r("暫停 %d:%d" % (sc.get("at", 0), sc.get("ht", 0)), 156, 55, FG)
    bon = []
    if sc.get("ab"):
        bon.append(a)
    if sc.get("hb"):
        bon.append(h)
    c.text("罰球加成", 4, 67, DIM)
    c.text_r(" ".join(bon) if bon else "無", 156, 67, WARN if bon else FAINT)
