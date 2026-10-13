"""比分板: 棒球畫面 (獨立檔案, 需要時才載入以節省記憶體)
v0 主畫面  v1 細節(每局得分 / 賽後勝敗)  v2 即時  v3 戰績與打序  (未開賽 v1 = 先發投手)"""
from ui import *
from p_scoret import bases, row, teams, pre_final, foot, bline


def num(v):
    return str(v) if v < 10 else "+"


def draw(p, c, sc, v, g):
    st = sc.get("st")
    if v < 2 or st == "P":
        p._subdrop()
    if v == 0:
        main(p, c, sc)
        foot(p, c, sc, g)
    elif st == "P":
        probable(p, c, sc)
    elif v == 1:
        detail(p, c, sc)
    else:
        # 進行中: 2 即時 3 戰績打序 4 得分紀錄;  完賽: 2 得分紀錄 3 打點與全壘打
        sp = (v == 4) if st == "L" else (v in (2, 3))
        m = p._sub("p_scoresp" if sp else "p_scorelv")
        if m is None:
            c.text("記憶體不足, 稍後重試", 6, 30, WARN)
        elif sp:
            (m.stars if (st == "F" and v == 3) else m.plays)(p, c, sc)
        elif v == 2:
            m.live(p, c, sc)
        else:
            m.roster(p, c, sc)


def main(p, c, sc):
    p.lx, p.rx = 70, 78
    R = p.rx
    a, h = sc.get("a", "?"), sc.get("h", "?")
    aS, hS = sc.get("as"), sc.get("hs")
    st = sc.get("st")
    bat = (sc.get("hf", 0)) if st == "L" else -1
    sub = None
    if st in ("L", "F"):
        sub = ("H%d E%d" % (sc.get("ah", 0), sc.get("ae", 0)), "H%d E%d" % (sc.get("hh", 0), sc.get("he", 0)))
    elif sc.get("ar"):
        sub = (sc["ar"], sc.get("hr", ""))
    teams(p, c, sc, a, h, aS if aS is not None else 0, hS if hS is not None else 0, bat, st == "L", sub)
    if pre_final(p, c, sc):
        return
    if st == "F":
        c.text("%d局" % sc.get("in", 9), R, 28, FG)
        c.text("安打 %d:%d" % (sc.get("ah", 0), sc.get("hh", 0)), R, 42, DIM)
        c.text("失誤 %d:%d" % (sc.get("ae", 0), sc.get("he", 0)), R, 56, DIM)
        if sc.get("tm"):
            c.text(sc["tm"], R, 68, FAINT)
        return
    inn = sc.get("in", 1)
    tx = sc.get("tx") or ("%d局" % inn)
    c.text(tx, R, 14, ACC)
    if not sc.get("mid"):
        c.icon(IC["down" if sc.get("hf", 0) else "up"], R + c.text_w(tx) + 3, 18, ACC)
    bs = sc.get("bs", 0)
    bases(c, R + 66, 36, bs)
    for k, (lab, val, mx, col) in enumerate((("B", sc.get("b", 0), 4, GOOD), ("S", sc.get("s", 0), 3, WARN), ("O", sc.get("o", 0), 3, BAD))):
        y = 27 + k * 10
        c.text(lab, R, y - 3, DIM)
        for i in range(mx):
            c.frect(R + 12 + i * 9, y, 6, 6, col if i < val else FAINT)
    if sc.get("bavg"):
        c.text("打", R, 56, DIM)
        c.text(bline(c, sc, 66), R + 16, 56, FG)
    if sc.get("pc") is not None:
        c.text("投", R, 68, DIM)
        c.text("%d球" % sc["pc"] + (" %s局" % sc["pip"] if sc.get("pip") else ""), R + 16, 68, FG)


def detail(p, c, sc):
    a, h = sc.get("a", "?"), sc.get("h", "?")
    ai, hi = sc.get("ai", []), sc.get("hi", [])
    n = max(len(ai), len(hi), 9)
    first = max(0, n - 9)
    x0 = 26
    for i in range(9):
        c.text_c(str((first + i + 1) % 10), x0 + i * 10 + 5, 14, FAINT)
    for lab, x in (("R", 124), ("H", 138), ("E", 150)):
        c.text_c(lab, x, 14, ACC)
    for r, (nm, arr, R_, H, E) in enumerate(((a, ai, sc.get("as", 0), sc.get("ah", 0), sc.get("ae", 0)),
                                              (h, hi, sc.get("hs", 0), sc.get("hh", 0), sc.get("he", 0)))):
        y = 27 + r * 13
        c.text(nm, 2, y, FG)
        for i in range(9):
            inn = first + i
            val = arr[inn] if inn < len(arr) else None
            if val is None:
                c.text_c("-" if inn >= len(arr) and sc.get("st") != "P" else "", x0 + i * 10 + 5, y, FAINT)
            else:
                c.text_c(num(val), x0 + i * 10 + 5, y, FG if val else DIM)
        c.text_c(str(R_), 124, y, GOOD)
        c.text_c(str(H), 138, y, FG)
        c.text_c(str(E), 150, y, WARN if E else DIM)
    c.hline(2, 53, 156, FAINT)
    if sc.get("st") == "F":
        c.text("勝", 2, 55, GOOD)
        sv = sc.get("sv")
        svw = 0
        if sv:
            svw = c.text_w("救 " + sv) + 4
            c.text_r("救 " + sv, 156, 55, WARN)
        p._txt(c, "wp", sc.get("wp") or "--", 16, 55, 138 - svw, FG)
        c.text("敗", 2, 67, BAD)
        ex = "%d局" % sc.get("in", 9)
        c.text_r(ex, 156, 67, DIM)
        p._txt(c, "lp", sc.get("lp") or "--", 16, 67, 138 - c.text_w(ex) - 4, FG)
        return
    row(p, c, "bt", "打", sc.get("bt"), bline(c, sc, 70), 55)
    ex = ""
    if sc.get("pc") is not None:
        ex = "%d球" % sc["pc"]
        if sc.get("pip"):
            ex += " %s局" % sc["pip"]
        if sc.get("per") is not None:
            ex += " %d責" % sc["per"]
    row(p, c, "pt", "投", sc.get("pt"), ex, 67)


# ---------- 未開賽: 先發投手 ----------
def probable(p, c, sc):
    for k, (tm, nm, ex, rec) in enumerate(((sc.get("a", "?"), sc.get("ap"), sc.get("ax"), sc.get("ar")),
                                            (sc.get("h", "?"), sc.get("hp"), sc.get("hx"), sc.get("hr")))):
        y = 14 + k * 33
        c.text(tm, 2, y, ACC)
        c.text(rec or "", 2, y + 12, DIM)
        p._txt(c, "pp%d" % k, nm or "未公布", 40, y, 118, FG)
        c.text(ex or "", 40, y + 12, DIM)
        if k == 0:
            c.hline(2, 43, 156, FAINT)
