@echo off
chcp 65001 >nul
schtasks.exe /End /TN "MUBEL_B2B_ERP_READONLY" 2>nul >nul
schtasks.exe /Delete /TN "MUBEL_B2B_ERP_READONLY" /F 2>nul >nul
echo [OK] MUBEL B2B ERP READ ONLY ajan gorevi durduruldu.
echo YILANCIOGLU tablolarina dokunulmadi.
pause
