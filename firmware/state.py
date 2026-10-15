"""全域狀態: 設定、手機資訊、時間、碼表。"""
import gc
import os
import time
try:
    import ujson as json
except ImportError:
    import json
from config import *

DEFAULTS = {
    "orient": 0, "panel": 0, "bgr": 1, "inv": 1, "calib": 0,
    "h24": 0, "secs": 1, "tsecs": 0, "idle": 0, "bright": 80, "pwr": 1, "dimt": 60, "lowt": 600, "lsleep": 0, "seek": 5, "vstep": 1,
    "ring": ["home", "sw", "music"], "hi": 0, "chips": 100, "menu": None, "rnd": [0, 999, 5, 1], "msgw": 1, "msgt": 1,
}


class Settings:
    def __init__(self):
        self.d = dict(DEFAULTS)
        for fn in (SETTINGS_FILE, SETTINGS_FILE + ".tmp"):
            try:
                with open(fn) as f:
                    self.d.update(json.load(f))
                break
            except Exception:
                pass
        self._dirty = False

    def get(self, k):
        return self.d.get(k, DEFAULTS.get(k))

    def set(self, k, v, save=True):
        self.d[k] = v
        self._dirty = True
        if save:
            self.save()

    def save(self):
        if not self._dirty:
            return
        try:                                   # 先寫暫存檔再改名: 寫到一半斷電也不會弄壞原本的設定
            tmp = SETTINGS_FILE + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.d, f)
            try:
                os.rename(tmp, SETTINGS_FILE)
            except OSError:                    # 某些檔案系統不能覆蓋既有檔案: 先刪再改名
                os.remove(SETTINGS_FILE)
                os.rename(tmp, SETTINGS_FILE)
            self._dirty = False
        except Exception:
            pass

    def reset(self):
        self.d = dict(DEFAULTS)
        self._dirty = True
        self.save()


_DAYS = (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)


def civil(unix_secs):
    """Unix 秒 -> (年, 月, 日, 時, 分, 秒, 星期0=週一)"""
    days, rem = divmod(int(unix_secs), 86400)
    hh, rem = divmod(rem, 3600)
    mm, ss = divmod(rem, 60)
    wd = (days + 3) % 7               # 1970-01-01 是週四
    z = days + 719468                 # Howard Hinnant 的 civil_from_days
    era = z // 146097
    doe = z - era * 146097
    yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
    y = yoe + era * 400
    doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
    mp = (5 * doy + 2) // 153
    d = doy - (153 * mp + 2) // 5 + 1
    m = mp + 3 if mp < 10 else mp - 9
    if m <= 2:
        y += 1
    return y, m, d, hh, mm, ss, wd


WEEK = ("週一", "週二", "週三", "週四", "週五", "週六", "週日")

# 音樂狀態
PL_NONE, PL_PLAY, PL_PAUSE = 0, 1, 2
RT_NONE, RT_SPK, RT_BT, RT_WIRED = 0, 1, 2, 3


LITE = ("lg", "st", "a", "h", "as", "hs", "in", "hf", "tx", "mid", "tm", "q", "c", "brk", "tn")


def lite(m):
    return {k: m[k] for k in LITE if k in m}


class State:
    def __init__(self):
        self.cfg = Settings()
        # 時間
        self.t_synced = False
        self._t0 = 0
        self._epoch0 = 0
        # 手機
        self.batt = -1
        self.charging = False
        self.has_track = False
        self.title = ""
        self.artist = ""
        self.album = ""
        self.app = ""
        self.dur = 0
        self.pl = PL_NONE
        self._pos = 0
        self._pos_t = 0
        self.speed = 1.0
        self.vol = 0
        self.vol_max = 15
        self.muted = False
        self.route = RT_NONE
        self.route_name = ""
        self.shuffle = 0
        self.repeat = 0          # 0 不循環 1 清單循環 2 單曲循環
        # 捷徑 (手機端設定, 名單由手機傳來)
        self.acts = []
        self.pad = None          # 手機控制器: (模式名, 第幾組, 共幾組, [上 右 下 左 中央 的功能名], 有設兩下手勢的方向位元)
        self.pad_v = 0
        self.kk = {}             # 籃球: 板子自己比對前後比分得到的「最近得分」 {欄位: (隊0客1主, 分數)}, 手機沒給時備用
        self.layout_changed = False
        self.sys = {}            # 手機系統狀態 (電量/網路/記憶體…)
        self.sys_t = 0
        self.msgs = []           # 手機通知: [編號, App, 標題, 內容, 收到時間ms, 未讀, 已標記]
        self.games = []          # 比分板: 手機設定追蹤的比賽 [(聯盟, 客隊, 主隊), ...] (順序 = 欄位編號)
        self.sc_sel = -1         # 控制器正在看的比賽欄位 (-1 = 沒在比分頁)
        self.clk_t = {}          # 欄位編號 -> 收到計時資料的時間 (籃球計時器往下推算用)
        self.buf_t = {}          # 欄位編號 -> 開始緩衝的時間
        self.score_t = {}        # 欄位編號 -> 最近一次更新的時間 (ms)
        self.scores = {}         # 欄位編號 -> 手機最新傳來的比分資料 (dict)
        self.msg_ev = None       # 新訊息 (App, 標題) , App 會轉成提示並喚醒螢幕
        self.act_ev = None       # (狀態, 名稱, 訊息) 最近一次手機回報, App 會轉成提示
        # 碼表
        self.sw_run = False
        self.sw_t = 0
        self.sw_acc = 0
        self.sw_laps = []

    def set_sel(self, i):
        """比分頁: 設定正在看的比賽; 其他比賽的資料縮成精簡版"""
        if i != self.sc_sel:
            self.kk = {}
        self.sc_sel = i
        for j in list(self.scores):
            if j != i:
                self.scores[j] = lite(self.scores[j])
        gc.collect()

    def unread(self):
        n = 0
        for x in self.msgs:
            if x[5]:
                n += 1
        return n

    def set_layout(self, menu, ring):
        """手機設定的目錄順序 (menu) 與快捷切換 (ring)。home 固定第一個, settings 一定保留。"""
        import registry
        ids = [p[0] for p in registry.MAIN]
        mm = []
        for x in (menu or []):
            if x in ids and x not in mm:
                mm.append(x)
        if mm:
            if "home" in mm:
                mm.remove("home")
            mm.insert(0, "home")
            if "settings" not in mm:
                mm.append("settings")
            self.cfg.set("menu", mm, False)
        rr = []
        for x in (ring or []):
            if x in ids and x not in rr:
                rr.append(x)
        if rr:
            self.cfg.set("ring", rr, False)
        self.cfg.save()
        self.layout_changed = True

    # ---- 時間 ----
    def sync_time(self, unix, tz_min, now):
        self._epoch0 = int(unix) + int(tz_min) * 60
        self._t0 = now
        self.t_synced = True

    def local(self, now):
        secs = self._epoch0 + time.ticks_diff(now, self._t0) // 1000
        return civil(secs)

    # ---- 音樂位置 ----
    def set_pos(self, pos, now):
        self._pos = pos
        self._pos_t = now

    def pos(self, now):
        p = self._pos
        if self.pl == PL_PLAY:
            p += int(time.ticks_diff(now, self._pos_t) * self.speed)
        if self.dur and p > self.dur:
            p = self.dur
        return p if p > 0 else 0

    # ---- 碼表 ----
    def sw_elapsed(self, now):
        if self.sw_run:
            return self.sw_acc + time.ticks_diff(now, self.sw_t)
        return self.sw_acc

    def sw_toggle(self, now):
        if self.sw_run:
            self.sw_acc += time.ticks_diff(now, self.sw_t)
            self.sw_run = False
        else:
            self.sw_t = now
            self.sw_run = True

    def sw_lap(self, now):
        if self.sw_run and len(self.sw_laps) < 99:
            self.sw_laps.append(self.sw_elapsed(now))
            return True
        return False

    def sw_reset(self):
        self.sw_run = False
        self.sw_acc = 0
        self.sw_laps = []

    # ---- 手機訊息 ----
    def apply(self, m, now):
        k = m.get("k")
        if k == "time":
            self.sync_time(m.get("t", 0), m.get("z", 0), now)
        elif k == "batt":
            self.batt = m.get("p", -1)
            self.charging = bool(m.get("ch", 0))
        elif k == "track":
            self.title = m.get("t", "")
            self.artist = m.get("a", "")
            self.album = m.get("b", "")
            self.app = m.get("app", "")
            self.dur = m.get("d", 0)
            self.has_track = bool(self.title)
            if not self.has_track:
                self.pl = PL_NONE
        elif k == "play":
            self.pl = m.get("s", 0)
            self.speed = m.get("v", 100) / 100.0
            self.set_pos(m.get("p", 0), now)
        elif k == "vol":
            self.vol = m.get("v", 0)
            self.vol_max = max(1, m.get("m", 15))
            self.muted = bool(m.get("mu", 0))
        elif k == "route":
            r = m.get("r", "")
            self.route = {"spk": RT_SPK, "bt": RT_BT, "wired": RT_WIRED}.get(r, RT_NONE)
            self.route_name = m.get("n", "")
        elif k == "pad":
            lb = [str(x)[:12] for x in (m.get("l") or [])][:5]
            lb += [""] * (5 - len(lb))
            self.pad = (str(m.get("n", ""))[:12], int(m.get("i", 0)), int(m.get("t", 1)), lb, int(m.get("g", 0)))
            self.pad_v += 1
        elif k == "acts":
            self.acts = [str(x) for x in m.get("l", [])][:16]
        elif k == "sys":
            self.sys = m
            self.sys_t = now
        elif k == "layout":
            self.set_layout(m.get("m"), m.get("r"))
        elif k == "games":
            self.games = [(str(x[0])[:6], str(x[1])[:8], str(x[2])[:8]) for x in m.get("l", []) if len(x) >= 3][:8]
            for i in list(self.scores):
                if i >= len(self.games):
                    del self.scores[i]
        elif k == "score":
            i = m.get("i", -1)
            if 0 <= i < 8:
                old = self.scores.get(i)
                if i == self.sc_sel and old and m.get("lg") == "NBA" and "as" in old and "as" in m and "hs" in m:
                    da = m["as"] - old.get("as", 0)
                    dh = m["hs"] - old.get("hs", 0)
                    if da > 0 or dh > 0:
                        self.kk[i] = (0 if da > dh else 1, max(da, dh))
                if i != self.sc_sel:                            # 沒在看的比賽只留精簡欄位, 省記憶體
                    m = lite(m)
                self.scores[i] = m
                self.score_t[i] = now
                if "cs" in m:
                    self.clk_t[i] = now
                if m.get("bf"):
                    self.buf_t[i] = now
                if i == self.sc_sel and len(m) > 12:             # 完整資料剛解析完, 馬上整理掉解析產生的垃圾
                    gc.collect()
        elif k == "scok":                                       # 手機剛確認過這幾場的資料 (沒變動也算更新)
            for i in m.get("l", []):
                self.score_t[i] = now
        elif k == "msg":
            i = m.get("i", 0)
            old = [x for x in self.msgs if x[0] == i]
            mk = bool(old and old[0][6])                        # 更新同一則時保留標記
            self.msgs = [x for x in self.msgs if x[0] != i]
            self.msgs.insert(0, [i, str(m.get("a", ""))[:16], str(m.get("t", ""))[:40], str(m.get("x", ""))[:120], now, True, mk])
            while len(self.msgs) > 12:                          # 超過 12 則: 先丟最舊的「未標記」; 全是標記就丟最新的
                for j in range(len(self.msgs) - 1, -1, -1):
                    if not self.msgs[j][6]:
                        del self.msgs[j]
                        break
                else:
                    del self.msgs[0]
            self.msg_ev = (self.msgs[0][1], self.msgs[0][2]) if self.msgs and self.msgs[0][0] == i else None
        elif k == "msgrm":            # 手機上已讀/清除 -> 這裡也移除
            i = m.get("i", 0)
            self.msgs = [x for x in self.msgs if x[0] != i or x[6]]      # 標記過的不會被手機端的清除帶走
        elif k == "act":
            self.act_ev = (m.get("s", ""), m.get("n", ""), m.get("m", ""))
        elif k == "mode":
            self.shuffle = m.get("sh", 0)
            self.repeat = m.get("rp", 0)
