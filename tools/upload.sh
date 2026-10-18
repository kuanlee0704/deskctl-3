#!/bin/sh
# 把韌體傳到板子 (需要 pip install mpremote)。用法: ./upload.sh [序列埠, 例如 COM5 或 /dev/ttyACM0]
PORT=${1:-auto}
cd "$(dirname "$0")/../firmware" || exit 1
if [ "$PORT" = "auto" ]; then M="mpremote"; else M="mpremote connect $PORT"; fi
for f in *.py font12.bin manual.txt; do
  echo "上傳 $f"
  $M fs cp "$f" ":$f" || exit 1
done
$M reset
