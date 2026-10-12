$ErrorActionPreference="SilentlyContinue"
$root="C:\MUBEL_B2B"
$status=Join-Path $root "var\mubel-readonly\DURUM.txt"
Write-Host ""
Write-Host "MUBEL B2B READ ONLY DURUM" -ForegroundColor Cyan
Write-Host "------------------------" -ForegroundColor DarkGray
if(Test-Path $status){ Get-Content $status | ForEach-Object { Write-Host $_ } }
else { Write-Host "DURUM.txt henuz olusmamis." -ForegroundColor Yellow }
Write-Host ""
try {
  $h=Invoke-RestMethod "http://127.0.0.1:8787/health" -TimeoutSec 3
  if($h.ok){ Write-Host "[OK] ERP Agent 127.0.0.1:8787 ayakta" -ForegroundColor Green }
} catch { Write-Host "[X] ERP Agent cevap vermiyor" -ForegroundColor Red }
try {
  $null=Invoke-RestMethod "http://127.0.0.1:3000/api/health" -TimeoutSec 3
  Write-Host "[OK] B2B web 127.0.0.1:3000 ayakta" -ForegroundColor Green
} catch { Write-Host "[X] B2B web cevap vermiyor" -ForegroundColor Red }
schtasks.exe /Query /TN "MUBEL_B2B_ERP_READONLY" /FO LIST 2>$null
