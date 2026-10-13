"""比分板: 籃球 球隊數據 / 球員 (獨立檔案, 進入該頁才載入)"""
from ui import *

# 數據陣列順序: 投籃% 三分% 罰球% 籃板 助攻 失誤 禁區得分 快攻得分
ROWS = (((0, "投籃%"), (1, "三分%"), (2, "罰球%"), (3, "籃板")),
        ((4, "助攻"), (5, "失誤"), (6, "禁區"), (7, "快攻")))


def stats(p, c, sc, pg):
    ta, th = sc.get("ta"), sc.get("th")
    if not ta or not th:
        c.text("等待數據…", 6, 34, DIM)
        return
    c.text_c(sc.get("a", "?"), 22, 13, ACC)
    c.text_c(sc.get("h", "?"), 138, 13, ACC)
    for k, (i, lab) in enumerate(ROWS[pg]):
        y = 27 + k * 13
        x, y2 = ta[i], th[i]
        better = (x < y2) if i == 5 else (x > y2)          # 失誤越少越好
        c.text_c(str(x), 22, y, GOOD if better and x != y2 else FG)
        c.text_c(lab, 80, y, DIM)
        c.text_c(str(y2), 138, y, GOOD if (not better) and x != y2 else FG)


def leaders(p, c, sc):
    """各隊得分前兩名: 姓名 + 得分/籃板/助攻; 最下面一列是最近一次得分"""
    for r, (tm, key) in enumerate(((sc.get("a", "?"), "la"), (sc.get("h", "?"), "lh"))):
        rows = sc.get(key) or []
        for k in range(2):
            y = 14 + r * 26 + k * 12
            if k < len(rows):
                e = rows[k]
                ex = "%d/%d/%d" % (e[1], e[2], e[3])
                c.text_r(ex, 158, y, FG)
                p._txt(c, "n%d%d" % (r, k), tm + " " + (e[0] or ""), 2, y, 150 - c.text_w(ex), FG)
        if r == 0:
            c.hline(2, 39, 156, FAINT)
    c.hline(2, 65, 156, FAINT)
    ks = sc.get("ks")
    if ks:
        col = ACC if sc.get("kt") == 0 else WARN
        c.text("+%d" % ks, 2, 67, col)
        p._txt(c, "kn", (sc.get("a") if sc.get("kt") == 0 else sc.get("h")) + " " + (sc.get("kn") or ""), 24, 67, 134, col)
    else:
        c.text("分/板/助", 2, 67, FAINT)
