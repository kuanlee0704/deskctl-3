"""粗略檢查韌體有沒有 MicroPython 不支援、但 CPython 模擬環境放行的寫法 (例如 del bytearray[切片])。
用法: python3 test_upy_compat.py   (有問題時結束碼為 1)"""
import ast, glob, os, sys

FW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "firmware")
OK_MODULES = {"app", "ble", "bluetooth", "calib", "config", "esp32", "framebuf", "gc", "gfx", "io", "json", "keys", "lcd",
              "machine", "micropython", "musicctl", "os", "power", "random", "registry", "state", "struct", "sys", "time",
              "ui", "ujson", "p_scoret", "network", "ubinascii", "binascii", "math", "array", "uos", "utime", "ure", "re"}
BAD_ATTRS = {"removeprefix", "removesuffix", "isascii", "bit_count", "casefold", "format_map", "zfill_", "isidentifier"}
# 允許對 list 做 del x[切片] 的 (檔案, 變數名); 其餘一律視為可能是 bytearray 而報錯
LIST_DELS = {("keys.py", "out")}
bad = []
for path in sorted(glob.glob(os.path.join(FW, "*.py"))):
    fn = os.path.basename(path)
    tree = ast.parse(open(path, encoding="utf-8").read(), fn)
    for n in ast.walk(tree):
        if isinstance(n, ast.Import):
            for a in n.names:
                top = a.name.split(".")[0]
                if top not in OK_MODULES and not top.startswith(("p_", "g_")):
                    bad.append("%s:%d import %s (MicroPython 可能沒有)" % (fn, n.lineno, top))
        elif isinstance(n, ast.ImportFrom):
            top = (n.module or "").split(".")[0]
            if top not in OK_MODULES and not top.startswith(("p_", "g_")):
                bad.append("%s:%d from %s (MicroPython 可能沒有)" % (fn, n.lineno, top))
        elif isinstance(n, ast.Delete):
            for t in n.targets:
                if isinstance(t, ast.Subscript) and isinstance(t.slice, ast.Slice):
                    name = t.value.id if isinstance(t.value, ast.Name) else (t.value.attr if isinstance(t.value, ast.Attribute) else "?")
                    if (fn, name) not in LIST_DELS:
                        bad.append("%s:%d del %s[切片] (bytearray 不支援, 確認是 list 後加入 LIST_DELS)" % (fn, n.lineno, name))
        elif isinstance(n, ast.Attribute) and n.attr in BAD_ATTRS:
            bad.append("%s:%d .%s (舊版 MicroPython 沒有)" % (fn, n.lineno, n.attr))
        elif isinstance(n, (ast.Match,)):
            bad.append("%s:%d match 語法 (MicroPython 不支援)" % (fn, n.lineno))
        elif isinstance(n, ast.BinOp) and isinstance(n.op, ast.BitOr) and isinstance(n.left, ast.Dict):
            bad.append("%s:%d dict | dict (MicroPython 不支援)" % (fn, n.lineno))
for b in bad:
    print("FAIL", b)
print("\n%d compat issues" % len(bad))
sys.exit(1 if bad else 0)
