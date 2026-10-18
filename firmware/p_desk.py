"""桌面: 桌邊擺放用的畫面。大時鐘 + 日期 + 手機電量/藍牙/訊息狀態 + 正在播放的音樂 (歌名、歌手、進度)。
整個螢幕自己畫 (不顯示上方狀態列); 按鍵與首頁相同: 中央播放/暫停, 上下音量, 長按左右快轉..."""
from app import Page
from ui import *
from keys import *
from state import WEEK, PL_PLAY, RT_SPK, RT_BT, RT_WIRED


class DeskPage(Page):
    title = "桌面"
    reset_label = "重新同步"
    bare = True                         # 不畫上方狀態列

    def __init__(self, app):
        Page.__init__(self, app)
        self._sec = -1
        self._pl = -1
        self._dw = 0                    # 數字最寬的字寬 (進度條右端用最寬數字估算, 不會隨數字跳動)

    def on_reset(self, now):
        self.app.link.send({"c": "sync"})

    def on_key(self, k, ev, now):
        self.dirty = True
        return self.app.mctl.handle(k, ev, now, True)

    def tick(self, now):
        st = self.st
        sec = (now // 1000) if not st.t_synced else st.local(now)[5]
        ph = sec * 2 + (0 if st.blink(now) else 1)          # 冒號每秒閃一輪, 半秒換一次
        d = self.dirty or ph != self._sec
        mq = self.app.mq
        if self.app.mctl.vol_active(now) or mq[0].animating or mq[1].animating:
            d = True
        self._sec = ph
        self.dirty = False
        return d

    def draw(self, c, now):
        st = self.st
        cfg = st.cfg
        link = self.app.link
        online = link.online(now)
        # ---- 第一列: 日期 (左) 與狀態圖示 (右) ----
        if st.t_synced:
            y, m, d, hh, mm, ss, wd = st.local(now)
            c.text("%d/%02d %s" % (m, d, WEEK[wd]), 4, 0, FG)
        else:
            y = m = d = hh = mm = ss = wd = 0
            c.text("等待手機校時", 4, 0, DIM)
        x = 158
        if st.batt >= 0:
            col = GOOD if st.batt > 30 else (WARN if st.batt > 15 else BAD)
            t = "%d%%" % st.batt
            x -= c.text_w(t)
            c.text(t, x, 0, col)
            x -= 12
            c.icon(IC["bat"], x, 3, col)
            c.frect(x + 1, 4, max(1, 5 * st.batt // 100), 2, col)
            if st.charging:
                x -= 7
                c.icon(IC["bolt"], x, 2, WARN)
            x -= 4
        bcol = GOOD if online else (WARN if link.connected else FAINT)
        x -= 7
        c.icon(IC["bt"], x, 1, bcol)
        x -= 4
        if st.msgs and cfg.get("msgt") and st.unread():
            x -= 7
            c.icon(IC["mail"], x, 3, PINK)
            x -= 4
        if st.sw_run:
            x -= 9
            c.icon(IC["stopw"], x, 1, WARN)
        # ---- 大時鐘 ----
        if st.t_synced:
            if not cfg.get("h24"):
                ap = "上午" if hh < 12 else "下午"
                hh = hh % 12 or 12
            else:
                ap = ""
            hm = "%02d:%02d" % (hh, mm)
            c.text_big(hm[:2], 4, 10, FG, 3, 7)
            c.text_big(":", 46, 10, FG if st.blink(now) else FAINT, 3, 5)
            c.text_big(hm[3:], 61, 10, FG, 3, 7)
            if ap:
                c.text(ap, 108, 15, DIM)
            if cfg.get("secs"):
                c.text("%02d" % ss, 108, 29, ACC)
        else:
            c.text_big("--:--", 4, 10, FAINT, 3, 7)
        # ---- 音樂 ----
        mq = self.app.mq
        if st.has_track and online:
            playing = st.pl == PL_PLAY
            mq[0].draw(c, 4, 43, st.title, now, color=FG if playing else DIM)
            mq[1].draw(c, 4, 55, st.artist or "", now, color=ACC if playing else FAINT)
            pos = st.pos(now)
            ts = "%s/%s" % (fmt_ms(pos), fmt_ms(st.dur) if st.dur else "--:--")
            # 進度條右端 = 時間文字左邊 - 間隔; 文字寬度用「最寬數字」估算, 秒數變動時進度條不會抖動也不會碰到字
            if not self._dw:
                self._dw = max(c.text_w(ch) for ch in "0123456789")
            tw = 0
            for ch in ts:
                tw += self._dw if "0" <= ch <= "9" else c.text_w(ch)
            x0, yy = 18, 72
            x1 = 157 - tw - 4
            c.icon(IC["pause"] if playing else IC["play"], 4, 68, ACC if playing else DIM)
            if x1 - x0 >= 12:
                c.frect(x0, yy, x1 - x0, 3, FAINT)
                if st.dur:
                    c.frect(x0, yy, (x1 - x0) * min(pos, st.dur) // st.dur, 3, ACC)
            c.text_r(ts, 157, 67, DIM)
            # ---- 右上角: 播放裝置 (手機喇叭 / 耳機) 與音量 ----
            if st.route == RT_BT or st.route == RT_WIRED:
                ic = IC["head"]
                c.icon(ic, 157 - ic.w, 14, GOOD if st.route == RT_BT else ACC)
            elif st.route == RT_SPK:
                ic = IC["phone"]
                c.icon(ic, 157 - ic.w, 13, DIM)
            v = 0 if st.muted else st.vol
            vt = "%d" % (100 * v // max(1, st.vol_max))
            vc = BAD if st.muted else FG
            c.text_r(vt, 157, 29, vc)
            c.icon(IC["mute"] if st.muted else IC["spk"], 157 - c.text_w(vt) - 11, 31, vc)
        else:
            mq[0].text = None
            mq[1].text = None
            if online:
                c.text("沒有播放中的音樂", 4, 52, DIM)
                c.text("手機開始播放就會顯示", 4, 66, FAINT)
            elif link.connected:
                c.text("App 未回應", 4, 52, WARN)
            else:
                c.text("等待手機 App 連線", 4, 52, DIM)
        if self.app.mctl.vol_until:
            self.app.mctl.draw_vol(c, now)
