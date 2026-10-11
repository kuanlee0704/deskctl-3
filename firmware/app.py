"""應用程式核心: 頁面管理、全域手勢、疊加層。"""
import gc
import sys
import time
import registry
from keys import *
from config import *
import ui
from ui import *
from musicctl import MusicCtl
from power import Power, L as PM_LOW, O as PM_OFF
from state import PL_PLAY


# 常駐的頁面; 其他頁面(遊戲/說明書/設定)離開時會卸載以釋放記憶體
CORE = ("home", "sw", "music")


class Page:
    id = ""
    title = ""
    fast = ()                # 這些方向鍵不等連按判定 (遊戲用)
    reset_label = "重設此頁"

    def __init__(self, app):
        self.app = app
        self.st = app.st
        self.dirty = True

    def enter(self, now):
        self.dirty = True

    def leave(self):
        pass

    def on_key(self, k, ev, now):
        return False

    def on_reset(self, now):
        pass

    def tick(self, now):
        d = self.dirty
        self.dirty = False
        return d

    def draw(self, c, now):
        pass

    def get_title(self):
        return self.title


class MenuPage(Page):
    """目錄: 上下選, 中央進入, 右鍵加入/移出快捷切換(★), 左鍵返回"""
    id = "menu"
    title = "目錄"
    reset_label = ""

    def __init__(self, app):
        Page.__init__(self, app)
        self.sel = 0
        self.items = registry.MAIN
        self.src = "home"

    def open(self, src, now):
        self.items = registry.main_items(self.st.cfg.get("menu"))
        self.src = src
        self.sel = 0
        for i, p in enumerate(self.items):
            if p[0] == src:
                self.sel = i
        self.dirty = True

    def on_key(self, k, ev, now):
        n = len(self.items)
        if k in (K_UP, K_DOWN) and ev in (EV_CLICK, EV_HOLD, EV_LONG):
            self.sel = (self.sel + (1 if k == K_DOWN else -1)) % n
        elif k == K_CENTER and ev == EV_CLICK:
            self.app.go(self.items[self.sel][0])
            return True
        elif k == K_LEFT and ev == EV_CLICK:
            self.app.go(self.src)
            return True
        elif k == K_RIGHT and ev == EV_CLICK:
            pid = self.items[self.sel][0]
            ring = list(self.app.st.cfg.get("ring"))
            if pid in ring:
                if len(ring) > 1:
                    ring.remove(pid)
                    self.app.toast("已移出快捷切換")
            else:
                ring.append(pid)
                self.app.toast("已加入快捷切換 ★")
            order = [p[0] for p in self.items]
            ring.sort(key=lambda x: order.index(x) if x in order else 99)
            self.app.st.cfg.set("ring", ring)
            self.app.send_layout()
        else:
            return True if ev in (EV_PRESS, EV_RELEASE, EV_DOUBLE, EV_TRIPLE) else False
        self.dirty = True
        return True

    def draw(self, c, now):
        n = len(self.items)
        rows = 4
        top = min(max(self.sel - 1, 0), max(0, n - rows))
        ring = self.app.st.cfg.get("ring")
        y = TOP_H + 1
        for i in range(top, min(n, top + rows)):
            pid, name = self.items[i][0], self.items[i][1]
            if i == self.sel:
                c.frect(2, y - 1, 156, 14, PANEL)
                c.text(">", 5, y, ACC)
            c.text(name, 16, y, FG if i == self.sel else DIM)
            if pid in ring:
                c.text("★", 140, y, WARN)
            if pid == self.src:
                c.text("目前", 100, y, FAINT)
            y += 14
        c.text("中:進入  右:★快捷  左:返回", 4, 68, FAINT)
        # 捲動指示
        if n > rows:
            h = 52 * rows // n
            yy = TOP_H + 1 + (52 - h) * top // max(1, n - rows)
            c.frect(158, yy, 2, h, FAINT)


class App:
    def __init__(self, canvas, lcd, keys, link, st, now, reboot=None):
        self.c = canvas
        self.lcd = lcd
        self.keys = keys
        self.link = link
        self.st = st
        self.insts = {}
        self.cur = None
        self.cur_id = ""
        self.prev_id = ""
        self.menu_src = "home"
        self._toast = None
        self._toast_until = 0
        self._last_draw = 0
        self._sig = None
        self.idle_t = now
        self._swallow = -1
        self.rebooting = False
        self._reboot = reboot
        # 大塊緩衝區一開始就配置好, 之後不再動態配置 (避免記憶體碎片)
        f = canvas.font
        self.mq = (Marquee(f, 152, FG), Marquee(f, 152, DIM), Marquee(f, 84, DIM))
        self.mctl = MusicCtl(self)
        self.menu = MenuPage(self)
        self.insts["menu"] = self.menu
        for pid in CORE:
            self.page(pid)
        self._gc_t = now
        self._gc_n = 0
        self.pm = Power(self)
        self.t_boot = now
        self.mem_hist = []                 # 剩餘記憶體歷史 (每 5 秒一點, 給「系統總覽」畫趨勢)
        self.mem_min = gc.mem_free()
        self._mem_t = now
        self._hello_conn = -1
        self._stat_t = now
        gc.collect()
        self.go("home", now=now)

    # ---------- 頁面 ----------
    def page(self, pid):
        p = self.insts.get(pid)
        if p is None:
            info = registry.info(pid)
            if info is None:
                return None
            gc.collect()
            try:
                mod = __import__(info[2])
            except MemoryError:
                self._evict(pid)
                mod = __import__(info[2])
            p = getattr(mod, info[3])(self)
            p.id = pid
            if not p.title:
                p.title = info[1]
            self.insts[pid] = p
        return p

    def _evict(self, keep):
        """記憶體不足時: 丟掉不在使用中的頁面 (與其程式碼), 騰出空間給新頁面載入。碼表要保留 (計時中)"""
        for pid in list(self.insts):
            if pid in (keep, "home", "sw", "menu") or self.insts[pid] is self.cur:
                continue
            del self.insts[pid]
            info = registry.info(pid)
            if info and info[2] in sys.modules:
                del sys.modules[info[2]]
        gc.collect()

    def go(self, pid, now=None):
        if now is None:
            now = time.ticks_ms()
        p = self.page(pid)
        if p is None:
            return
        old_id = self.cur_id
        if self.cur is not None:
            self.cur.leave()
            if self.cur_id != "menu" and self.cur_id != pid:
                self.prev_id = self.cur_id
        self.cur = p
        self.cur_id = pid
        self.keys.set_fast(p.fast)
        p.enter(now)
        self._sig = None
        if old_id and old_id != pid:
            self.unload(old_id)

    def unload(self, pid):
        """卸載不常駐的頁面與其程式碼, 把記憶體還回去"""
        if pid in CORE or pid == "menu":
            return
        info = registry.info(pid)
        if info is None or pid not in self.insts:
            return
        del self.insts[pid]
        sys.modules.pop(info[2], None)
        gc.collect()

    def ring_step(self, d):
        ring = [r for r in self.st.cfg.get("ring") if registry.info(r)]
        if not ring:
            return
        if self.cur_id in ring:
            i = (ring.index(self.cur_id) + d) % len(ring)
        else:
            i = 0
        self.go(ring[i])

    # ---------- 回報給手機 ----------
    def send_layout(self):
        cfg = self.st.cfg
        items = registry.main_items(cfg.get("menu"))
        self.link.send({"c": "layout", "m": [p[0] for p in items], "r": list(cfg.get("ring"))})

    def send_stat(self, now):
        self.link.send({"c": "stat", "ver": VERSION, "up": time.ticks_diff(now, self.t_boot) // 1000,
                        "mf": gc.mem_free(), "ma": gc.mem_alloc(), "pg": self.cur_id,
                        "cn": self.link.n_conn, "dc": self.link.n_disc, "lu": self.link.last_up})

    def hello(self, now):
        """手機剛連上: 告訴它有哪些頁面、目前目錄順序與運作狀態 (手機端用來顯示與排序)"""
        self.link.send({"c": "pages", "l": [[p[0], p[1]] for p in registry.MAIN]})
        self.send_layout()
        self.send_stat(now)

    def act_toast(self, ev):
        s, n, m = ev
        if s == "run":
            t = "執行 " + n
        elif s == "done":
            t = "完成 " + n + ((" " + m) if m else "")
        elif s == "busy":
            t = "上一個還沒跑完"
        else:
            t = "失敗: " + (m or n)
        self.toast(ui.clip_text(self.c, t, 138), 2000)

    def toast(self, text, ms=1300):
        self._toast = text
        self._toast_until = time.ticks_add(time.ticks_ms(), ms)
        self._sig = None

    # ---------- 按鍵 ----------
    def handle(self, k, ev, now):
        self.idle_t = now
        if ev == EV_VLONG:
            if k == K_CENTER:
                self.do_reboot()
            elif self.cur.reset_label:
                self.cur.on_reset(now)
                self.toast(self.cur.reset_label + " ✓")
            return
        if k == K_CENTER:
            if ev == EV_LONG:
                if self.cur_id == "menu":
                    self.go(self.menu.src, now)
                else:
                    self.menu.open(self.cur_id, now)
                    self.go("menu", now)
                return
            if ev == EV_TRIPLE:
                # 全域「回首頁」: 不在首頁 -> 回首頁; 在首頁 -> 回到剛才離開的頁面
                if self.cur_id != "home":
                    self.go("home", now)
                elif self.prev_id and self.prev_id != "home":
                    self.go(self.prev_id, now)
                return
        if self.cur.on_key(k, ev, now):
            return
        if ev == EV_CLICK and k in (K_LEFT, K_RIGHT):
            self.ring_step(1 if k == K_RIGHT else -1)

    def do_reboot(self):
        self.rebooting = True
        self.c.fill(BG)
        self.c.text_c("重啟中…", 80, 34, FG)
        self.lcd.show(self.c.buf)
        self.st.cfg.save()
        time.sleep_ms(300)
        if self._reboot:
            self._reboot()

    # ---------- 主迴圈的一步 ----------
    def step(self, now):
        evs = self.keys.poll(now)
        for k, ev in evs:
            self.idle_t = now
            if self.pm.state >= PM_LOW:            # 低亮度/熄屏時, 第一下只負責喚醒
                if ev == EV_PRESS:
                    self.pm.wake(now)
                    self._swallow = k
                continue
            if self._swallow == k:
                if ev == EV_RELEASE or ev in (EV_CLICK, EV_DOUBLE, EV_TRIPLE):
                    self._swallow = -1
                continue
            self.handle(k, ev, now)
            if self.rebooting:
                return
        # 手機訊息
        for m in self.link.poll(now):
            was = self.st.pl
            self.st.apply(m, now)
            ev = self.st.act_ev
            if ev is not None:
                self.st.act_ev = None
                self.act_toast(ev)
            mev = self.st.msg_ev
            if mev is not None:
                self.st.msg_ev = None
                if self.cur_id != "msg":
                    self.toast(ui.clip_text(self.c, mev[0] + ": " + (mev[1] or "新訊息"), 138), 2500)
                if self.st.cfg.get("msgw"):
                    self.idle_t = now              # 新訊息 -> 喚醒螢幕
            if self.st.pl == PL_PLAY and was != PL_PLAY:
                self.idle_t = now                  # 手機開始播放 -> 喚醒
            self.cur.dirty = True
            if m.get("k") == "ping":
                self.link.send({"c": "pong"})
        if time.ticks_diff(now, self._mem_t) >= 5000:
            self._mem_t = now
            f = gc.mem_free()
            self.mem_hist.append(f)
            del self.mem_hist[:-30]
            if f < self.mem_min:
                self.mem_min = f
        self.link.pump()
        self.pm.update(now)
        on = self.link.online(now) and self.link.connected
        if on and self._hello_conn != self.link.n_conn:
            self._hello_conn = self.link.n_conn          # 每次(重新)連線都打一次招呼
            self.hello(now)
            self._stat_t = now
        elif on and time.ticks_diff(now, self._stat_t) > 10000:
            self._stat_t = now
            self.send_stat(now)
        self.render(now)
        if time.ticks_diff(now, self._gc_t) > 3000:       # 定期整理記憶體, 避免碎片累積
            self._gc_t = now
            gc.collect()
            self._gc_n += 1
            if self._gc_n % 10 == 0:
                print("mem free", gc.mem_free(), "alloc", gc.mem_alloc())

    @property
    def sleeping(self):
        return self.pm.state == PM_OFF

    def render(self, now):
        if self.pm.state == PM_OFF:
            return
        if time.ticks_diff(now, self._last_draw) < FPS_MS:
            return
        st, link = self.st, self.link
        tsecs = bool(st.cfg.get("tsecs"))
        minute = st.local(now)[5 if tsecs else 4] if st.t_synced else -1
        sig = (link.online(now), link.connected, st.sw_run, minute, st.t_synced,
               st.batt, self._toast is not None, st.pl, st.has_track)
        need = self.cur.tick(now)
        if sig != self._sig:
            self._sig = sig
            need = True
        holding = False
        for k in range(5):
            if self.keys.held_ms(k, now) >= SHOW_VLONG_MS:
                holding = True
        if self._toast is not None and time.ticks_diff(now, self._toast_until) >= 0:
            self._toast = None
            need = True
        if not (need or holding or self._toast is not None):
            return
        self._last_draw = now
        self.draw(now, holding)
        self.lcd.show(self.c.buf)

    def draw(self, now, holding=False):
        c = self.c
        c.fill(BG)
        try:
            self.cur.draw(c, now)
        finally:
            ui.draw_topbar(c, self.cur.get_title(), self.st, self.link, now,
                           self.st.cfg.get("h24"), bool(self.st.cfg.get("tsecs")))
        if holding:
            self.draw_hold(c, now)
        elif self._toast is not None:
            w = c.text_w(self._toast) + 12
            x = (160 - w) // 2
            c.frect(x, 62, w, 16, PANEL)
            c.rect(x, 62, w, 16, ACC)
            c.text(self._toast, x + 6, 64, FG)

    def draw_hold(self, c, now):
        for k in range(5):
            h = self.keys.held_ms(k, now)
            if h >= SHOW_VLONG_MS:
                if k == K_CENTER:
                    label = "繼續按住:重啟裝置"
                    col = BAD
                elif self.cur.reset_label:
                    label = "繼續按住:" + self.cur.reset_label
                    col = WARN
                else:
                    return
                frac = min(1.0, (h - SHOW_VLONG_MS) / (VLONG_MS - SHOW_VLONG_MS))
                c.frect(4, 56, 152, 22, PANEL)
                c.rect(4, 56, 152, 22, col)
                c.text_c(label, 80, 58, FG)
                c.rect(10, 72, 140, 4, FAINT)
                c.frect(10, 72, int(140 * frac), 4, col)
                return
