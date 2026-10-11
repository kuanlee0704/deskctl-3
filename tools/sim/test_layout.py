"""版面偵測: 逐像素檢查文字/圖示/捲動條有沒有互相壓到, 以及文字是否超出螢幕。"""
import sys, itertools
from harness import *
import numpy as np
import gfx, ui
from keys import *

issues = {}
owner = np.zeros((80, 160), np.int32)
labels = [""]
scenario = [""]


def report(kind, a, b=""):
    key = (scenario[0], kind, a, b)
    issues[key] = issues.get(key, 0) + 1


def arr_of(cv):
    return np.frombuffer(cv.buf, dtype=np.uint16).reshape(80, 160)


def claim(mask, label):
    hit = owner[mask]
    hit = hit[hit > 0]
    if hit.size:
        for o in set(hit.tolist()):
            report("OVERLAP", label, labels[o])
    labels.append(label)
    owner[mask] = len(labels) - 1


def wrap_ink(fn, name):
    def w(self, *a, **k):
        if self.w != 160:
            return fn(self, *a, **k)
        arr = arr_of(self)
        pre = arr.copy()
        arr[:] = 0x1234
        try:
            r = fn(self, *a, **k)
        finally:
            mask = arr != 0x1234
            arr[:] = np.where(mask, arr, pre)
        if name in ("text", "text_big"):
            s, x = a[0], a[1]
            if name == "text":
                wd = self.text_w(s)
            else:
                fixed = k.get("fixed", a[5] if len(a) > 5 else 0)
                kk = a[4] if len(a) > 4 else k.get("k")
                wd = (fixed * len(s) * kk) if fixed else self.text_w(s) * kk
            if x < 0 or x + wd > 160:
                report("OOB", "%s '%s' x=%d w=%d" % (name, s, x, wd))
            lab = "%s '%s'@%d,%d" % (name, s, x, a[2])
        else:
            lab = "icon@%d,%d" % (a[1], a[2])
        if mask.any():
            claim(mask, lab)
        return r
    return w


gfx.Canvas.text = wrap_ink(gfx.Canvas.text, "text")
gfx.Canvas.text_big = wrap_ink(gfx.Canvas.text_big, "text_big")
gfx.Canvas.icon = wrap_ink(gfx.Canvas.icon, "icon")

_fill, _frect = gfx.Canvas.fill, gfx.Canvas.frect


def fill(self, c):
    _fill(self, c)
    if self.w == 160:
        owner[:] = 0


def frect(self, x, y, w, h, c):
    _frect(self, x, y, w, h, c)
    if self.w == 160:
        x0, y0 = max(0, x), max(0, y)
        x1, y1 = min(160, x + w), min(80, y + h)
        if x1 <= x0 or y1 <= y0:
            return
        if min(w, h) <= 2 and c != ui.BG and c != ui.BAR:
            m = np.zeros((80, 160), bool)
            m[y0:y1, x0:x1] = True
            claim(m, "bar@%d,%d" % (x, y))
        else:
            owner[y0:y1, x0:x1] = 0


gfx.Canvas.fill, gfx.Canvas.frect = fill, frect

_mq = ui.Marquee.draw


def mq_draw(self, parent, x, y, text, now, bg=ui.BG, color=None):
    pre = arr_of(parent).copy()
    _mq(self, parent, x, y, text, now, bg, color)
    post = arr_of(parent)
    m = np.zeros((80, 160), bool)
    reg = post[y:y + self.h, x:x + self.w] != post[y, x] if False else None
    sub = post[y:y + self.h, x:x + self.w]
    ink = sub != np.uint16(arr_of(parent)[y, x]) if False else sub != _bgv(parent, bg)
    m[y:y + self.h, x:x + self.w] = ink
    if m.any():
        claim(m, "marquee '%s'@%d,%d" % (text[:8], x, y))


def _bgv(parent, bg):
    t = gfx.Canvas(1, 1, parent.font)
    arr_of_t = np.frombuffer(t.buf, dtype=np.uint16)
    t.fb.fill(bg)
    return arr_of_t[0]


ui.Marquee.draw = mq_draw

UNIX = 1791449340


def mk(over=None, synced=True, online=True):
    r = Rig(over or {})
    r.link.is_online = online
    if synced:
        r.msg(k="time", t=UNIX, z=480)
    r.msg(k="batt", p=47, ch=0)
    r.run(100)
    return r


def snap(r, name, n=1):
    scenario[0] = name
    for _ in range(n):
        r.app.draw(clock.now)
        r.run(300)


def track(r, title="Song", artist="Artist", s=1, d=200000, p=30000):
    r.msg(k="track", t=title, a=artist, b="", app="x", d=d)
    r.msg(k="play", s=s, p=p, v=100)
    r.msg(k="vol", v=7, m=15, mu=0)
    r.msg(k="route", r="bt", n="AirPods Pro Max Long Name")
    r.run(100)


# ---------- 首頁 ----------
for h24, secs, tsecs, batt, ch in itertools.product((0, 1), (0, 1), (0, 1), (5, 47, 100), (0, 1)):
    for mode in ("nolink", "playing", "paused", "longtitle"):
        r = mk({"h24": h24, "secs": secs, "tsecs": tsecs}, online=(mode != "nolink"))
        r.msg(k="batt", p=batt, ch=ch)
        if mode == "playing":
            track(r)
        elif mode == "paused":
            track(r, s=2)
        elif mode == "longtitle":
            track(r, "這是一首非常非常長的歌名 A Very Long Song Title Indeed", "歌手")
        r.run(100)
        r.shot("x", "/tmp") if False else None
        snap(r, "home h24=%d s=%d t=%d b=%d ch=%d %s" % (h24, secs, tsecs, batt, ch, mode), 2)
r = mk(synced=False, online=False); snap(r, "home unsynced")
r.app.toast("手機未連線"); snap(r, "home toast")

# ---------- 碼表 ----------
def sw_case(ms, laps=0, run=True, name=""):
    r = mk({"tsecs": 1})
    r.app.go("sw", clock.now)
    r.st.sw_acc = ms
    r.st.sw_run = run
    r.st.sw_t = clock.now
    r.st.sw_laps = [ms * (i + 1) // (laps + 1) for i in range(laps)] if laps else []
    r.run(100)
    snap(r, "sw %s %dms laps=%d run=%s" % (name, ms, laps, run), 2)
    return r

for ms in (0, 5000, 59 * 60000 + 59990, 3600000, 3600000 + 5 * 60000 + 7000, 12 * 3600000 + 34 * 60000 + 56000,
           99 * 3600000 + 59 * 60000 + 59000, 150 * 3600000):
    for laps in (0, 1, 2, 3, 9, 120):
        for run in (True, False):
            sw_case(ms, laps, run)
r = sw_case(7200000, 30)
for i in range(10):
    r.hold(K_UP, 700)
    snap(r, "sw scroll up %d" % i)
for i in range(40):
    r.hold(K_DOWN, 700)
snap(r, "sw scroll down")

# ---------- 音樂 ----------
for title, artist in (("Hi", "A"), ("這是一首非常非常長的歌名 A Very Long Song Title", "很長很長的歌手名字 Artist Name Here"), ("", "")):
    for s in (1, 2):
        for mu in (0, 1):
            for rt in ("bt", "spk", "wired"):
                for vol in (0, 7, 15):
                    r = mk()
                    r.app.go("music", clock.now)
                    r.msg(k="track", t=title, a=artist, b="", app="x", d=3 * 3600000 + 5000)
                    r.msg(k="play", s=s, p=2 * 3600000, v=100)
                    r.msg(k="vol", v=vol, m=15, mu=mu)
                    r.msg(k="route", r=rt, n="Headphones Name Very Long")
                    r.msg(k="mode", sh=1, rp=2)
                    r.run(100)
                    snap(r, "music %s s=%d mu=%d %s v=%d" % (title[:4], s, mu, rt, vol), 2)
r = mk(online=False); r.app.go("music", clock.now); snap(r, "music offline")

# ---------- 目錄 / 遊戲 / 設定 / 說明書 ----------
for src in ("home", "sw", "music", "games", "manual", "settings"):
    r = mk(); r.app.go(src, clock.now)
    r.hold(K_CENTER, 800)
    for i in range(8):
        snap(r, "menu src=%s sel=%d" % (src, i))
        r.click(K_DOWN)
r = mk(); r.app.go("games", clock.now)
for i in range(6):
    snap(r, "games sel=%d" % i); r.click(K_DOWN)
r = mk(); r.app.go("settings", clock.now)
for i in range(16):
    snap(r, "settings sel=%d" % i); r.click(K_DOWN)
for lab in ("藍牙診斷", "還原"):
    pass
r = mk(); r.app.go("manual", clock.now)
for i in range(20):
    snap(r, "manual p=%d" % i); r.click(K_DOWN)

# ---------- 1A2B ----------
r = mk(); r.app.go("ab", clock.now)
p = r.app.cur
snap(r, "ab fresh")
for n in range(1, 14):
    r.click(K_CENTER) if len(set(p.guess)) == 4 else None
    p.guess = [(n % 10), (n + 1) % 10, (n + 2) % 10, (n + 3) % 10]
    r.click(K_CENTER)
    snap(r, "ab hist=%d" % len(p.hist))
for i in range(12):
    r.hold(K_UP, 700)
snap(r, "ab scrolled up")
p.won = True; snap(r, "ab won")

# ---------- 骰子 / 蛇 ----------
r = mk(); r.app.go("dice", clock.now)
for i in range(6):
    snap(r, "dice %d" % i); r.click(K_CENTER); r.run(2500)
r = mk(); r.app.go("snake", clock.now)
snap(r, "snake menu")
r.press(K_CENTER); r.run(500); snap(r, "snake play")
r.run(30000); snap(r, "snake over")

# ---------- 隨機取數 ----------
r = mk(); r.app.go("rand", clock.now)
p = r.app.cur
for pos in range(9):
    p.pos = pos; snap(r, "rand setup pos=%d" % pos)
for lo, hi, n, uq in ((0, 9, 5, 1), (0, 999, 5, 1), (0, 999, 20, 0), (100, 200, 99, 1), (0, 5, 20, 1), (500, 100, 7, 1), (0, 999, 99, 0), (1, 1, 1, 0)):
    p.res = None
    p.dg = [lo // 100 % 10, lo // 10 % 10, lo % 10, hi // 100 % 10, hi // 10 % 10, hi % 10, n // 10, n % 10]
    p.uniq = uq
    r.click(K_CENTER)
    for s in range(30):
        snap(r, "rand res %d-%d n=%d uq=%d top=%d" % (lo, hi, n, uq, p.top))
        r.click(K_DOWN)
    assert p.res is not None and len(p.res) >= 1
    if uq:
        assert len(set(p.res)) == len(p.res), "unique violated"
    assert all(min(lo, hi) <= v <= max(lo, hi) for v in p.res)

# ---------- 捷徑 ----------
r = mk(); r.app.go("act", clock.now); snap(r, "act empty")
for names in (["A"], ["Samsung Music 播放", "Brave YouTube", "第三個很長很長很長很長很長很長的捷徑名稱", "d", "e", "f"]):
    r.msg(k="acts", l=names); r.run(100)
    for i in range(7):
        snap(r, "act n=%d sel=%d" % (len(names), i)); r.click(K_DOWN)
for ev in (("run", "很長很長很長很長很長很長很長的捷徑名稱", ""), ("fail", "x", "需要開啟無障礙服務"), ("busy", "", "")):
    r.msg(k="act", s=ev[0], n=ev[1], m=ev[2]); r.run(100); snap(r, "act toast " + ev[0])

# ---------- 手機狀態 ----------
r = mk(); r.app.go("sys", clock.now); snap(r, "sys empty")
full = dict(pl=2, t=-123, v=4180, th=3, nt=1, wf=4, bt=1, sc=1, ps=1, ds=1, bk=45, br=1, ra=88, rf=12288, ap=1234, ca=9999, sf=1234)
for sysd in (full, dict(nt=0, bk=10), dict(nt=2, bt=0, sc=0), dict(pl=0, t=452, v=3500, th=2, nt=1, wf=0, ra=95, rf=100, ap=9999, ca=9999, sf=5, bk=50, br=0), dict(t=300, v=4000, th=0, nt=1, wf=2, bk=20, ra=5, rf=9999, sf=9999)):
    r.msg(k="sys", **sysd); r.run(100)
    for i in range(5):
        snap(r, "sys %s sub=%d" % (sorted(sysd)[:2], r.app.cur.sub)); r.click(K_DOWN)
r.st.batt = 100; r.st.charging = True; r.app.cur.sub = 0; snap(r, "sys batt100")

# ---------- 手機訊息 ----------
r = mk(); r.app.go("msg", clock.now); snap(r, "msg empty")
longt = "這是一則很長很長的訊息內容, 用來測試自動換行與捲動功能是否正常, 還有 English words mixed 123456789 超過三行的情況"
for n in (1, 3, 5, 12):
    r = mk()
    for i in range(n):
        r.msg(k="msg", i=i + 1, a=["LINE", "簡訊", "Gmail", "Instagram 很長的名字的App"][i % 4], t=["王小明", "", "週報(很長很長很長很長很長的標題)", "x"][i % 4], x=longt if i % 2 else "好")
    r.run(100); snap(r, "msg toast n=%d" % n)
    r.app.go("msg", clock.now)
    for j in range(min(n, 6)):
        snap(r, "msg list n=%d sel=%d" % (n, j)); r.click(K_DOWN)
    r.click(K_CENTER)
    for j in range(8):
        snap(r, "msg detail n=%d s=%d" % (n, j)); r.click(K_DOWN)
    r.click(K_CENTER)
r = mk(); r.msg(k="msg", i=1, a="LINE", t="a", x="b"); r.run(100)
for h24, secs, tsecs in itertools.product((0, 1), (0, 1), (0, 1)):
    r.st.cfg.set("h24", h24); r.st.cfg.set("tsecs", tsecs); r.app.go("home", clock.now); snap(r, "topbar mail %d%d%d" % (h24, secs, tsecs))

# ---------- 系統總覽 ----------
r = mk(); r.app.go("dev", clock.now); r.run(100)
for n in (0, 3, 40):
    r.app.mem_hist[:] = [90000 - i * 700 + (i % 5) * 900 for i in range(n)]
    r.app.cur.sub = 0
    for i in range(5):
        snap(r, "dev n=%d sub=%d" % (n, r.app.cur.sub)); r.click(K_DOWN)
r.msg(k="sys", pl=2, t=345, v=4180, th=0, nt=1, wf=3, bt=1, sc=1, ps=0, ds=0, bk=20, br=0, ra=72, rf=3200, ap=312, ca=48, sf=245); r.st.batt = 100; r.run(100)
r.app.cur.sub = 0; snap(r, "dev with phone")
r.st.batt = 7; r.msg(k="sys", br=1, ra=95, nt=0); r.run(100); r.app.cur.sub = 0; snap(r, "dev phone bad")
r.app.t_boot = clock.now - 3 * 86400000; r.app.cur.sub = 0; snap(r, "dev uptime days"); r.app.cur.sub = 1; snap(r, "dev run days")
# 訊息: 標記
r = mk()
for i in range(4):
    r.msg(k="msg", i=i + 1, a="LINE", t="王小明", x="好" * (i * 15 + 1))
r.run(100); r.app.go("msg", clock.now); r.st.msgs[1][6] = True; r.st.msgs[0][5] = True
for j in range(4):
    snap(r, "msg marked list sel=%d" % j); r.click(K_DOWN)
r.click(K_CENTER); snap(r, "msg marked detail")

# ---------- 比分板 ----------
r = mk(); r.app.go("score", clock.now); r.run(100); snap(r, "score empty")
r.msg(k="games", l=[["MLB", "NYY", "BOS"], ["NBA", "LAL", "GSW"], ["CPBL", "樂天", "統一"], ["MLB", "SF", "LAD"]]); r.run(100)
snap(r, "score waiting")
base_live = dict(k="score", i=0, lg="MLB", st="L", a="NYY", h="BOS", **{"as": 12, "hs": 3}, **{"in": 10, "hf": 1, "tx": "10局", "b": 3, "s": 2, "o": 2, "bs": 7, "ah": 14, "hh": 5, "ae": 1, "he": 0, "bt": "Judge-Longname", "pt": "Crochet", "bavg": ".312", "bhab": "2-4", "pc": 87, "pip": "5.1", "per": 2, "ai": [0, 1, 0, 0, 3, 0, 5, 0, 0, 3], "hi": [0, 0, 1, 0, 0, 2, 0, 0, 0]})
for bs in (0, 1, 2, 4, 7):
    for st in ("L", "P", "F"):
        d = dict(base_live, bs=bs, st=st, tm="10/12 19:10")
        r.msg(**d); r.run(50)
        r.app.cur.sel = 0
        for v in (0, 1):
            r.app.cur.view = v; snap(r, "mlb bs=%d st=%s v=%d" % (bs, st, v))
for ai, hi in (([], []), ([0] * 9, [0] * 8), ([1] * 12, [2] * 11)):
    r.msg(**dict(base_live, ai=ai, hi=hi, st="L")); r.run(50)
    for v in (0, 1):
        r.app.cur.view = v; snap(r, "mlb innings %d v=%d" % (len(ai), v))
r.msg(**dict(base_live, **{"as": 0, "hs": 0, "mid": 1, "tx": "3中", "b": 0, "s": 0, "o": 0, "bs": 0, "in": 3, "hf": 1})); r.run(50)
r.app.cur.view = 0; snap(r, "mlb middle")
bk = dict(k="score", i=1, lg="NBA", st="L", a="LAL", h="GSW", **{"as": 112, "hs": 108}, q=4, c="0:45", af=7, hf2=5, at=1, ht=0, ab=1, hb=0, aq=[28, 30, 25, 29], hq=[27, 25, 30, 26])
for q, c_ in ((1, "12:00"), (2, "5:32"), (4, "0:45"), (5, "3:07"), (7, "0.8")):
    for st in ("L", "P", "F"):
        d = dict(bk, q=q, c=c_, st=st, tm="10/12 09:30", aq=bk["aq"] + ([5] * (q - 4) if q > 4 else []), hq=bk["hq"] + ([4] * (q - 4) if q > 4 else []))
        r.msg(**d); r.run(50)
        r.app.cur.sel = 1
        for v in (0, 1):
            r.app.cur.view = v; snap(r, "nba q=%d st=%s v=%d" % (q, st, v))
r.msg(**dict(bk, brk=1, st="L", q=2, c="")); r.run(50); r.app.cur.view = 0; snap(r, "nba break")
r.msg(**dict(bk, hb=1, ab=1, **{"as": 100, "hs": 100})); r.run(50); r.app.cur.view = 1; snap(r, "nba both bonus")
r.msg(**dict(k="score", i=2, lg="CPBL", st="L", a="樂天", h="統一", **{"as": 2, "hs": 1}, **{"in": 5, "hf": 0, "b": 1, "s": 1, "o": 1, "bs": 3, "ah": 4, "hh": 3, "ae": 0, "he": 0})); r.run(50)
r.app.cur.sel = 2
for v in (0, 1):
    r.app.cur.view = v; snap(r, "cpbl v=%d" % v)
for j in range(5):
    snap(r, "score switch %d" % j); r.click(K_RIGHT)

# ---------- 設定 toast ----------
r = mk(); r.app.go("sw", clock.now); r.app.toast("先暫停才能重設"); snap(r, "sw toast")

bad = [(k, v) for k, v in issues.items()]
bad.sort()
for (sc, kind, a, b), n in bad[:200]:
    print(kind, "|", sc, "|", a, "|", b)
print("\n%d layout issues" % len(bad))
sys.exit(1 if bad else 0)
