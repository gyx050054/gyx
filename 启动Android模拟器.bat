@echo off
chcp 65001 >nul
title 智能灌溉 - 启动 Android 模拟器

echo.
echo  ========================================
echo    智能灌溉系统 - 启动 Android 模拟器
echo    设备：Pixel_7
echo  ========================================
echo.
echo  正在启动模拟器，窗口弹出后请等待开机完成...
echo  （首次启动较慢，约 1-3 分钟，请耐心等待）
echo.

start "" "D:\Android\Sdk\emulator\emulator.exe" -avd Pixel_7

timeout /t 2 >nul
exit
