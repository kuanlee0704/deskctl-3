"""比分板: 棒球即時與戰績畫面 (獨立檔案, 進入這兩頁時才載入)"""
from ui import *
from p_scoret import bases, row, teams, pre_final, foot


# ---------- 即時 ----------
def res_col(r):
    if r in ("1B", "2B", "3B", "HR"):
        return GOOD
    if r in ("BB", "IBB", "HBP"):
        return ACC
    return DIM


def live(p, c, sc):
    a, h = sc.get("a", "?"), sc.get("h", "?")
    hf = sc.get("hf", 0)
    x = c.text(a, 2, 13, FG if hf else ACC)
    x = c.text(" %d" % sc.get("as", 0), x, 13, FG)
    x = c.text("  " + h, x, 13, ACC if hf else FG)
    c.text(" %d" % sc.get("hs", 0), x, 13, FG)
    tx = sc.get("tx") or ("%d局" % sc.get("in", 1))
    c.text_r(tx, 146, 13, ACC)
    if not sc.get("mid"):
        c.icon(IC["down" if hf else "up"], 148, 17, ACC)
    c.hline(0, 25, 160, FAINT)
    c.vline(74, 26, 54, FAINT)
    for k, (lab, val, mx, col) in enumerate((("B", sc.get("b", 0), 4, GOOD), ("S", sc.get("s", 0), 3, WARN), ("O", sc.get("o", 0), 3, BAD))):
        y = 30 + k * 10
        c.text(lab, 2, y - 3, DIM)
        for i in range(mx):
            c.frect(12 + i * 8, y, 6, 6, col if i < val else FAINT)
    bases(c, 58, 39, sc.get("bs", 0))
    lt = sc.get("lt")
    c.text(("%s %s" % (lt, sc.get("lv", ""))).strip() if lt else "--", 2, 57, WARN if lt else FAINT)
    lr = sc.get("lr")
    if lr:
        p._txt(c, "lr", (lr + " " + sc.get("ln", "")).strip(), 2, 68, 70, res_col(lr))
    c.text("打", 78, 27, DIM)
    p._txt(c, "bt", sc.get("bt") or "--", 92, 27, 66, FG)
    c.text((sc.get("bavg", "") + " " + sc.get("bhab", "")).strip() or "--", 92, 39, FG)
    c.text("投", 78, 51, DIM)
    p._txt(c, "pt", sc.get("pt") or "--", 92, 51, 66, FG)
    pc = sc.get("pc")
    c.text(("%d球" % pc + (" %s局" % sc["pip"] if sc.get("pip") else "")) if pc is not None else "--", 92, 63, FG)


# ---------- 戰績與打序 ----------
def roster(p, c, sc):
    x = c.text(sc.get("a", "?"), 2, 13, ACC)
    x = c.text(" " + sc.get("ar", "--"), x, 13, DIM)
    x = c.text("   " + sc.get("h", "?"), x, 13, ACC)
    c.text(" " + sc.get("hr", "--"), x, 13, DIM)
    row(p, c, "bt", "打", sc.get("bt"), (sc.get("bavg", "") + " " + sc.get("bhab", "")).strip(), 26)
    row(p, c, "n1", "次", sc.get("n1"), sc.get("v1", ""), 38)
    row(p, c, "n2", "次", sc.get("n2"), sc.get("v2", ""), 50)
    ex = " ".join(x for x in (("ERA " + sc["pe"]) if sc.get("pe") else "", ("%sK" % sc["pk"]) if sc.get("pk") is not None else "") if x)
    row(p, c, "pt", "投", sc.get("pt"), ex, 62)


