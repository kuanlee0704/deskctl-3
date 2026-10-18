"""手勢引擎 + 應用層行為測試 (桌面模擬)。 python3 test_all.py"""
import sys
from harness import *
from keys import *
import config

fails = []
def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + (" " + str(extra) if (extra and not cond) else ""))
    if not cond: fails.append(name)

# ---------- 手勢引擎 ----------
def drive(script, fast=()):
    """script: [(t_ms, key, down?)]; 回傳 [(t, key, ev)]"""
    ks = K.Keys(pins=[None]*5); ks.set_fast(fast)
    raw = [False]*5; out = []; t = 0; i = 0
    script = sorted(script)
    while t < 9000:
        while i < len(script) and script[i][0] <= t:
            raw[script[i][1]] = script[i][2]; i += 1
        for k, ev in ks.poll(t, list(raw)):
            out.append((t, k, ev))
        t += 5
    return out
def evs(out, k=None): return [e for (_, kk, e) in out if k is None or kk == k]
def noprs(out): return [e for e in evs(out) if e != EV_PRESS]

o = drive([(100, K_LEFT, True), (200, K_LEFT, False)])
check("click left", noprs(o) == [EV_CLICK] and o[-1][0] >= 200 + config.DBL_MS - 10, o)
o = drive([(100, K_LEFT, True), (170, K_LEFT, False), (250, K_LEFT, True), (320, K_LEFT, False)])
check("double left immediate", noprs(o) == [EV_DOUBLE] and o[-1][0] < 360, o)
o = drive([(100, K_CENTER, True), (170, K_CENTER, False), (250, K_CENTER, True), (320, K_CENTER, False)])
check("double center waits", noprs(o) == [EV_DOUBLE] and o[-1][0] >= 320 + config.DBL_MS - 10, o)
o = drive([(100+160*i, K_CENTER, d) for i in range(3) for d in (True,)] + [(170+160*i, K_CENTER, False) for i in range(3)])
check("triple center", noprs(o) == [EV_TRIPLE], o)
o = drive([(100, K_RIGHT, True), (1700, K_RIGHT, False)])
e = noprs(o)
check("long + holds + release", e[0] == EV_LONG and e[-1] == EV_RELEASE and e.count(EV_HOLD) >= 3 and EV_CLICK not in e, e)
tp = [t for t, k, ev in o if ev == EV_PRESS][0]
check("long fires at ~600ms after press", abs([t for t, k, ev in o if ev == EV_LONG][0] - tp - 600) <= 15, o[:3])
o = drive([(100, K_CENTER, True), (6000, K_CENTER, False)])
vt = [t for t, k, ev in o if ev == EV_VLONG]
tp = [t for t, k, ev in o if ev == EV_PRESS][0]
check("vlong at 5s once", len(vt) == 1 and abs(vt[0] - tp - 5000) <= 15 and EV_LONG in evs(o), vt)
o = drive([(100, K_LEFT, True), (170, K_LEFT, False)], fast=(K_LEFT,))
check("fast click immediate", [t for t, k, ev in o if ev == EV_CLICK][0] <= 200, o)
o = drive([(100, K_UP, True), (160, K_UP, False), (250, K_UP, True), (2000, K_UP, False)])
check("click then long flushes click", noprs(o)[:2] == [EV_CLICK, EV_LONG], noprs(o))
o = drive([(100, K_UP, True), (110, K_UP, False), (115, K_UP, True), (400, K_UP, False)])
check("bounce ignored", noprs(o) == [EV_CLICK], o)
o = drive([(100, K_UP, True), (110, K_UP, False)])
check("glitch <25ms ignored", evs(o) == [], o)

# ---------- 應用層 ----------
r = Rig()
r.msg(k="time", t=1791449340, z=480); r.msg(k="ping")
r.msg(k="track", t="晴天", a="周杰倫", b="葉惠美", d=269000); r.msg(k="play", s=1, p=83000, v=100)
r.msg(k="vol", v=9, m=15, mu=0); r.msg(k="mode", sh=0, rp=0)
r.run(200)
check("online after msgs", r.link.online(clock.now))
check("pong sent", {"c": "pong"} in r.link.sent)
a = r.app
check("starts at home", a.cur_id == "home")
r.click(K_RIGHT); check("home right -> stopwatch", a.cur_id == "sw")
r.click(K_LEFT); check("sw left -> home", a.cur_id == "home")
r.click(K_LEFT); check("left wraps ring -> music", a.cur_id == "music")
r.link.sent.clear()
# 音樂
r.click(K_CENTER); check("music center click -> pp", {"c": "pp"} in r.link.sent, r.link.sent)
r.link.sent.clear(); r.click(K_RIGHT); check("right click -> +5s", {"c": "seek", "d": 5000} in r.link.sent, r.link.sent)
r.link.sent.clear(); r.click(K_LEFT); check("left click -> -5s", {"c": "seek", "d": -5000} in r.link.sent, r.link.sent)
r.link.sent.clear(); r.double(K_RIGHT); check("right double -> next (no seek)", r.link.sent == [{"c": "next"}], r.link.sent)
r.link.sent.clear(); r.double(K_LEFT); check("left double -> prev", r.link.sent == [{"c": "prev"}], r.link.sent)
r.link.sent.clear(); r.click(K_UP); check("up -> vol +1", r.link.sent == [{"c": "vol", "d": 1}], r.link.sent)
r.link.sent.clear(); r.click(K_DOWN); check("down -> vol -1", r.link.sent == [{"c": "vol", "d": -1}], r.link.sent)
r.link.sent.clear(); r.double(K_UP); check("up double -> shuffle on", r.link.sent == [{"c": "shuffle", "v": 1}], r.link.sent)
r.link.sent.clear(); r.double(K_DOWN); check("down double -> repeat 1", r.link.sent == [{"c": "repeat", "v": 1}], r.link.sent)
r.double(K_DOWN); check("repeat cycles to 2", r.st.repeat == 2); r.double(K_DOWN); check("repeat back to 0", r.st.repeat == 0)
r.link.sent.clear(); r.double(K_CENTER); check("center double -> mute (not menu)", r.link.sent == [{"c": "mute"}] and a.cur_id == "music", (r.link.sent, a.cur_id))
r.link.sent.clear(); r.hold(K_RIGHT, 2000)
seeks = [m for m in r.link.sent if m.get("c") == "seek"]
check("hold right -> continuous seeks", len(seeks) >= 5, len(seeks))
check("hold right sends no next/pp", all(m.get("c") in ("seek","stat") for m in r.link.sent), r.link.sent)
# 目錄
r.hold(K_CENTER, 800); check("center long -> menu", a.cur_id == "menu")
r.hold(K_CENTER, 800); check("center long again -> back to music", a.cur_id == "music")
r.hold(K_CENTER, 800); r.click(K_DOWN); r.click(K_DOWN); r.click(K_CENTER)
check("menu select -> games", a.cur_id == "games", a.cur_id)
r.triple(K_CENTER); check("triple center -> home", a.cur_id == "home", a.cur_id)
r.triple(K_CENTER); check("triple again toggles back", a.cur_id == "games", a.cur_id)
# 重設 / 重啟
r.app.go("music"); r.link.sent.clear()
r.hold(K_UP, 5400); check("up 5s -> music resync", {"c": "sync"} in r.link.sent, r.link.sent)
r.hold(K_CENTER, 5400); check("center 5s -> reboot", r.rebooted)
# 碼表
r = Rig(); r.msg(k="time", t=1791449340, z=480); r.run(100); a = r.app
r.click(K_RIGHT); check("at sw", a.cur_id == "sw")
r.click(K_CENTER); check("sw start", r.st.sw_run)
r.run(1500); r.click(K_UP); check("lap recorded", len(r.st.sw_laps) == 1)
r.click(K_RIGHT) if False else None
a.go("home"); r.run(1000); check("keeps running in background", r.st.sw_run and r.st.sw_elapsed(clock.now) > 2000)
check("timer icon state", r.st.sw_run)
a.go("sw"); r.click(K_CENTER); check("sw pause", not r.st.sw_run)
e1 = r.st.sw_elapsed(clock.now); r.run(1000); check("paused holds time", r.st.sw_elapsed(clock.now) == e1)
r.click(K_DOWN); check("reset when paused", r.st.sw_acc == 0 and not r.st.sw_laps)
r.click(K_CENTER); r.click(K_DOWN); check("down while running doesn't reset", r.st.sw_run)
r.double(K_DOWN); check("down double forces reset", not r.st.sw_run and r.st.sw_acc == 0)
# 熄屏
r = Rig({"idle": 30}); r.msg(k="time", t=1791449340, z=480); r.run(100)
r.run(31000); check("auto sleep", r.app.sleeping)
check("screen off: backlight 0 + panel off", r.lcd.bl == 0 and r.lcd.disp is False, (r.lcd.bl, getattr(r.lcd, "disp", None)))
r.press(K_UP, 80); r.run(400); check("wake on press", not r.app.sleeping)
check("wake restores backlight+panel", r.lcd.bl == 80 and r.lcd.disp is True, (r.lcd.bl, r.lcd.disp))
check("waking key swallowed (stay home)", r.app.cur_id == "home")
# 設定
r = Rig(); a = r.app; a.go("settings"); r.run(100)
r.click(K_RIGHT); check("setting h24 toggled", r.st.cfg.get("h24") == 1)
# 遊戲邏輯
import random
random.seed(1)
a.go("ab"); g = a.cur; g.secret = list("5678"); g.guess = [5, 6, 8, 9]
r.click(K_CENTER); check("1A2B 2A1B", g.hist[-1] == ("5689", 2, 1) or g.hist[-1][1:] == (2, 1), g.hist)
g.guess = [5, 6, 7, 8]; r.click(K_CENTER); check("1A2B win", g.won)
a.go("snake"); s = a.cur; s.new(); s.body = [(5, 5), (4, 5), (3, 5)]; s.dir = (1, 0); s.food = (6, 5)
s.step(); check("snake eats", s.score == 1 and len(s.body) == 4)
s.body = [(10, 5), (9, 5), (9, 6), (10, 6), (11, 6), (11, 5)]; s.dir = (0, 1); s.turn = []
s.body = [(10, 5), (10, 6), (11, 6), (11, 5), (11, 4), (10, 4)]
s.dir = (1, 0); s.step(); check("snake self collision", s.over)

# ---------- 首頁控制音樂 ----------
r = Rig(); r.msg(k="time", t=1791449340, z=480); r.msg(k="ping")
r.msg(k="track", t="晴天", a="周杰倫", d=269000); r.msg(k="play", s=1, p=83000, v=100); r.msg(k="vol", v=9, m=15, mu=0)
r.run(200); a = r.app
check("home start", a.cur_id == "home")
r.link.sent.clear(); r.click(K_CENTER); check("home center -> pp", r.link.sent == [{"c": "pp"}], r.link.sent)
r.link.sent.clear(); r.double(K_CENTER); check("home center double -> mute", r.link.sent == [{"c": "mute"}] and a.cur_id == "home", r.link.sent)
r.link.sent.clear(); r.click(K_UP); check("home up -> vol+1", r.link.sent == [{"c": "vol", "d": 1}], r.link.sent)
r.link.sent.clear(); r.click(K_DOWN); check("home down -> vol-1", r.link.sent == [{"c": "vol", "d": -1}], r.link.sent)
r.link.sent.clear(); r.double(K_UP); check("home up double -> shuffle", r.link.sent == [{"c": "shuffle", "v": 1}], r.link.sent)
r.link.sent.clear(); r.double(K_DOWN); check("home down double -> repeat", r.link.sent == [{"c": "repeat", "v": 1}], r.link.sent)
r.link.sent.clear(); r.double(K_RIGHT); check("home right double -> next (stay home)", r.link.sent == [{"c": "next"}] and a.cur_id == "home", (r.link.sent, a.cur_id))
r.link.sent.clear(); r.double(K_LEFT); check("home left double -> prev", r.link.sent == [{"c": "prev"}], r.link.sent)
r.link.sent.clear(); r.hold(K_RIGHT, 2000)
check("home right hold -> seeks only", len([m for m in r.link.sent if m.get("c") == "seek"]) >= 5 and all(m.get("c") == "seek" for m in r.link.sent) and a.cur_id == "home", (a.cur_id, r.link.sent[:3]))
r.link.sent.clear(); r.click(K_RIGHT); check("home right click -> page switch, no command", a.cur_id == "sw" and r.link.sent == [], (a.cur_id, r.link.sent))
r.click(K_LEFT); check("back to home", a.cur_id == "home")
r.link.is_online = False; r.link.sent.clear()
r.link.send = lambda d: False
r.click(K_CENTER); check("offline toast", a._toast == "手機未連線", a._toast)


# ===== v0.8 =====
from power import A, D, L, O
r = Rig({"tsecs": 0}); r.msg(k="time", t=1791449340, z=480); r.run(100); a = r.app
check("default 12h + home secs on + topbar secs off", r.st.cfg.get("h24") == 0 and r.st.cfg.get("secs") == 1 and r.st.cfg.get("tsecs") == 0)
check("active backlight 80", r.lcd.bl == 80, r.lcd.bl)
r.run(61000); check("dim after 60s", a.pm.state == D and r.lcd.bl == 40, (a.pm.state, r.lcd.bl))
r.press(K_UP, 80); r.run(300); check("press back to active", a.pm.state == A and r.lcd.bl == 80)
# 低亮度: 沒播歌 -> 切首頁大時鐘, 按鍵喚醒回原頁
a.go("sw"); r.run(100)
r.run(601000); check("low 15% + clock page", a.pm.state == L and r.lcd.bl == 15 and a.cur_id == "home", (a.pm.state, r.lcd.bl, a.cur_id))
r.press(K_CENTER, 80); r.run(300)
check("wake -> back to stopwatch, key swallowed", a.cur_id == "sw" and a.pm.state == A and not r.st.sw_run, (a.cur_id, a.pm.state))
# 播歌中低亮度: 不換頁
a.go("music"); r.msg(k="track", t="Song", a="A", b="", app="x", d=100000); r.msg(k="play", s=1, p=0, v=100); r.run(100)
r.run(601000); check("playing: low but stay on music", a.pm.state == L and a.cur_id == "music", (a.pm.state, a.cur_id))
# 手機開始播放 -> 喚醒
r.msg(k="play", s=2, p=1000, v=100); r.run(100); r.msg(k="play", s=1, p=1000, v=100); r.run(200)
check("phone starts playing -> wake", a.pm.state == A, a.pm.state)
# 狀態欄播放圖示 (像素偵測: 有播放時右上區域比暫停多出內容)
a.go("home"); r.run(200); r.shot("pl_on", "/tmp")
f1 = bytes(r.lcd.frame)
r.msg(k="play", s=2, p=1000, v=100); r.run(200); r.shot("pl_off", "/tmp")
check("topbar play icon appears only when playing", f1 != bytes(r.lcd.frame))
# 碼表捲動
a.go("sw"); r.run(100); r.st.sw_acc = 5000; r.st.sw_run = True; r.st.sw_t = clock.now if False else r.st.sw_t
r.click(K_CENTER) if not r.st.sw_run else None
r.st.sw_laps = [1000 * (i + 1) for i in range(8)]; a.cur.dirty = True; r.run(100)
r.hold(K_UP, 1500)
check("stopwatch long up scrolls laps", a.cur.top < 6 and not a.cur.follow, (a.cur.top, a.cur.follow))
r.hold(K_DOWN, 3500)
check("stopwatch long down returns to latest", a.cur.top == 6 and a.cur.follow, (a.cur.top, a.cur.follow))
# 1A2B 捲動
a.go("ab"); r.run(100); p = a.cur
for n in range(1, 9):
    p.guess = [n, (n + 1) % 10, (n + 2) % 10, (n + 3) % 10]; r.click(K_CENTER)
check("ab history auto-follows latest", p.scroll == 3, p.scroll)
r.hold(K_UP, 1500); check("ab long up scrolls history", p.scroll < 3, p.scroll)
g0 = list(p.guess); r.click(K_UP); check("ab click up still changes digit", p.guess != g0)
# 隨機取數
a.go("rand"); r.run(100); p = a.cur
p.dg = [0, 0, 0, 0, 1, 0, 0, 5]; p.uniq = 1; r.click(K_CENTER)
check("rand unique 5 in 0..10", p.res is not None and len(set(p.res)) == 5 and all(0 <= v <= 10 for v in p.res), p.res)
p.dg = [0, 0, 0, 0, 0, 3, 0, 9]; r.click(K_CENTER); p.res = None; r.click(K_CENTER)
check("rand unique clamped to range size", len(p.res) == 4 and len(set(p.res)) == 4, p.res)
p.dg = [0, 0, 0, 9, 9, 9, 6, 0]; p.res = None; r.click(K_CENTER)
check("rand 60 results scroll", p.rows() > 4); t0 = p.top; r.click(K_DOWN); check("rand down scrolls", p.top == t0 + 1)
r.hold(K_DOWN, 3000); check("rand hold scroll to end", p.top == p.rows() - 4, (p.top, p.rows()))
r.double(K_CENTER); check("rand result double -> setup", p.res is None)
check("rand settings persisted", r.st.cfg.get("rnd")[2] == 60, r.st.cfg.get("rnd"))
r = Rig({"dimt": 15, "lowt": 30, "idle": 120}); r.msg(k="time", t=1791449340, z=480); r.run(100); a = r.app
r.run(16000); check("custom dim 15s", a.pm.state == D, a.pm.state)
r.run(15000); check("custom low 30s", a.pm.state == L, a.pm.state)
r.run(90000); check("custom off 120s", a.pm.state == O, a.pm.state)
# ===== 捷徑 =====
r = Rig(); a = r.app; r.msg(k="time", t=1791449340, z=480); r.run(100)
r.msg(k="acts", l=["Samsung Music 播放", "Brave YouTube", "第三個很長很長很長很長很長很長的捷徑名稱"]); r.run(100)
check("acts stored", len(r.st.acts) == 3, r.st.acts)
a.go("act"); r.run(100); p = a.cur
r.link.sent.clear(); r.click(K_CENTER)
check("run sent idx 0", {"c": "run", "i": 0} in r.link.sent, r.link.sent)
r.click(K_DOWN); r.click(K_DOWN); r.link.sent.clear(); r.click(K_CENTER)
check("run sent idx 2", {"c": "run", "i": 2} in r.link.sent, r.link.sent)
r.click(K_DOWN); check("selection wraps", p.sel == 0, p.sel)
r.msg(k="act", s="done", n="Brave YouTube"); r.run(100)
check("done toast", a._toast is not None and "完成" in a._toast, a._toast)
r.msg(k="act", s="fail", n="x", m="需要開啟無障礙服務"); r.run(100)
check("fail toast", "失敗" in a._toast, a._toast)
r.link.is_online = False; r.link.sent.clear(); r.click(K_CENTER)
check("offline: not sent + toast", not r.link.sent and "未連線" in a._toast, (r.link.sent, a._toast))
r.link.is_online = True
r.hold(K_UP, 5300); check("5s dir hold -> sync", {"c": "sync"} in r.link.sent, r.link.sent)
r = Rig(); r.run(100); r.app.go("act"); r.run(100)
r.link.sent.clear(); r.click(K_CENTER); check("empty list: no send", not r.link.sent)
# ---------- 手機訊息 ----------
r = Rig({"idle": 30}); r.msg(k="time", t=1791449340, z=480); r.run(100); a = r.app
r.msg(k="msg", i=1, a="LINE", t="小明", x="吃飯了嗎"); r.run(100)
check("msg stored", len(r.st.msgs) == 1 and r.st.unread() == 1, r.st.msgs)
check("msg toast shown", a._toast and "LINE" in a._toast, a._toast)
r.msg(k="msg", i=1, a="LINE", t="小明", x="更新"); r.run(50)
check("same id replaces", len(r.st.msgs) == 1 and r.st.msgs[0][3] == "更新")
r.msg(k="msg", i=2, a="簡訊", t="x", x="y"); r.run(50)
check("newest first", [m[0] for m in r.st.msgs] == [2, 1])
r.msg(k="msgrm", i=2); r.run(50)
check("msgrm removes", [m[0] for m in r.st.msgs] == [1])
for i in range(20):
    r.msg(k="msg", i=100 + i, a="A", t="t", x="x")
r.run(50); check("capped at 12", len(r.st.msgs) == 12)
a.go("msg"); r.run(100); check("opening page marks read", r.st.unread() == 0)
r.click(K_CENTER); check("center opens detail", a.cur.detail)
r.click(K_CENTER); check("center closes detail", not a.cur.detail)
# 總覽: 長按左=刪此則; 長按右=全刪(標記保留)
r.msg(k="msg", i=901, a="A", t="one", x="1"); r.msg(k="msg", i=902, a="B", t="two", x="2"); r.msg(k="msg", i=903, a="C", t="three", x="3"); r.run(100)
a.go("msg"); r.run(100); n0 = len(r.st.msgs)
r.hold(K_LEFT, 900); check("list: long-left deletes one", len(r.st.msgs) == n0 - 1, (n0, len(r.st.msgs)))
r.click(K_CENTER); check("open detail", a.cur.detail)
r.hold(K_RIGHT, 900); check("detail: long-right marks", r.st.msgs[a.cur.sel][6] is True)
mid = r.st.msgs[a.cur.sel][0]; n1 = len(r.st.msgs)
r.hold(K_LEFT, 900); check("marked cannot be deleted", len(r.st.msgs) == n1 and any(m[0] == mid for m in r.st.msgs))
r.msg(k="msgrm", i=mid); r.run(50); check("phone msgrm keeps marked", any(m[0] == mid for m in r.st.msgs))
r.click(K_CENTER); r.hold(K_RIGHT, 900)
check("list: long-right deletes all but marked", [m[0] for m in r.st.msgs] == [mid], [m[0] for m in r.st.msgs])
r.click(K_CENTER); r.hold(K_RIGHT, 900); check("detail: long-right unmarks", r.st.msgs[0][6] is False)
r.hold(K_LEFT, 900); check("unmarked can be deleted", len(r.st.msgs) == 0, len(r.st.msgs))
for i in range(14):
    r.msg(k="msg", i=500 + i, a="A", t="t", x="x")
r.run(50); a.go("msg"); r.run(50); r.click(K_CENTER); r.hold(K_RIGHT, 900); r.click(K_CENTER)
mk = r.st.msgs[a.cur.sel][0]
for i in range(20):
    r.msg(k="msg", i=700 + i, a="A", t="t", x="x")
r.run(50); check("cap keeps marked", len(r.st.msgs) <= 12 and any(m[0] == mk for m in r.st.msgs), len(r.st.msgs))

# ---------- 手機清理 ----------
r = Rig(); r.run(100); r.app.go("sys"); r.run(100); r.link.is_online = False
r.link.sent.clear(); r.click(K_CENTER); check("sys center offline: no clean", not any(str(m.get("c","")).startswith("clean") for m in r.link.sent), r.link.sent)
r.link.is_online = True; r.link.sent.clear(); r.click(K_CENTER); check("sys center -> clean", {"c": "clean"} in r.link.sent, r.link.sent)
r.link.sent.clear(); r.double(K_CENTER); check("sys center double -> clean2", {"c": "clean2"} in r.link.sent, r.link.sent)
r.msg(k="act", s="done", n="清理", m="釋放約 300MB"); r.run(100); check("clean result toast", r.app._toast and "300MB" in r.app._toast, r.app._toast)

# ---------- 比分板 ----------
r = Rig(); r.run(100); r.link.sent.clear(); a = r.app; a.go("score"); r.run(100)
check("score enter sends sc on", {"c": "sc", "on": 1, "sel": 0} in r.link.sent, r.link.sent)
r.msg(k="games", l=[["MLB", "NYY", "BOS"], ["NBA", "LAL", "GSW"], ["CPBL", "樂天", "統一"]]); r.run(50)
check("games stored", len(r.st.games) == 3 and r.st.games[1][0] == "NBA", r.st.games)
r.msg(k="score", i=1, lg="NBA", st="L", a="LAL", h="GSW", q=2, c="1:00"); r.run(50)
check("score stored", r.st.scores.get(1, {}).get("q") == 2)
check("start slot 0", a.cur.sel == 0)
r.click(K_RIGHT); check("right -> next game", a.cur.sel == 1 and a.cur_id == "score", (a.cur.sel, a.cur_id))
r.click(K_LEFT); r.click(K_LEFT); check("left wraps", a.cur.sel == 2)
a.cur.sel = 1; r.click(K_DOWN); check("down wraps to last view", a.cur.view == 4, a.cur.view); r.click(K_UP); check("up -> main view", a.cur.view == 0); r.click(K_UP); check("up -> detail view", a.cur.view == 1); a.cur.view = 0
# --- 棒球即時: 4 個畫面循環; 精簡/完整資料; 籃球計時器往下推算 ---
r.msg(k="score", i=0, lg="MLB", st="L", a="NYY", h="BOS", **{"as": 1, "hs": 0}, bt="S. Judge", pt="G. Crochet", ar="90-72", hr="85-77")
a.cur.sel = 0; a.cur._tell(); r.run(20)
check("full data kept for selected", "bt" in r.st.scores[0], r.st.scores.get(0))
check("lite data for others", "q" in r.st.scores[1] and "af" not in r.st.scores[1], r.st.scores.get(1))
vs = []
for _ in range(5):
    r.click(K_UP); vs.append(a.cur.view)
check("mlb live cycles 5 views", vs == [1, 2, 3, 4, 0], vs)
r.click(K_DOWN); check("down wraps back to 4", a.cur.view == 4, a.cur.view)
a.cur.view = 0
r.msg(k="score", i=1, lg="NBA", st="L", a="LAL", h="GSW", q=2, cs=3000, cr=1); r.run(20)
a.cur.sel = 1; a.cur._tell(); r.msg(k="score", i=1, lg="NBA", st="L", a="LAL", h="GSW", q=2, cs=3000, cr=1); r.run(20)
r0 = a.cur.clk(r.st.scores[1], a.cur._now)
r.run(2000); r1 = a.cur.clk(r.st.scores[1], a.cur._now)
check("nba clock counts down", 0 < r0 - r1 <= 25 and r1 < r0, (r0, r1))
r.msg(k="score", i=1, lg="NBA", st="L", a="LAL", h="GSW", q=2, cs=3000, cr=0); r.run(2000)
check("nba clock holds when stopped", a.cur.clk(r.st.scores[1], a.cur._now) == 3000)
check("leave trims all to lite", True)
a.cur.sel = 2
r.msg(k="games", l=[["MLB", "NYY", "BOS"]]); r.run(50); check("shrunk games drops scores", 1 not in r.st.scores)
r.link.sent.clear(); a.go("home"); r.run(50); check("leave sends sc off", {"c": "sc", "on": 0} in r.link.sent, r.link.sent)

# ---- 清理記憶體 ----
r.app.go("dev", clock.now) if hasattr(r.app, "go") else None
b, f = r.app.free_mem(reply=False)
check("free_mem returns ints", isinstance(b, int) and isinstance(f, int), (b, f))
check("free_mem resets min", r.app.mem_min == f or r.app.mem_min <= f)
r.msg(k="memclean"); r.run(50)
check("memclean msg ok", True)
print("memclean tests done")

# ---- 設定檔安全寫入 ----
import os as _os, tempfile as _tf
_cwd = _os.getcwd(); _d = _tf.mkdtemp(); _os.chdir(_d)
try:
    import state as _stm
    S = _stm.Settings(); S.set("bright", 33)
    check("settings saved", _os.path.exists("settings.json") and not _os.path.exists("settings.json.tmp"))
    S.set("bright", 44)
    check("settings overwrite ok", _stm.Settings().get("bright") == 44)
    open("settings.json", "w").write('{"bright": 5')            # 主檔寫壞
    open("settings.json.tmp", "w").write('{"bright": 77}')      # 暫存檔完整
    check("recover from tmp", _stm.Settings().get("bright") == 77)
    open("settings.json.tmp", "w").write('{"bri')               # 兩個都壞 -> 預設值, 不當機
    check("both corrupt -> defaults", _stm.Settings().get("bright") == _stm.DEFAULTS["bright"])
finally:
    _os.chdir(_cwd)

# ---- 手機控制器頁 ----
r.app.go("pad", clock.now); a = r.app; a.pm.wake(clock.now); r.run(20)
check("pad page opens", a.cur_id == "pad", a.cur_id)
r.msg(k="pad", n="瀏覽", i=0, t=2, l=["上滾", "右滾", "下滾", "左滾", "點擊"], g=0b0001); r.run(300)
check("pad state stored", r.st.pad and r.st.pad[0] == "瀏覽" and r.st.pad[3][4] == "點擊", r.st.pad)
check("fast: no-double dirs fast, up (has double) not", K_UP not in a.cur.fast and K_DOWN in a.cur.fast and K_LEFT in a.cur.fast and K_RIGHT in a.cur.fast, (a.cur.fast, a.cur._pv, r.st.pad_v, a.cur.rot, r.st.pad))
r.link.sent.clear()
r.click(K_DOWN); r.run(10)
check("down click sent as logical D", {"c": "pad", "d": 2, "e": "c"} in r.link.sent, r.link.sent)
r.link.sent.clear()
r.double(K_UP); r.run(10)
check("up double sent", {"c": "pad", "d": 0, "e": "d"} in r.link.sent, r.link.sent)
r.link.sent.clear()
r.click(K_CENTER); r.run(40)
check("center click sent", {"c": "pad", "d": 4, "e": "c"} in r.link.sent, r.link.sent)
r.double(K_CENTER); r.run(20)
check("center double rotates 90", a.cur.rot == 1 and a.cur_id == "pad", a.cur.rot)
check("fast mask follows rotation", K_RIGHT not in a.cur.fast and K_UP in a.cur.fast, a.cur.fast)
r.link.sent.clear()
r.click(K_RIGHT); r.run(10)          # 轉 90° 後, 實體的「右」= 邏輯的「上」
check("rotated: physical right = logical up", {"c": "pad", "d": 0, "e": "c"} in r.link.sent, r.link.sent)
r.link.sent.clear()
r.hold(K_DOWN, 1000); r.run(10)
es = [m.get("e") for m in r.link.sent if isinstance(m, dict) and m.get("c") == "pad"]
check("hold sends l then repeated h", "l" in es and es.count("h") >= 1 and all(m.get("d") == 1 for m in r.link.sent if isinstance(m, dict) and m.get("c") == "pad"), es)
r.triple(K_CENTER); r.run(20)
check("center triple -> home", a.cur_id == "home", a.cur_id)
a.cur.rot = 0 if hasattr(a.cur, "rot") else 0
r.st.cfg.set("padrot", 0)

# ---- 標題列更新秒數: 一定要顯示 (即使標題很長、圖示很多) ----
import ui as _ui
r.app.go("score", clock.now); a.cur.sel = 0
r.msg(k="score", i=0, lg="MLB", st="L", a="NYY", h="BOS", **{"as": 1, "hs": 0}); r.run(30)
r.st.sw_run = True; r.st.msgs = [(1, "x", "y", 0, 0, 1)]
a.cur.view = 4
r.run(10)
tg = a.cur.get_tag()
check("age tag present while live", tg.startswith("("), tg)
r.msg(k="score", i=0, lg="MLB", st="F", a="NYY", h="BOS", **{"as": 1, "hs": 0}); r.run(30)
check("age tag present when final", a.cur.get_tag().startswith("("), a.cur.get_tag())
r.st.sw_run = False; r.st.msgs = []

# ---- 籃球延遲播放: 兩筆已知資料之間插值, 不預測 ----
r.app.go("score", clock.now); a = r.app; a.pm.wake(clock.now)
r.msg(k="games", l=[["NBA", "LAL", "GSW"]]); r.run(30)
a.cur.sel = 0; a.cur._tell(); r.run(30)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=2, c="x", cs=6000, cr=0, bf=15, dl=15, **{"as": 10, "hs": 8}); r.run(60)
sc0 = r.st.scores[0]
check("buffering shown", a.cur.buf_left(sc0, a.cur._now) > 0, a.cur.buf_left(sc0, a.cur._now))
r.run(16000)
check("buffer done", a.cur.buf_left(r.st.scores[0], a.cur._now) == 0)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=2, c="x", cs=6000, cn=5960, cd=4000, cr=0, dl=15, **{"as": 12, "hs": 8}); r.run(10)
t0 = a.cur._now
c0 = a.cur.clk(r.st.scores[0], t0)
r.run(2000)
c1 = a.cur.clk(r.st.scores[0], a.cur._now)
check("counts down 1s/s (halfway)", 5970 <= c1 <= 5990, (c0, c1))
r.run(6000)
c2 = a.cur.clk(r.st.scores[0], a.cur._now)
check("holds at next known value (no overshoot)", c2 == 5960, c2)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=2, c="x", cs=5600, cr=0, dl=15, **{"as": 12, "hs": 8}); r.run(300)
tg2 = a.cur.get_tag(); check("age tag resets to 0 on update (no delay added)", tg2 in ("(:00)", "(:01)"), repr(tg2))
r.msg(k="games", l=[["NBA", "LAL", "GSW"], ["NBA", "BOS", "MIA"]]); r.run(30)
a.cur.view = 2; a.cur.sel = 0; a.cur.on_key(K_RIGHT, EV_CLICK, clock.now); r.run(10)
check("switching game returns to main view", a.cur.view == 0 and a.cur.sel == 1, (a.cur.view, a.cur.sel))
a.cur.sel = 0; a.cur._tell(); r.run(30)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=2, c="x", cs=6000, cn=6000, cd=4000, cr=0, dl=15, **{"as": 12, "hs": 8}); r.run(3000)
check("stopped clock stays", a.cur.clk(r.st.scores[0], a.cur._now) == 6000)

# ---- 籃球 +N: 手機沒給時板子自己比對 ----
r.app.go("score", clock.now); a = r.app; a.pm.wake(clock.now)
r.msg(k="games", l=[["NBA", "LAL", "GSW"]]); r.run(30)
a.cur.sel = 0; a.cur._tell(); r.run(30)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=1, c="5:00", **{"as": 10, "hs": 8}); r.run(30)
check("no +N before any change", not r.st.kk.get(0), r.st.kk)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=1, c="4:40", **{"as": 10, "hs": 11}); r.run(30)
check("board derives +3 for home", r.st.kk.get(0) == (1, 3), r.st.kk)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=1, c="4:30", **{"as": 10, "hs": 11}); r.run(30)
check("+N sticky when score unchanged", r.st.kk.get(0) == (1, 3), r.st.kk)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=1, c="4:20", **{"as": 12, "hs": 11}); r.run(30)
check("board derives +2 for away", r.st.kk.get(0) == (0, 2), r.st.kk)


# ---- 記憶體不足自救 ----
r.app.go("score", clock.now)
for lv in (1, 2, 3):
    r.app.recover(lv)
check("recover level3 goes home", r.app.cur_id == "home", r.app.cur_id)

# ---- 倒數必須一秒一秒走, 資料忽快忽慢也不能跳好幾秒 ----
r.app.go("score", clock.now); a = r.app; a.pm.wake(clock.now)
r.msg(k="games", l=[["NBA", "LAL", "GSW"]]); r.run(30)
a.cur.sel = 0; a.cur._tell(); r.run(30)
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=3, c="x", cs=3000, cn=2920, cd=4000, cr=0, dl=15, **{"as": 1, "hs": 1}); r.run(10)
seq = []
for _ in range(40):
    r.run(100); seq.append(a.cur.clk(r.st.scores[0], a.cur._now))
r.msg(k="score", i=0, lg="NBA", st="L", a="LAL", h="GSW", q=3, c="x", cs=2920, cn=2920, cd=4000, cr=0, dl=15, **{"as": 1, "hs": 1}); r.run(10)
for _ in range(40):
    r.run(100); seq.append(a.cur.clk(r.st.scores[0], a.cur._now))
steps = [seq[i] - seq[i + 1] for i in range(len(seq) - 1)]
check("no jump > 0.3s per 0.1s frame (fast data, then stale data)", max(steps) <= 3, max(steps))
check("never goes up while counting", min(steps) >= 0, min(steps))

# ---- 足球: 畫面數與切換 ----
r.msg(k="games", l=[["SOC", "MCI", "LIV"]]); r.run(30)
a.cur.sel = 0; a.cur._tell(); r.run(30)
r.msg(k="score", i=0, lg="SOC", st="L", a="MCI", h="LIV", c="67'", q=2, ln="英超", **{"as": 1, "hs": 0}); r.run(30)
check("soccer live has 4 views", a.cur._nv() == 4, a.cur._nv())
r.msg(k="score", i=0, lg="SOC", st="P", a="MCI", h="LIV", tm="10/17 02:00"); r.run(30)
check("soccer pre-game has 1 view", a.cur._nv() == 1, a.cur._nv())
r.msg(k="score", i=0, lg="SOC", st="L", a="MCI", h="LIV", c="HT", q=1, **{"as": 0, "hs": 0}); r.run(30)
a.cur.view = 3; r.run(30)
check("soccer title shows view letter", a.cur.get_title().startswith("SOC1/1"), a.cur.get_title())

# ---- 快捷鍵 (手機設定) ----
import state as _S
check("clean_hk drops bad entries", _S.clean_hk([["*", 0, 0, "home", "", 0], ["*", 9, 0, "home"], ["*", 2, 2, "home"], ["*", 1, 0, "nope"], ["*", 0, 0, "goto"], "x"]) == [["*", 0, 0, "home", "", 0]])
import p_settings as _PS
for nm, key, opts, fmt in _PS.SettingsPage._ALL:
    if key in _S.CFG_OPTS:
        check("CFG_OPTS matches settings page: " + key, set(opts) == set(_S.CFG_OPTS[key]), (opts, _S.CFG_OPTS[key]))
r = Rig(); a = r.app
r.app.go("sw", clock.now); r.run(100)
r.msg(k="cfg", d={"seek": 15, "vstep": 7, "evil": 1, "h24": 1}); r.run(50)
check("cfg accepts valid", r.st.cfg.get("seek") == 15 and r.st.cfg.get("h24") == 1)
check("cfg rejects invalid value / unknown key", r.st.cfg.get("vstep") != 7 and "evil" not in r.st.cfg.d)
check("cfg echoed to phone", any(isinstance(x, dict) and x.get("c") == "cfg" for x in r.link.sent))
# 全域雙擊 (不優先): 碼表頁沒用到左鍵雙擊 -> 生效
r.msg(k="hk", l=[["*", 0, 0, "goto", "dev", 0], ["music", 4, 0, "home", "", 0], ["*", 3, 1, "back", "", 1], ["*", 1, 2, "off", "", 1]]); r.run(50)
check("hk stored", len(r.st.cfg.get("hk")) == 4)
r.app.go("sw", clock.now); r.run(100)
r.double(K_LEFT); r.run(100)
check("global double -> goto dev", r.app.cur_id == "dev", r.app.cur_id)
r.app.go("music", clock.now); r.run(100)
r.double(K_RIGHT); r.run(100)
check("page-scope binding fires before page (music right double)", r.app.cur_id == "home", r.app.cur_id)
check("triple binding raises maxc", r.keys.maxc[K_DOWN] == 3, r.keys.maxc)
r.app.go("dev", clock.now); r.run(100); r.app.go("sw", clock.now); r.run(100)
r.triple(K_DOWN); r.run(100)
check("triple down -> back", r.app.cur_id == "dev", r.app.cur_id)
r.app.go("music", clock.now); r.run(100)
n0 = len(r.link.sent)
r.hold(K_UP, 900); r.run(100)
check("forced global long up -> screen off requested", r.app.pm.off_req)
r.press(K_CENTER, 80); r.run(200)
check("keypress wakes from forced off", not r.app.pm.off_req)
r.msg(k="hk", l=[["*", 2, 0, "run", "不存在", 0]]); r.run(50)
r.app.go("sw", clock.now); r.run(100)
r.double(K_CENTER); r.run(100)
check("run unknown shortcut does not crash", True)

# ---- 手機端 (Kotlin) 與韌體的快捷鍵 / 設定表要一致 ----
import re, os
kt = open(os.path.join(os.path.dirname(__file__), "..", "..", "android/app/src/main/java/tw/deskctl/HotkeyStore.kt"), encoding="utf-8").read()
acts_kt = re.findall(r'ActDef\("([^"]+)"', kt)
check("phone ACTS == firmware HK_ACTS", sorted(acts_kt) == sorted(_S.HK_ACTS), (acts_kt, _S.HK_ACTS))
check("phone MAX == firmware HK_MAX", ("const val MAX = %d" % _S.HK_MAX) in kt)
for key, vals in re.findall(r'Opt\("(\w+)", "[^"]*", intArrayOf\(([^)]*)\)', kt):
    v = tuple(int(x) for x in vals.split(","))
    check("phone opt values match CFG_OPTS: " + key, set(v) == set(_S.CFG_OPTS[key]), (v, _S.CFG_OPTS[key]))
check("every CFG_OPTS key is editable on phone", set(re.findall(r'Opt\("(\w+)", "', kt)) == set(_S.CFG_OPTS))
# USED 表要等於實測 (各頁攔截的 雙擊/三連擊/長按)
import registry
rr = Rig(); aa = rr.app
rr.msg(k="games", l=[["MLB", "A", "B"]]); rr.msg(k="acts", l=["x", "y"]); rr.run(50)
used_kt = dict((m[0], set(m[1].split())) for m in re.findall(r'"(\w+)" to "([0-9 ]+)"', kt))
for pid in used_kt:
    aa.go(pid, clock.now); rr.run(100)
    got = set()
    for k in range(5):
        for gi, ev in enumerate((EV_DOUBLE, EV_TRIPLE, EV_LONG)):
            if k == 2 and gi:
                continue
            if aa.cur.on_key(k, ev, clock.now):
                got.add("%d%d" % (k, gi))
    check("USED table matches page: " + pid, got == used_kt[pid], sorted(got ^ used_kt[pid]))

# ---- 時鐘冒號: 每秒閃一輪 (半秒亮、半秒暗) ----
rb = Rig(); rb.msg(k="time", t=1791449340, z=480); rb.run(100)
t0 = rb.st._t0
check("blink: on in first half of second", rb.st.blink(t0 + 100) and rb.st.blink(t0 + 1100))
check("blink: off in second half of second", (not rb.st.blink(t0 + 600)) and (not rb.st.blink(t0 + 1600)))
for pid in ("home", "desk"):
    rb.app.go(pid, clock.now); rb.run(300)
    n = [0]
    orig = rb.app.lcd.show
    def counted(buf, _o=orig, _n=n):
        _n[0] += 1
        return _o(buf)
    rb.app.lcd.show = counted
    rb.run(4000)
    rb.app.lcd.show = orig
    check("%s redraws about twice per second (colon blink)" % pid, 6 <= n[0] <= 12, n[0])
print("\n%d failures" % len(fails)); sys.exit(1 if fails else 0)
