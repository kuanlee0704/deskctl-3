"""比分板: 賽後戰績 (兩隊賽季成績 + 系列賽/對戰狀況) (獨立檔案, 進入該頁才載入)
資料 sc["sr"] = {"t": 系列賽一行文字(可空), "r": [[標籤, 客隊值, 主隊值], ...]}; 有 t 時最多 3 列, 否則最多 4 列"""
from ui import *

AX, HX = 69, 128                  # 兩欄的中心 x


def rec(p, c, sc):
    sr = sc.get("sr")
    if not sr or not (sr.get("r") or sr.get("t")):
        c.text("暫無戰績資料", 6, 30, DIM)
        c.text("稍後會自動更新", 6, 46, FAINT)
        return
    c.text_c(sc.get("a", "?"), AX, 14, ACC)
    c.text_c(sc.get("h", "?"), HX, 14, WARN)
    t = sr.get("t") or ""
    rows = sr.get("r") or []
    for i in range(3 if t else 4):
        if i >= len(rows):
            break
        r = rows[i]
        y = 27 + i * 13
        c.text(r[0], 2, y, DIM)
        c.text_c(clip_text(c, str(r[1]), 58), AX, y, FG)
        c.text_c(clip_text(c, str(r[2]), 58), HX, y, FG)
    if t:
        p._txt(c, "sr", t, 2, 66, 154, GOOD)
