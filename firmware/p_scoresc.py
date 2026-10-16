"""比分板: 足球畫面 (獨立檔案, 需要時才載入): 主畫面 / 進球與紅黃牌 / 球隊統計 2 頁"""
from ui import *
from p_scoret import teams, pre_final, foot

# 統計陣列順序: 控球% 射門 射正 角球 犯規 越位 黃牌 紅牌
ROWS = (((0, "控球%"), (1, "射門"), (2, "射正"), (3, "角球")),
        ((4, "犯規"), (5, "越位"), (6, "黃牌"), (7, "紅牌")))
KIND = {"g": ("球", GOOD), "p": ("點", GOOD), "o": ("烏", BAD), "y": ("黃", WARN), "r": ("紅", BAD)}


def draw(p, c, sc, v, g):
    if v == 0 or sc.get("st") == "P":
        main(p, c, sc)
        foot(p, c, sc, g)
    elif v == 1:
        events(p, c, sc)
    else:
        stats(p, c, sc, v - 2)


def main(p, c, sc):
    p.lx, p.rx = 70, 90
    R = p.rx
    a, h = sc.get("a", "?"), sc.get("h", "?")
    live = sc.get("st") == "L"
    sub = None
    sa, sh = sc.get("sa"), sc.get("sh")
    if (live or sc.get("st") == "F") and sa and sh:
        sub = tuple("黃%d 紅%d" % (s[6], s[7]) if len(s) > 7 else "" for s in (sa, sh))
    teams(p, c, sc, a, h, sc.get("as", 0), sc.get("hs", 0), -1, live, sub, 85, (ACC, WARN))
    if pre_final(p, c, sc):
        return
    ps = sc.get("ps")
    if sc.get("st") == "F":
        c.text("FT", R, 30, FG)
        if ps:
            c.text("PK %d-%d" % (ps[0], ps[1]), R, 44, WARN)
        if sc.get("tm"):
            c.text(sc["tm"], R, 58, FAINT)
        return
    if sc.get("ln"):
        c.text(sc["ln"], R, 14, ACC)
    m = sc.get("c") or "--"
    if ps:
        m = "PK %d-%d" % (ps[0], ps[1])
    if len(m) <= 5:
        c.text_big(m, R, 28, WARN if m == "HT" else FG, 2, 0)
    else:
        c.text(m, R, 32, FG)
    if sa and sh and len(sa) > 2 and len(sh) > 2:
        c.text("射門 %d:%d" % (sa[1], sh[1]), R, 54, FG)
        c.text("控球 %d:%d" % (sa[0], sh[0]), R, 66, DIM)


def events(p, c, sc):
    """進球 / 紅黃牌時間線: 最新在最下面; 客隊藍, 主隊黃"""
    ev = sc.get("ev") or []
    if not ev:
        c.text("尚無進球或牌", 6, 34, DIM)
        return
    ev = ev[-5:]
    for i, e in enumerate(ev):
        y = 14 + i * 12
        k = KIND.get(e[2], ("?", FG))
        col = ACC if e[1] == 0 else WARN
        c.text(str(e[0]), 2, y, DIM)
        c.text(k[0], 34, y, k[1])
        p._txt(c, "e%d" % i, e[3] or "--", 50, y, 106, col)


def stats(p, c, sc, pg):
    ta, th = sc.get("sa"), sc.get("sh")
    if not ta or not th:
        c.text("等待數據…", 6, 34, DIM)
        return
    c.text_c(sc.get("a", "?"), 22, 13, ACC)
    c.text_c(sc.get("h", "?"), 138, 13, WARN)
    for k, (i, lab) in enumerate(ROWS[pg]):
        y = 27 + k * 13
        x, z = ta[i], th[i]
        c.text_c(str(x), 22, y, GOOD if i < 4 and x > z else FG)
        c.text_c(lab, 80, y, DIM)
        c.text_c(str(z), 138, y, GOOD if i < 4 and z > x else FG)
