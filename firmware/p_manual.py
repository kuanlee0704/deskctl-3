"""說明書: 內容放在 manual.txt (一頁一段, 空行分頁, 第一行是標題, 「@」代表圖解頁)。
翻到那一頁才從檔案讀那幾行: 不用編譯一大串文字常數, 也不會長駐記憶體 (這頁的編譯曾在記憶體零碎時失敗)。"""
from app import Page
from keys import *
from ui import *
from array import array

FILE = "manual.txt"
NL = (b"\n", b"\r\n")


class ManualPage(Page):
    title = "說明書"
    reset_label = ""

    def __init__(self, app):
        Page.__init__(self, app)
        self.i = 0
        self.off = array("I")           # 每一頁在檔案裡的起點 (只存數字, 很小)
        self.name = ""
        self.lines = ()
        self._ld = -1
        try:
            with open(FILE, "rb") as f:
                pos = 0
                new = True
                while True:
                    ln = f.readline()
                    if not ln:
                        break
                    if ln in NL:
                        new = True
                    elif new:
                        self.off.append(pos)
                        new = False
                    pos += len(ln)
        except OSError:
            pass

    def get_title(self):
        return "說明 %d/%d" % (self.i + 1, len(self.off)) if self.off else "說明書"

    def _load(self):
        self._ld = self.i
        ls = []
        try:
            with open(FILE, "rb") as f:
                f.seek(self.off[self.i])
                while len(ls) < 8:
                    ln = f.readline()
                    if not ln or ln in NL:
                        break
                    ls.append(ln.decode().strip())
        except OSError:
            pass
        self.name = ls[0] if ls else ""
        self.lines = ls[1:]

    def trim(self):
        self.lines = ()
        self._ld = -1

    def leave(self):
        self.trim()

    def on_key(self, k, ev, now):
        self.dirty = True
        n = len(self.off)
        if n and k in (K_UP, K_DOWN) and ev in (EV_CLICK, EV_HOLD, EV_LONG):
            self.i = (self.i + (1 if k == K_DOWN else -1)) % n
            return True
        return ev in (EV_PRESS, EV_RELEASE)

    def draw(self, c, now):
        if not self.off:
            c.text("找不到 manual.txt", 6, 22, WARN)
            c.text("請把韌體資料夾裡的", 6, 38, DIM)
            c.text("manual.txt 一起上傳", 6, 52, DIM)
            return
        if self._ld != self.i:
            self._load()
        if self.lines and self.lines[0] == "@":
            self.diagram(c)
        else:
            y = 15
            for ln in self.lines:
                c.text(ln, 5, y, FG if ln[:1] not in "(" else DIM)
                y += 13

    def diagram(self, c):
        # 螢幕
        c.rect(6, 17, 62, 36, DIM)
        c.frect(9, 20, 56, 30, PANEL)
        c.text_c("螢幕", 37, 28, ACC)
        c.text_c("在左邊", 37, 58, DIM)
        # 五向鍵
        cx, cy = 118, 35
        c.rect(cx - 25, cy - 20, 50, 40, FAINT)
        c.text_c("上", cx, cy - 19, FG)
        c.text_c("中", cx, cy - 6, ACC)
        c.text_c("下", cx, cy + 7, FG)
        c.text("左", cx - 23, cy - 6, FG)
        c.text("右", cx + 12, cy - 6, FG)
        c.text_c("按鍵在右邊", cx, 58, DIM)
        c.line(72, 35, 90, 35, FAINT)
        c.line(86, 32, 90, 35, FAINT)
        c.line(86, 38, 90, 35, FAINT)
