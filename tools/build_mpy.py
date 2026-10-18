#!/usr/bin/env python3
"""把 firmware/*.py 編成 .mpy, 輸出到 firmware_mpy/ (在你的電腦上執行, 需要 pip install mpy-cross)。

用法:  python tools/build_mpy.py
注意:  mpy-cross 的版本要跟板子的 MicroPython 相容 (見 README「改用 .mpy」)。
保留 .py 不編譯: boot.py main.py config.py (入口檔與會手動修改的設定)
"""
import os, shutil, subprocess, sys

KEEP_PY = {"boot.py", "main.py", "config.py"}
here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "..", "firmware")
out = os.path.join(here, "..", "firmware_mpy")

try:
    ver = subprocess.run(["mpy-cross", "--version"], capture_output=True, text=True).stdout.strip()
except FileNotFoundError:
    sys.exit("找不到 mpy-cross, 請先執行: pip install mpy-cross")
print("使用", ver)

shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)
n_py = n_mpy = 0
for f in sorted(os.listdir(src)):
    p = os.path.join(src, f)
    if f.endswith(".py") and f not in KEEP_PY:
        o = os.path.join(out, f[:-3] + ".mpy")
        r = subprocess.run(["mpy-cross", "-o", o, p], capture_output=True, text=True)
        if r.returncode:
            sys.exit("編譯失敗 %s:\n%s" % (f, r.stderr))
        print("%-16s %6d -> %6d bytes" % (f, os.path.getsize(p), os.path.getsize(o)))
        n_mpy += 1
    elif f.endswith((".py", ".bin", ".txt")):
        shutil.copy(p, os.path.join(out, f))
        n_py += 1
print("完成: %d 個 .mpy, %d 個原樣複製 (.py/.bin/.txt) -> %s" % (n_mpy, n_py, os.path.normpath(out)))
print("上傳前請先在板子上刪掉同名的 .py, 否則會優先載入舊的 .py!")
