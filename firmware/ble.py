"""BLE GATT 伺服器 (Nordic UART 服務, 訊息為一行一筆 JSON)。
手機 App 當 Central 連上來: 手機寫入 RX 特徵 -> 狀態; 本機由 TX 特徵 notify -> 指令。"""
import time
try:
    import ujson as json
except ImportError:
    import json
import gc
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
        self._rx = []                     # 收到的封包片段 (小物件, 不用一直放大同一塊緩衝區 -> 少碎片)
        self._rxn = 0                     # 片段總長度
        self._nl = False                  # 片段裡出現過換行 = 有完整訊息可以處理
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
            self.drop_rx()
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
                if self._rxn + len(chunk) < 4096:
                    self._rx.append(chunk)
                    self._rxn += len(chunk)
                    if chunk.find(b"\n") >= 0:
                        self._nl = True
                else:
                    self.drop_rx()
        elif ev == _MTU:
            self.mtu = data[1]

    # ---- 收 ----
    def drop_rx(self):
        """丟掉收到一半的資料 (手機之後會再同步)"""
        self._rx = []
        self._rxn = 0
        self._nl = False

    def poll(self, now):
        """回傳手機傳來的訊息 (dict list)。
        封包片段先放在 list, 有換行才一次接起來 (一般就是一則完整訊息, 直接交給 json, 不再多複製一份)。"""
        if not self._nl:
            if self._rxn > 6000:                # 一直沒有換行 = 壞資料, 不要無限累積
                self.drop_rx()
            return ()
        self._nl = False                        # 先清旗標再換掉 list: 中途又來的片段不會被漏掉
        ch = self._rx
        self._rx = []
        try:
            data = b"".join(ch)
        except MemoryError:                     # 接不起來: 這批丟掉, 否則會永遠卡在這裡
            self._rxn = 0
            return ()
        ch = None
        self._rxn = 0
        out = []
        if data.find(b"\n") == len(data) - 1:  # 常見情形: 剛好一則完整訊息 (json 允許尾端換行)
            lines = (data,)
            rest = b""
        else:
            lines = data.split(b"\n")
            rest = lines.pop()                  # 最後一段是還沒收完的
        data = None
        for p in lines:
            if len(p) < 2:
                continue
            if len(p) > 500:                    # 大訊息解析前先整理, 免得碎片讓它失敗
                gc.collect()
            try:
                m = json.loads(p)
            except Exception:                   # 含 MemoryError: 這則丟掉, 手機之後會再傳
                continue
            self.last_rx = now
            self.ever_rx = True
            self.seen = True
            out.append(m)
        lines = None
        if rest:                                # 沒收完的放回最前面, 後面來的片段接在它後面
            self._rx.insert(0, rest)
            self._rxn += len(rest)
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

    def drop_rx(self):
        pass

    def disconnect(self):
        pass
