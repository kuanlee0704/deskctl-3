"""比分板: 共用畫法 (獨立檔案, 棒球/籃球畫面會用到)"""
from ui import *


def diamond(c, cx, cy, r, col, fill):
    for dy in range(-r, r + 1):
        w = r - abs(dy)
        if fill:
            c.frect(cx - w, cy + dy, 2 * w + 1, 1, col)
        else:
            c.pixel(cx - w, cy + dy, col)
            c.pixel(cx + w, cy + dy, col)


def bases(c, cx, cy, bs):
    diamond(c, cx, cy - 8, 4, WARN if bs & 2 else FAINT, bs & 2)
    diamond(c, cx + 9, cy + 1, 4, WARN if bs & 1 else FAINT, bs & 1)
    diamond(c, cx - 9, cy + 1, 4, WARN if bs & 4 else FAINT, bs & 4)


def row(p, c, key, lab, name, ex, y, lc=DIM):
    """一列: 標籤 + 姓名(太長捲動) + 靠右的數據"""
    c.text(lab, 2, y, lc)
    w = 140
    if ex:
        c.text_r(ex, 158, y, FG)
        w -= c.text_w(ex) + 4
    p._txt(c, key, name or "--", 16, y, w, FG)


def foot(p, c, sc, g):
    """左下角一排: 中文隊名 (客 v 主)"""
    p._txt(c, "tm", sc.get("tn") or "%s v %s" % (g[1], g[2]), 2, 68, p.lx - 2, DIM)

def teams(p, c, sc, a, h, aS, hS, bat=-1, live=True, sub=None):
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
        c.text_big(st, p.lx - len(st) * 12, y, col, 2, 6)
    c.hline(4, 41, p.lx - 4, FAINT)
    c.vline(p.lx + 3, 14, 66, FAINT)

def pre_final(p, c, sc):
    """未開賽的右側資訊 (日期+開賽時間); 回傳 True 表示已處理"""
    R = p.rx
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
