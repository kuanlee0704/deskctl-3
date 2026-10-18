@echo off
rem Windows 用: upload.bat COM5   (需要 pip install mpremote)
set PORT=%1
if "%PORT%"=="" (set M=mpremote) else (set M=mpremote connect %PORT%)
cd /d "%~dp0..\firmware"
for %%f in (*.py font12.bin manual.txt) do (
  echo 上傳 %%f
  %M% fs cp %%f :%%f || exit /b 1
)
%M% reset
