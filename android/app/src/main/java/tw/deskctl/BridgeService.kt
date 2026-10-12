package tw.deskctl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/**
 * 常駐服務: 與 ESP32 (DeskCtl) 建立 BLE 連線並維持住。
 * 這個類別只管「連線與收發」; 時間/電量/音樂等實際功能都在 Feature 模組 (見 Features.kt)。
 */
@SuppressLint("MissingPermission")
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "tw.deskctl.START"
        const val ACTION_STOP = "tw.deskctl.STOP"
        const val ACTION_FORGET = "tw.deskctl.FORGET"
        const val ACTION_RECONNECT = "tw.deskctl.RECONNECT"
        const val ACTION_SYNC = "tw.deskctl.SYNC"
        const val ACTION_MEMCLEAN = "tw.deskctl.MEMCLEAN"     // 要求控制器清理記憶體
        const val ACTION_RUN = "tw.deskctl.RUN"        // extra "i" = 捷徑編號 (手機上測試執行用)
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val DEVICE_NAME = "DeskCtl"
        private const val TAG = "BLE"

        val NUS: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAR_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // 手機 -> 裝置 (寫入)
        val CHAR_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // 裝置 -> 手機 (通知)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 給 MainActivity 顯示用的即時狀態
        @Volatile var status: String = "尚未啟動"
        @Volatile var running: Boolean = false
        @Volatile var paused: Boolean = false            // 找不到控制器太久, 已自動暫停搜尋
        const val GIVE_UP_MS = 10 * 60 * 1000L
        @Volatile var connected: Boolean = false
        @Volatile var connectedSince: Long = 0L          // SystemClock.elapsedRealtime
        @Volatile var infoLines: List<String> = emptyList()
    }

    private fun log(msg: String) = AppLog.i(TAG, msg)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var am: android.media.AudioManager
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var ready = false
        set(v) {
            field = v
            connected = v
            if (v) { searchSince = 0L; paused = false }
            connectedSince = if (v) SystemClock.elapsedRealtime() else 0L
        }
    private var mtu = 23
    private var scanning = false
    private var searchSince = 0L          // 開始(連續)找不到控制器的時間, 連上就歸零
    private var inited = false
    private var tick = 0
    private var lastRx = 0L
    private var connectFails = 0
    private val connectRunnable = Runnable { connect() }
    private val writeRelease = Runnable { writing = false; pump() }
    private val connTimeout = Runnable {
        if (!ready && gatt != null) {
            AppLog.w(TAG, "連線逾時, 重試")
            closeGatt(); connectFails++; scheduleConnect(800)
        }
    }

    private fun scheduleConnect(ms: Long) {
        h.removeCallbacks(connectRunnable)
        h.postDelayed(connectRunnable, ms)
    }

    private val txQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private val inBuf = StringBuilder()
    private val lastSent = HashMap<String, String>()

    // 功能模組
    private var features: List<Feature> = emptyList()

    private val host = object : FeatureHost {
        override val context: Context get() = this@BridgeService
        override val handler: Handler get() = h
        override val isReady: Boolean get() = ready
        override fun send(o: JSONObject) = this@BridgeService.send(o)
        override fun sendIfChanged(key: String, o: JSONObject) = this@BridgeService.sendIfChanged(key, o)
    }

    private fun eachFeature(block: (Feature) -> Unit) {
        for (f in features) {
            try {
                block(f)
            } catch (e: Exception) {
                AppLog.e(f.id, "發生錯誤: " + e)
            }
        }
    }

    // ---------------- 生命週期 ----------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setWasStarted(this, false)
                AppLog.i(TAG, "使用者停止服務")
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FORGET -> {
                Prefs.setMac(this, null)
                AppLog.i(TAG, "已忘記控制器, 重新搜尋")
                if (inited) { resumeSearch(); searchSince = 0L; stopScan(); closeGatt(); connectFails = 0; scheduleConnect(100) }
            }
            ACTION_RECONNECT -> {
                AppLog.i(TAG, "手動重新連線")
                if (inited) { resumeSearch(); searchSince = 0L; stopScan(); closeGatt(); connectFails = 0; scheduleConnect(100) }
            }
            ACTION_RUN -> {
                val i = intent?.getIntExtra("i", -1) ?: -1
                if (inited && i >= 0) handleCmd(JSONObject().put("c", "run").put("i", i))
            }
            ACTION_MEMCLEAN -> {
                if (inited && ready) {
                    AppLog.i(TAG, "要求控制器清理記憶體")
                    send(JSONObject().put("k", "memclean"))
                }
            }
            ACTION_SYNC -> {
                if (inited && ready) {
                    AppLog.i(TAG, "手動重新同步")
                    lastSent.clear()
                    eachFeature { it.onReady() }
                }
            }
        }
        startAsForeground()
        if (inited && paused && (intent?.action == null || intent?.action == ACTION_START)) {
            resumeSearch()                       // 使用者重新開啟 App / 按啟動: 恢復搜尋
            scheduleConnect(100)
        }
        if (!inited) init()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "桌邊控制器", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotif(status)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifAction(title: String, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, BridgeService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_rotate)
        return Notification.Action.Builder(icon, title, pi).build()
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("桌邊控制器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(notifAction("重新連線", ACTION_RECONNECT, 1))
            .addAction(notifAction("停止", ACTION_STOP, 2))
            .build()
    }

    private fun setStatus(s: String) {
        if (s == status) return
        status = s
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(s))
        } catch (_: Exception) {
        }
    }

    private fun init() {
        inited = true
        running = true
        Prefs.setWasStarted(this, true)
        am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        AppLog.i(TAG, "服務啟動")
        features = Features.registry.map { it.make(host) }
        eachFeature { it.start() }
        acquireWake()
        connect()
        h.postDelayed(ticker, 1000)
    }

    private var wake: android.os.PowerManager.WakeLock? = null

    /** 部分喚醒鎖: 螢幕關閉時 CPU 仍會跑, 計時器與藍牙才不會被凍結 (較耗電, 可在設定關閉) */
    private fun acquireWake() {
        releaseWake()
        if (!Prefs.wakeLock(this)) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val w = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "deskctl:bridge")
            w.setReferenceCounted(false)
            w.acquire()
            wake = w
            AppLog.i(TAG, "已持有喚醒鎖")
        } catch (e: Exception) {
            AppLog.w(TAG, "喚醒鎖失敗: " + e)
        }
    }

    private fun releaseWake() {
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wake = null
    }

    private fun shutdown() {
        running = false
        inited = false
        h.removeCallbacksAndMessages(null)
        eachFeature { it.stop() }
        releaseWake()
        features = emptyList()
        infoLines = emptyList()
        stopScan()
        closeGatt()
        setStatus("已停止")
        AppLog.i(TAG, "服務已停止")
    }

    override fun onDestroy() {
        if (inited) shutdown()
        super.onDestroy()
    }

    // ---------------- 連線 ----------------
    private fun hasBt(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun connect() {
        if (!inited) return
        if (gatt != null || scanning) return          // 已在連線/掃描中, 不要重複動作 (否則會把好好的連線關掉)
        if (paused) return
        val nowMs = SystemClock.elapsedRealtime()
        if (searchSince == 0L) searchSince = nowMs
        if (Prefs.autoPause(this) && nowMs - searchSince > GIVE_UP_MS) {
            pauseSearch()
            return
        }
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            setStatus("請先開啟手機藍牙")
            scheduleConnect(4000)
            return
        }
        if (!hasBt()) {
            setStatus("缺少藍牙權限, 請開啟 App 授權")
            scheduleConnect(5000)
            return
        }
        val mac = Prefs.mac(this)
        if (mac != null && connectFails < 4) {
            setStatus("連線中…")
            log("直連 $mac (第${connectFails + 1}次)")
            val dev = ad.getRemoteDevice(mac)
            gatt = dev.connectGatt(this, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            h.removeCallbacks(connTimeout)
            h.postDelayed(connTimeout, 12000)
        } else {
            connectFails = 0
            startScan(ad)
        }
    }

    /** 連續 10 分鐘都連不上控制器: 停止搜尋與喚醒鎖, 省電。使用者按「重新連線」或重新開啟 App 才會恢復 */
    private fun pauseSearch() {
        paused = true
        h.removeCallbacks(connectRunnable)
        h.removeCallbacks(connTimeout)
        stopScan()
        closeGatt()
        releaseWake()
        AppLog.w(TAG, "連續 10 分鐘找不到控制器, 已暫停搜尋以省電")
        setStatus("已暫停搜尋 (10 分鐘找不到控制器), 按「重新連線」恢復")
    }

    private fun resumeSearch() {
        if (!paused) return
        paused = false
        searchSince = 0L
        acquireWake()
        AppLog.i(TAG, "恢復搜尋控制器")
    }

    private fun startScan(ad: BluetoothAdapter) {
        if (scanning) return
        val sc = ad.bluetoothLeScanner ?: run {
            setStatus("無法掃描藍牙"); scheduleConnect(4000); return
        }
        setStatus("搜尋 $DEVICE_NAME …")
        val filters = listOf(ScanFilter.Builder().setDeviceName(DEVICE_NAME).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        sc.startScan(filters, settings, scanCb)
        h.postDelayed({
            if (scanning) { stopScan(); scheduleConnect(500) }   // 逾時後重新掃描
        }, 20000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post {
                if (!scanning) return@post
                stopScan()
                Prefs.setMac(this@BridgeService, result.device.address)
                setStatus("找到裝置, 連線中…")
                log("找到 ${result.device.address}")
                closeGatt()
                connectFails = 0
                gatt = result.device.connectGatt(
                    this@BridgeService, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE
                )
                h.removeCallbacks(connTimeout)
                h.postDelayed(connTimeout, 12000)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            h.post { scanning = false; setStatus("掃描失敗($errorCode)"); scheduleConnect(5000) }
        }
    }

    private fun closeGatt() {
        h.removeCallbacks(connTimeout)
        h.removeCallbacks(writeRelease)
        ready = false
        writing = false
        txQueue.clear()
        inBuf.setLength(0)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            h.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("已連上 (status=$st)")
                    setStatus("已連上, 協商中…")
                    lastRx = SystemClock.elapsedRealtime()
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    AppLog.w(TAG, "斷線 status=$st (8=逾時 19=裝置端斷開 133=連線失敗)")
                    setStatus("連線中斷(代碼 $st), 重新連線…")
                    val wasReady = ready
                    closeGatt()
                    if (!wasReady) connectFails++ else connectFails = 0
                    scheduleConnect(if (wasReady) 400 else 1500)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            h.post { mtu = if (st == BluetoothGatt.GATT_SUCCESS) m else 23; g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            h.post {
                val svc = g.getService(NUS)
                val tx = svc?.getCharacteristic(CHAR_TX)
                rxChar = svc?.getCharacteristic(CHAR_RX)
                if (tx == null || rxChar == null) {
                    setStatus("找不到控制器服務")
                    return@post
                }
                g.setCharacteristicNotification(tx, true)
                val d = tx.getDescriptor(CCCD)
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            h.post {
                ready = true
                connectFails = 0
                h.removeCallbacks(connTimeout)
                log("服務就緒 MTU=$mtu")
                setStatus("已連線 DeskCtl")
                lastSent.clear()
                eachFeature { it.onReady() }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            h.post { h.removeCallbacks(writeRelease); writing = false; pump() }
        }

        // Android 13+ 走這個
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            h.post { onIncoming(String(value, Charsets.UTF_8)) }
        }

        // Android 12 以下走這個
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val bytes = c.value ?: return
            h.post { onIncoming(String(bytes, Charsets.UTF_8)) }
        }
    }

    // ---------------- 傳送 ----------------
    private fun send(o: JSONObject) {
        if (!ready) return
        val data = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
        val size = maxOf(20, mtu - 3)
        var i = 0
        while (i < data.size) {
            val e = minOf(data.size, i + size)
            if (txQueue.size < 300) txQueue.addLast(data.copyOfRange(i, e))
            i = e
        }
        pump()
    }

    /** 內容沒變就不重送 (key 用來比對) */
    private fun sendIfChanged(key: String, o: JSONObject) {
        val s = o.toString()
        if (lastSent[key] == s) return
        lastSent[key] = s
        send(o)
    }

    private fun pump() {
        val g = gatt ?: return
        val c = rxChar ?: return
        if (!ready || writing || txQueue.isEmpty()) return
        val chunk = txQueue.removeFirst()
        c.value = chunk
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = g.writeCharacteristic(c)
        if (ok) {
            writing = true
            h.removeCallbacks(writeRelease)
            h.postDelayed(writeRelease, 25)      // 回呼沒來也會自動釋放, 避免卡死
        } else {
            txQueue.addFirst(chunk)
            h.removeCallbacks(writeRelease)
            writing = true
            h.postDelayed(writeRelease, 40)
        }
    }

    // ---------------- 接收指令 ----------------
    private fun onIncoming(s: String) {
        lastRx = SystemClock.elapsedRealtime()
        inBuf.append(s)
        while (true) {
            val i = inBuf.indexOf("\n")
            if (i < 0) break
            val line = inBuf.substring(0, i).trim()
            inBuf.delete(0, i + 1)
            if (line.isEmpty()) continue
            try {
                handleCmd(JSONObject(line))
            } catch (e: Exception) {
                AppLog.w(TAG, "無法解析: " + line.take(60))
            }
        }
        if (inBuf.length > 2000) inBuf.setLength(0)
    }

    private fun handleCmd(o: JSONObject) {
        val c = o.optString("c")
        when (c) {
            "", "pong" -> return
            "sync" -> {
                lastSent.clear()
                eachFeature { it.onReady() }
                return
            }
        }
        for (f in features) {
            val handled = try {
                f.onCommand(c, o)
            } catch (e: Exception) {
                AppLog.e(f.id, "指令 $c 發生錯誤: " + e)
                true
            }
            if (handled) return
        }
        AppLog.w("CMD", "沒有功能處理這個指令: $c")
    }

    // ---------------- 每秒節拍 ----------------
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            if (ready) {
                eachFeature { it.onTick(tick) }
                if (tick % 3 == 0) send(JSONObject().put("k", "ping"))
                // 看門狗: 20 秒沒收到任何東西 -> 認定連線已死, 主動重連
                if (SystemClock.elapsedRealtime() - lastRx > 20000) {
                    AppLog.w(TAG, "20 秒沒有回應, 主動重連")
                    closeGatt(); scheduleConnect(300)
                }
            }
            if (tick % 2 == 0) {
                val l = ArrayList<String>()
                for (f in features) {
                    val t = f.info()
                    if (t.isNotEmpty()) l.add(t)
                }
                infoLines = l
            }
            if (inited) h.postDelayed(this, 1000)
        }
    }
}
