"""比分板: 籃球畫面 (獨立檔案, 需要時才載入以節省記憶體)"""
from ui import *
from p_scoret import teams, pre_final, foot


def draw(p, c, sc, v, g):
    if v < 2 or sc.get("st") == "P":
        p._subdrop()
    if v == 0 or sc.get("st") == "P":
        main(p, c, sc)
        foot(p, c, sc, g)
    elif v == 1:
        detail(p, c, sc)
    else:
        m = p._sub("p_scorerec" if v == 5 else "p_scoresk")     # 2 投籃等數據  3 助攻等數據  4 球員與最近得分  5 賽後戰績
        if m is None:
            c.text("記憶體不足, 稍後重試", 6, 30, WARN)
        elif v == 5:
            m.rec(p, c, sc)
        elif v == 4:
            m.leaders(p, c, sc)
        else:
            m.stats(p, c, sc, v - 2)


def ctext(r):
    """剩餘 0.1 秒數 → 文字: 一分鐘以上 M:SS, 最後一分鐘 SS.t"""
    return "%d:%02d" % (r // 600, (r // 10) % 60) if r >= 600 else "%d.%d" % (r // 10, r % 10)


def main(p, c, sc):
    p.lx, p.rx = 70, 90                 # 比分往左靠, 右邊留出 +2/+3 的空間; 分隔線仍在 85
    R = p.rx
    a, h = sc.get("a", "?"), sc.get("h", "?")
    live = sc.get("st") == "L"
    sub = None
    if live or sc.get("st") == "F":
        sub = ("F%d T%d" % (sc.get("af", 0), sc.get("at", 0)), "F%d T%d" % (sc.get("hf2", 0), sc.get("ht", 0)))
    ks = kt = None
    if live:                                      # 手機給的最近得分; 沒有就用板子自己比對前後比分得到的
        ks = sc.get("ks")
        kt = sc.get("kt", 0)
        if not ks:
            kk = p.st.kk.get(p.sel)
            if kk:
                kt, ks = kk
    kt = kt or 0
    teams(p, c, sc, a, h, sc.get("as", 0), sc.get("hs", 0), kt if ks else -1, live, sub, 85, (ACC, WARN))
    if ks:                                        # 最近一次得分: 得分隊那一列的比分旁邊標 +N, 客隊藍 / 主隊黃
        c.text("+%d" % ks, p.lx + 3, 21 + kt * 27, ACC if kt == 0 else WARN)
    if pre_final(p, c, sc):
        return
    q = sc.get("q", 1)
    if sc.get("st") == "F":
        c.text("第%d節" % q if q <= 4 else "加時賽", R, 30, FG)
        c.text("犯規 %d:%d" % (sc.get("af", 0), sc.get("hf2", 0)), R, 44, DIM)
        if sc.get("tm"):
            c.text(sc["tm"], R, 58, FAINT)
        return
    r = p.clk(sc, p._now)
    bl = p.buf_left(sc, p._now) if sc.get("bf") else 0
    if bl:                                   # 延遲播放剛開始: 先顯示目前比分, 計時器緩衝中
        c.text(("第%d節" % q) if q <= 4 else ("加時%d" % (q - 4)), R, 14, ACC)
        c.text_big("%d" % bl, R, 28, FAINT, 2, 0)
        c.text("緩衝中", R + 28, 32, WARN)
    elif sc.get("brk"):
        c.text(("END of %d" % q) if q <= 4 else ("END OT%d" % (q - 4)), R, 14, WARN)
        c.text_big("0:00", R, 28, FAINT, 2, 0)
    else:
        c.text(("第%d節" % q) if q <= 4 else ("加時%d" % (q - 4)), R, 14, ACC)
        if r >= 0:
            c.text_big(ctext(r), R, 28, WARN if r < 600 else FG, 2, 0)
        else:
            clk = sc.get("c", "")
            c.text_big(clk or "--", R, 28, FG, 2, 0)
    mk = lambda b: "*" if b else ""
    c.text("犯規 %d%s/%d%s" % (sc.get("af", 0), mk(sc.get("ab")), sc.get("hf2", 0), mk(sc.get("hb"))), R, 54, FG)
    c.text("暫停 %d/%d" % (sc.get("at", 0), sc.get("ht", 0)), R, 66, DIM)


def detail(p, c, sc):
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
