"""字型讀取 + 畫布 (framebuf RGB565)。"""
import framebuf
import struct


def rgb(r, g, b):
    """RGB888 -> RGB565, 高低位元組先對調 (SPI 送出時高位在前); 結果永不為 0。"""
    c = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)
    c = ((c & 0xFF) << 8) | (c >> 8)
    return c or 1


class Font:
    """字型讀取。所有字元槽位在啟動時一次配置好, 之後不再產生新物件 (避免記憶體碎片)。"""
    SLOTS = 120

    def __init__(self, path):
        self.f = open(path, "rb")
        hdr = self.f.read(6)
        if hdr[:3] != b"F12":
            raise ValueError("bad font")
        self.n = struct.unpack("<H", hdr[4:6])[0]
        self.codes_off = 6
        self.adv_off = 6 + 2 * self.n
        self.bmp_off = self.adv_off + self.n
        n = self.SLOTS
        self.gbuf = bytearray(24 * n)
        mv = memoryview(self.gbuf)
        self.mvs = [mv[i * 24:(i + 1) * 24] for i in range(n)]
        self.fbs = [framebuf.FrameBuffer(self.mvs[i], 16, 12, framebuf.MONO_HLSB) for i in range(n)]
        self.advs = bytearray(n)
        self.owner = [0] * n
        self.slot = {}
        self.nxt = 0
        self._b2 = bytearray(2)
        self._b1 = bytearray(1)

    def _find(self, cp):
        lo, hi = 0, self.n - 1
        f, b2 = self.f, self._b2
        while lo <= hi:
            mid = (lo + hi) >> 1
            f.seek(self.codes_off + 2 * mid)
            f.readinto(b2)
            v = b2[0] | (b2[1] << 8)
            if v == cp:
                return mid
            if v < cp:
                lo = mid + 1
            else:
                hi = mid - 1
        return -1

    def get(self, cp):
        """回傳字元所在的槽位編號; 寬度 = advs[槽], 圖 = fbs[槽], 原始資料 = gbuf[槽*24:]"""
        s = self.slot.get(cp)
        if s is not None:
            return s
        idx = self._find(cp)
        if idx < 0 and cp != 0x3F:
            return self.get(0x3F)
        s = self.nxt
        self.nxt = (s + 1) % self.SLOTS
        old = self.owner[s]
        if old:
            self.slot.pop(old, None)
        f = self.f
        if idx < 0:
            adv = 6
            self.mvs[s][:] = bytes(24)
        else:
            f.seek(self.adv_off + idx)
            f.readinto(self._b1)
            adv = self._b1[0]
            f.seek(self.bmp_off + 24 * idx)
            f.readinto(self.mvs[s])
        self.advs[s] = adv
        self.owner[s] = cp
        self.slot[cp] = s
        return s

    def width(self, s):
        w = 0
        advs = self.advs
        for ch in s:
            w += advs[self.get(ord(ch))]
        return w


class Canvas:
    def __init__(self, w, h, font, buf=None):
        self.w, self.h = w, h
        self.buf = buf if buf is not None else bytearray(w * h * 2)
        self.fb = framebuf.FrameBuffer(self.buf, w, h, framebuf.RGB565)
        self.font = font
        self._pal = {}

    # --- 基本繪圖 ---
    def fill(self, c):
        self.fb.fill(c)

    def rect(self, x, y, w, h, c):
        self.fb.rect(x, y, w, h, c)

    def frect(self, x, y, w, h, c):
        self.fb.fill_rect(x, y, w, h, c)

    def hline(self, x, y, w, c):
        self.fb.hline(x, y, w, c)

    def vline(self, x, y, h, c):
        self.fb.vline(x, y, h, c)

    def line(self, x0, y0, x1, y1, c):
        self.fb.line(x0, y0, x1, y1, c)

    def pixel(self, x, y, c):
        self.fb.pixel(x, y, c)

    def blit(self, other, x, y):
        self.fb.blit(other.fb, x, y)

    # --- 文字 ---
    def palette(self, c):
        p = self._pal.get(c)
        if p is None:
            if len(self._pal) > 40:
                self._pal.clear()
            pb = bytearray(4)
            p = framebuf.FrameBuffer(pb, 2, 1, framebuf.RGB565)
            p.pixel(0, 0, 0)
            p.pixel(1, 0, c)
            self._pal[c] = p
        return p

    def text(self, s, x, y, c):
        fb, font = self.fb, self.font
        pal = self.palette(c)
        w = self.w
        fbs, advs, get = font.fbs, font.advs, font.get
        for ch in s:
            sl = get(ord(ch))
            if -12 < x < w:
                fb.blit(fbs[sl], x, y, 0, pal)
            x += advs[sl]
        return x

    def text_w(self, s):
        return self.font.width(s)

    def text_r(self, s, xr, y, c):
        """靠右對齊, xr = 右邊界"""
        self.text(s, xr - self.font.width(s), y, c)

    def text_c(self, s, xc, y, c):
        self.text(s, xc - (self.font.width(s) >> 1), y, c)

    def text_big(self, s, x, y, c, k, fixed=0):
        """放大 k 倍的文字 (給時鐘用)。fixed>0 時每字固定推進寬度。"""
        font = self.font
        fr = self.fb.fill_rect
        g = font.gbuf
        for ch in s:
            sl = font.get(ord(ch))
            base = sl * 24
            for r in range(12):
                v = (g[base + 2 * r] << 8) | g[base + 2 * r + 1]
                if not v:
                    continue
                b = 0
                while b < 12:
                    if v & (0x8000 >> b):
                        e = b
                        while e < 12 and v & (0x8000 >> e):
                            e += 1
                        fr(x + b * k, y + r * k, (e - b) * k, k, c)
                        b = e
                    else:
                        b += 1
            x += (fixed if fixed else font.advs[sl]) * k
        return x

    # --- 圖示 (Icon 物件) ---
    def icon(self, ic, x, y, c):
        self.fb.blit(ic.fb, x, y, 0, self.palette(c))


class Icon:
    """用字串畫的單色圖示, '#' 為亮點。"""

    def __init__(self, rows):
        self.h = len(rows)
        self.w = max(len(r) for r in rows)
        stride = (self.w + 7) // 8
        self.buf = bytearray(stride * self.h)
        for y, r in enumerate(rows):
            for x, ch in enumerate(r):
                if ch == "#":
                    self.buf[y * stride + (x >> 3)] |= 0x80 >> (x & 7)
        self.fb = framebuf.FrameBuffer(self.buf, stride * 8, self.h, framebuf.MONO_HLSB)
