@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0MUBEL_B2B_READONLY_DURUM.ps1"
pause
