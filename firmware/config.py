VERSION = "0.25"

# ===== 硬體腳位 =====
# 擴充板(Display-Keypad)是旋轉 180° 疊在核心板上: 擴充板第 n 腳 -> 核心板第 33-n 腳
#   LKEY(3)->核心30=GPIO9   UPKEY(4)->核心29=GPIO8   CENTER(5)->核心28=GPIO4
#   DWKEY(6)->核心27=GPIO5  RKEY(23)->核心10=GPIO13
#   BL(9)->核心24=GPIO11  CS(10)->核心23=GPIO7  DC(11)->核心22=GPIO6
#   RES(12)->核心21=GPIO10  SDA(13)->核心20=GPIO3  SCL(14)->核心19=GPIO2
# 板子橫放(按鍵在右、螢幕在左)時, 實體方向與電路圖上的名稱差 90°:
#   電路圖 DWKEY(GPIO5) = 橫放時的「左」     電路圖 LKEY(GPIO9)  = 橫放時的「右」(已依實測對調)
#   電路圖 UPKEY(GPIO8) = 橫放時的「上」     電路圖 RKEY(GPIO13) = 橫放時的「下」
# 若方向還是不對, 只要在這裡對調數字即可 (校正畫面最下面會顯示你按到哪個鍵)
PIN_LEFT = 5
PIN_UP = 8
PIN_CENTER = 4
PIN_DOWN = 13
PIN_RIGHT = 9

PIN_SCK = 2
PIN_MOSI = 3
PIN_DC = 6
PIN_CS = 7
PIN_RST = 10
PIN_BL = 11
# 這塊板子實測 GPIO11 (ESP32-C3 的 VDD_SPI 腳) 無法控制背光, 所以預設不做背光調光。
# 若你把背光改接到可用的腳位 (改 PIN_BL), 再把這個改成 True 即可啟用亮度/變暗功能。
BL_CONTROL = False

# ===== 螢幕 (橫向 160x80) =====
LCD_W = 160
LCD_H = 80
# 0.96" 80x160 IPS 有幾種記憶體偏移規格 (橫向時的 x偏移, y偏移); 校正畫面按「右」切換
PANELS = ((1, 26), (0, 24), (0, 0), (2, 26))
SPI_BAUD = 20_000_000
# 橫向的 4 種 MADCTL 組合, 校正畫面可切換
ORIENTS = (0x60, 0xA0, 0x20, 0xE0)

# ===== 按鍵時間 (ms) =====
DEBOUNCE_MS = 25
LONG_MS = 600       # 長按門檻
DBL_MS = 260        # 連按判定間隔
HOLD_MS = 220       # 長按不放的重複間隔
VLONG_MS = 5000     # 超長按 (重啟 / 重設)
SHOW_VLONG_MS = 1800  # 超過這個時間開始顯示倒數

# ===== 歌名捲動 =====
MARQUEE_MS_PER_PX = 50    # 越大越慢 (每捲 1 像素要幾毫秒; 舊版 35, 現為 50 約 20 像素/秒)
MARQUEE_HOLD_MS = 1200    # 每輪開頭停留多久
MARQUEE_GAP_PX = 28       # 歌名接回來之前的空白寬度

# ===== 藍牙 =====
BLE_NAME = "DeskCtl"
ONLINE_GRACE_MS = 12000     # 斷線後這段時間內若很快接回, 畫面不顯示離線
ONLINE_TIMEOUT_MS = 25000   # 超過這時間沒收到手機 App 的訊息 -> 視為離線

# ===== 其他 =====
FONT_FILE = "font12.bin"
SETTINGS_FILE = "settings.json"
FPS_MS = 40         # 最快 25 fps
