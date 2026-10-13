"""比分板: 棒球 得分紀錄 / 賽後打點與全壘打 (獨立檔案, 進入該頁才載入)"""
from ui import *


def plays(p, c, sc):
    """得分紀錄: 每個得分打席一列 (局上下, 打者, 結果, 當時比分); 超過 4 筆每 3 秒換頁"""
    sp = sc.get("sp") or []
    if not sp:
        c.text("尚無得分", 6, 34, DIM)
        return
    n = len(sp)
    pg = 0
    if n > 4:
        pg = (p._now // 3000) % ((n + 3) // 4)
        p._anim = True
    for k in range(4):
        i = pg * 4 + k
        if i >= n:
            break
        e = sp[i]
        y = 14 + k * 13
        c.text("%d%s" % (e[0], "下" if e[1] else "上"), 2, y, FG if e[1] else ACC)
        c.text_r("%d-%d" % (e[4], e[5]), 158, y, FG)
        x = 158 - c.text_w("%d-%d" % (e[4], e[5])) - 4
        ev = e[3] or ""
        c.text_r(ev, x, y, GOOD if ev in ("HR", "1B", "2B", "3B") else DIM)
        w = x - c.text_w(ev) - 4 - 30
        c.text(clip_text(c, e[2] or "", w), 30, y, FG)
    if n > 4:
        c.text_r("%d/%d" % (pg + 1, (n + 3) // 4), 158, 67, FAINT)


def stars(p, c, sc):
    """賽後: 兩隊打點王與全壘打"""
    a, h = sc.get("a", "?"), sc.get("h", "?")
    for k, (tm, key) in enumerate(((a, "ra"), (h, "rh"))):
        y = 14 + k * 13
        c.text("%s 打點" % tm, 2, y, ACC)
        p._txt(c, key, sc.get(key) or "--", 64, y, 94, FG)
    c.hline(2, 41, 156, FAINT)
    c.text("全壘打", 2, 44, WARN)
    p._txt(c, "hrs", sc.get("hrs") or "無", 2, 57, 154, FG)
