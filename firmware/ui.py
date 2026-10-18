"""共用 UI: 色彩、圖示、上方狀態列、跑馬燈。"""
import time
from gfx import rgb, Icon, Canvas
from state import civil
from config import MARQUEE_MS_PER_PX, MARQUEE_HOLD_MS, MARQUEE_GAP_PX

BG = rgb(6, 9, 15)
PANEL = rgb(22, 30, 46)
BAR = rgb(14, 20, 32)
FG = rgb(238, 241, 248)
DIM = rgb(125, 135, 155)
FAINT = rgb(58, 68, 90)
ACC = rgb(70, 190, 255)
GOOD = rgb(80, 220, 130)
WARN = rgb(255, 190, 60)
BAD = rgb(255, 90, 90)
PINK = rgb(255, 120, 190)

TOP_H = 13          # 上方狀態列高度; 內容區從 y=13 開始, 高 67

IC = {
    "play": Icon(["#.......", "###.....", "#####...", "#######.", "########", "#######.",
                  "#####...", "###.....", "#......."]),
    "pause": Icon(["###..###"] * 9),
    "next": Icon(["#......##", "##.....##", "###....##", "####...##", "#####..##",
                  "####...##", "###....##", "##.....##", "#......##"]),
    "prev": Icon(["##......#", "##.....##", "##....###", "##...####", "##..#####",
                  "##...####", "##....###", "##.....##", "##......#"]),
    "stopw": Icon(["...###...", "....#....", "..#####..", ".#..#..#.", "#...##..#",
                   "#...#.#.#", "#.......#", ".#.....#.", "..#####.."]),
    "bt": Icon(["...#...", "...##..", "#..#.#.", ".#.#.#.", "..###..", "...#...",
                "..###..", ".#.#.#.", "#..#.#.", "...##..", "...#..."]),
    "phone": Icon(["#######", "#######", "#.....#", "#.....#", "#.....#", "#.....#",
                   "#.....#", "#.....#", "###.###", "#######"]),
    "head": Icon(["...#####...", "..#.....#..", ".#.......#.", ".#.......#.", "###.....###",
                  "###.....###", "###.....###", "###.....###", ".#.......#."]),
    "spk": Icon(["....#...", "...##..#", "####..#.", "####.#.#", "####.#.#",
                 "####..#.", "...##..#", "....#..."]),
    "mute": Icon(["....#...", "...##...", "####....", "####.#.#", "####..#.", "####.#.#",
                  "...##...", "....#..."]),
    "shuf": Icon(["........##.", "#......#.#.", ".#....#..#.", "..#..#...##", "...##..#...",
                  "..#.#.#....", ".#..#..##..", "#......#.#.", "........##."]),
    "rep": Icon([".#########.", "#.........#", "#........##", "#.......###", "#..........",
                 "#..........", "#.##.......", "##.#......#", ".#########."]),
    "bat": Icon(["#######..", "#.....##.", "#.....##.", "#######.."]),
    "up": Icon(["...#...", "..###..", ".#####.", "#######"]),
    "down": Icon(["#######", ".#####.", "..###..", "...#..."]),
    "mail": Icon(["#######", "##...##", "#.#.#.#", "#..#..#", "#######"]),
    "bolt": Icon(["..##", ".##.", "####", ".##.", "##.."]),
}


def clock_str(st, now, h24=True, secs=False):
    if not st.t_synced:
        return "--:--"
    y, m, d, hh, mm, ss, wd = st.local(now)
    if not h24:
        hh = hh % 12 or 12
    return ("%02d:%02d:%02d" % (hh, mm, ss)) if secs else ("%02d:%02d" % (hh, mm))


def fmt_ms(ms):
    s = ms // 1000
    return "%d:%02d" % (s // 60, s % 60)


def draw_topbar(c, title, st, link, now, h24=True, secs=False, tag=""):
    """secs = 狀態欄是否顯示秒 (設定 tsecs, 與首頁的秒數設定分開)"""
    c.frect(0, 0, 160, TOP_H - 1, BAR)
    c.hline(0, TOP_H - 1, 160, FAINT)
    x = 158
    t = clock_str(st, now, h24, secs)
    x -= c.text_w(t)
    c.text(t, x, 0, FG if st.t_synced else DIM)
    x -= 4
    # 藍牙狀態
    online = link.online(now)
    col = GOOD if online else (WARN if link.connected else FAINT)
    x -= 7
    c.icon(IC["bt"], x, 1, col)
    x -= 3
    if st.msgs and st.cfg.get("msgt") and st.unread():       # 有未讀手機訊息
        x -= 7
        c.icon(IC["mail"], x, 3, PINK)
        x -= 3
    if online and st.has_track and st.pl == 1:      # 手機正在播放音訊
        x -= 8
        c.icon(IC["play"], x, 2, ACC)
        x -= 3
    if st.sw_run:
        x -= 9
        c.icon(IC["stopw"], x, 1, WARN)
        x -= 3
    if tag:                                  # 小標靠右固定畫出 (在圖示左邊), 標題只用剩下的空間
        x -= c.text_w(tag)
        c.text(tag, x, 0, WARN)
        x -= 2
    c.text(clip_text(c, title, x - 6), 4, 0, ACC)
    return x


class Marquee:
    """文字太長時左右來回捲動, 內容區自己的小畫布以免蓋到旁邊。"""

    _shared = None          # 所有捲動文字共用同一塊緩衝區 (每次都是先畫完立刻貼到螢幕, 不會同時使用), 省下好幾 KB

    def __init__(self, font, w, color, h=12):
        n = w * h * 2
        if Marquee._shared is None or len(Marquee._shared) < n:
            Marquee._shared = bytearray(max(n, 152 * 12 * 2))
        self.cv = Canvas(w, h, font, memoryview(Marquee._shared)[:n])
        self.w = w
        self.h = h
        self.color = color
        self.text = None
        self.tw = 0
        self.t0 = 0

    def draw(self, parent, x, y, text, now, bg=BG, color=None):
        if text != self.text:
            self.text = text
            self.tw = self.cv.text_w(text)
            self.t0 = now
        cv = self.cv
        cv.frect(0, 0, self.w, self.h, bg)
        col = color or self.color
        if self.tw <= self.w:
            cv.text(text, 0, 0, col)
        else:
            # 停一下 -> 一路往左捲, 滑出去後從右邊接回來, 持續循環
            cyc = self.tw + MARQUEE_GAP_PX
            t = time.ticks_diff(now, self.t0) % (MARQUEE_HOLD_MS + cyc * MARQUEE_MS_PER_PX)
            off = 0 if t < MARQUEE_HOLD_MS else (t - MARQUEE_HOLD_MS) // MARQUEE_MS_PER_PX
            cv.text(text, -off, 0, col)
            cv.text(text, cyc - off, 0, col)
        parent.blit(cv, x, y)

    @property
    def animating(self):
        return self.tw > self.w


def clip_text(c, s, maxw):
    """超過寬度就截斷加 …"""
    if c.text_w(s) <= maxw:
        return s
    while s and c.text_w(s + "…") > maxw:
        s = s[:-1]
    return s + "…"
