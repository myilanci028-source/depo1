@echo off
chcp 65001 >nul
title MUBEL B2B - VEGA READ ONLY KOPRU
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0MUBEL_B2B_READONLY_KUR.ps1"
set RC=%ERRORLEVEL%
echo.
if not "%RC%"=="0" (
  echo [MUBEL] Kurulum guvenli sekilde durdu. Hata kodu: %RC%
  echo [MUBEL] YILANCIOGLU is verisine yazma yapilmadi.
) else (
  echo [MUBEL] READ ONLY kopru kurulumu tamamlandi.
)
pause
exit /b %RC%
