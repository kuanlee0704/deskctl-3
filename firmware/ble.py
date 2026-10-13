"""BLE GATT 伺服器 (Nordic UART 服務, 訊息為一行一筆 JSON)。
手機 App 當 Central 連上來: 手機寫入 RX 特徵 -> 狀態; 本機由 TX 特徵 notify -> 指令。"""
import time
try:
    import ujson as json
except ImportError:
    import json
from config import *

_CONNECT = 1
_DISCONNECT = 2
_WRITE = 3
_MTU = 21

_NUS = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   # 手機 -> 本機 (write)
_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   # 本機 -> 手機 (notify)


def _adv_field(t, data):
    return bytes((len(data) + 1, t)) + data


class Link:
    def __init__(self, name=BLE_NAME):
        import bluetooth
        self._bt = bluetooth
        self.ble = bluetooth.BLE()
        # 先直接開; 失敗才 關->等->開 重試 (軟重啟後控制器偶爾卡住)
        err = None
        for i in range(3):
            try:
                if i:
                    self.ble.active(False)
                    time.sleep_ms(200)
                self.ble.active(True)
                err = None
                break
            except OSError as e:
                err = e
                time.sleep_ms(300)
        if err:
            raise err
        try:
            self.ble.config(mtu=247)
        except Exception:
            pass
        try:
            self.ble.config(gap_name=name)
        except Exception:
            pass
        self.ble.irq(self._irq)
        nus = bluetooth.UUID(_NUS)
        tx = (bluetooth.UUID(_TX), bluetooth.FLAG_NOTIFY)
        rx = (bluetooth.UUID(_RX), bluetooth.FLAG_WRITE | bluetooth.FLAG_WRITE_NO_RESPONSE)
        ((self.h_tx, self.h_rx),) = self.ble.gatts_register_services(((nus, (tx, rx)),))
        try:
            self.ble.gatts_set_buffer(self.h_rx, 1024, True)
        except Exception:
            pass
        nb = name.encode()
        self._adv = _adv_field(0x01, b"\x06") + _adv_field(0x09, nb)
        self._resp = _adv_field(0x07, bytes(bluetooth.UUID(_NUS)))
        self.conn = None
        self.mtu = 23
        self._rxbuf = bytearray()
        self._txq = []
        self.last_rx = 0
        self.ever_rx = False
        self.seen = False
        self.n_conn = 0
        self.n_disc = 0
        self.t_conn = 0
        self.last_up = 0
        self._advertise()

    # ---- 狀態 ----
    @property
    def connected(self):
        return self.conn is not None

    def online(self, now):
        if not self.seen:
            return False
        age = time.ticks_diff(now, self.last_rx)
        if self.conn is not None and self.ever_rx:
            return age < ONLINE_TIMEOUT_MS
        # 剛斷線/剛重連、還沒收到新訊息: 寬限期內仍顯示在線, 避免畫面閃一下
        return age < ONLINE_GRACE_MS

    # ---- 內部 ----
    def _advertise(self):
        try:
            self.ble.gap_advertise(100000, adv_data=self._adv, resp_data=self._resp)
        except Exception:
            pass

    def _irq(self, ev, data):
        if ev == _CONNECT:
            self.n_conn += 1
            self.t_conn = time.ticks_ms()
            print("BLE connect #%d" % self.n_conn)
            self.conn = data[0]
            self.mtu = 23
            self.ever_rx = False
            self._txq = []
            self._rxbuf = bytearray()
        elif ev == _DISCONNECT:
            self.n_disc += 1
            self.last_up = time.ticks_diff(time.ticks_ms(), self.t_conn) // 1000
            print("BLE disconnect #%d after %ds" % (self.n_disc, self.last_up))
            self.conn = None
            self.ever_rx = False
            self._txq = []
            self._advertise()
        elif ev == _WRITE:
            if data[1] == self.h_rx:
                chunk = self.ble.gatts_read(self.h_rx)
                if len(self._rxbuf) + len(chunk) < 4096:
                    self._rxbuf += chunk
                else:
                    self._rxbuf = bytearray()
        elif ev == _MTU:
            self.mtu = data[1]

    # ---- 收 ----
    def poll(self, now):
        """回傳手機傳來的訊息 (dict list)"""
        buf = self._rxbuf
        if buf.find(b"\n") < 0:
            return ()
        out = []
        mv = memoryview(buf)
        st = 0
        while True:                         # 就地切出每一行 (MicroPython 的 bytearray 不能 del 切片, 所以用位移)
            i = buf.find(b"\n", st)
            if i < 0:
                break
            p = bytes(mv[st:i])
            st = i + 1
            if not p:
                continue
            try:
                m = json.loads(p)
            except Exception:
                continue
            self.last_rx = now
            self.ever_rx = True
            self.seen = True
            out.append(m)
        mv = None
        self._rxbuf = bytearray(buf[st:]) if st < len(buf) else bytearray()
        return out

    # ---- 送 ----
    def send(self, d):
        if self.conn is None:
            return False
        if len(self._txq) > 24:
            self._txq.pop(0)
        self._txq.append(json.dumps(d).encode() + b"\n")
        return True

    def pump(self):
        """每圈呼叫一次: 送出佇列中的資料 (一次一個封包, 避免塞爆)"""
        if self.conn is None or not self._txq:
            return
        size = max(20, self.mtu - 3)
        head = self._txq[0]
        chunk = head[:size]
        try:
            self.ble.gatts_notify(self.conn, self.h_tx, chunk)
        except OSError:
            return
        if len(head) > size:
            self._txq[0] = head[size:]
        else:
            self._txq.pop(0)

    def disconnect(self):
        if self.conn is not None:
            try:
                self.ble.gap_disconnect(self.conn)
            except Exception:
                pass


class NullLink:
    """沒有藍牙時(例如模擬器)的替身。"""
    connected = False
    mtu = 23

    n_conn = n_disc = last_up = 0

    def online(self, now):
        return False

    def poll(self, now):
        return ()

    def send(self, d):
        return False

    def pump(self):
        pass

    def disconnect(self):
        pass
