import time
import sys
import machine
from config import *


def boot():
    import gc
    # 藍牙控制器要最先開, 趁記憶體還沒被畫面/字型緩衝區佔走
    try:
        import bluetooth
        bluetooth.BLE().active(True)
        print("bt early ok, reset cause", machine.reset_cause())
    except Exception as e:
        print("bt early fail:", e, "reset cause", machine.reset_cause())
    from gfx import Font, Canvas
    from lcd import LCD
    from keys import Keys
    from state import State
    gc.collect()
    st = State()
    cfg = st.cfg
    print("DeskCtl v" + VERSION, cfg.d)
    keys = Keys()
    force_calib = bool(keys.read_raw()[2])       # 開機時按住中央 -> 強制進入校正
    lcd = LCD(cfg.get("orient"), bool(cfg.get("bgr")), bool(cfg.get("inv")), cfg.get("panel"))
    font = Font(FONT_FILE)
    c = Canvas(LCD_W, LCD_H, font)
    if force_calib or not cfg.get("calib"):
        import calib
        calib.run(lcd, c, keys, cfg)
        del sys.modules["calib"]
        del calib
        gc.collect()
    from ble import NullLink
    from app import App
    app = App(c, lcd, keys, NullLink(), st, time.ticks_ms(), machine.reset)   # 先建好 UI 與大緩衝區
    gc.collect()
    try:
        from ble import Link
        app.link = Link()
        if cfg.get("btrst"):
            cfg.set("btrst", 0)
    except Exception as e:                      # 沒有藍牙也能開機
        sys.print_exception(e)
        if not cfg.get("btrst"):                # 藍牙卡住 -> 硬重啟一次 (只試一次, 避免無限重啟)
            cfg.set("btrst", 1)
            time.sleep_ms(200)
            machine.reset()
    gc.collect()
    try:
        gc.threshold(GC_THRESHOLD)          # 每配置這麼多位元組就主動回收一次, 垃圾不會累積到記憶體見底才處理 (減少碎片)
    except Exception:
        pass
    print("mem free", gc.mem_free(), "alloc", gc.mem_alloc())
    return app, lcd, c


def log_error(msg, app=None):
    """把最後一次致命錯誤寫進 err.log (Thonny 可以直接看), 只保留一筆"""
    try:
        import gc
        with open("err.log", "w") as f:
            f.write(msg[-400:] + "\nfree=%d page=%s\n" % (gc.mem_free(), getattr(app, "cur_id", "?")))
    except Exception:
        pass


def show_error(lcd, c, e, app=None):
    try:
        import io
        buf = io.StringIO()
        sys.print_exception(e, buf)
        full = buf.getvalue()
        log_error(full, app)
        if isinstance(e, MemoryError):
            msg = "記憶體不足, 自動重新啟動中"
        else:
            msg = full.replace("\n", " ")[-150:]
    except Exception:
        msg = repr(e)
    c.fill(1)
    y = 2
    line = ""
    for ch in msg:
        if c.text_w(line + ch) > 152:
            c.text(line, 4, y, 0xFFFF)
            y += 12
            line = ""
            if y > 66:
                break
        line += ch
    c.text(line, 4, y, 0xFFFF)
    lcd.show(c.buf)


def run():
    import gc
    try:
        import micropython
        micropython.alloc_emergency_exception_buf(100)
    except Exception:
        pass
    app = lcd = c = None
    mem_errs = []
    try:
        app, lcd, c = boot()
        while True:
            try:
                app.step(time.ticks_ms())
            except MemoryError as e:
                # 記憶體不足: 逐級自救, 不重開機。30 秒內第 1 次 整理+卸載其他頁面, 第 2 次 丟掉這頁的暫存,
                # 第 3 次 回首頁, 第 6 次以上都救不回來才重啟 (並把原因寫進 err.log)
                now = time.ticks_ms()
                mem_errs.append(now)
                while mem_errs and time.ticks_diff(now, mem_errs[0]) > 30000:
                    mem_errs.pop(0)
                n = len(mem_errs)
                if n >= 6:
                    raise
                try:
                    if n > 1:
                        raise ValueError
                    import io
                    b = io.StringIO()
                    sys.print_exception(e, b)
                    log_error(b.getvalue(), app)
                except Exception:
                    pass
                f = app.recover(min(n, 3))
                print("MemoryError #%d free=%d" % (n, f))
                time.sleep_ms(80)
            app.pm.nap()
    except KeyboardInterrupt:
        raise
    except Exception as e:
        sys.print_exception(e)
        if lcd is not None:
            show_error(lcd, c, e, app)
        time.sleep(3)
        machine.reset()


run()
