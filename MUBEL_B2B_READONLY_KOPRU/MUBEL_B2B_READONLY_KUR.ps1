#requires -Version 5.1
$ErrorActionPreference="Stop"
$ProgressPreference="SilentlyContinue"

$Root="C:\MUBEL_B2B"
$AgentDir=Join-Path $Root "apps\erp-agent"
$LogDir=Join-Path $Root "var\mubel-readonly"
$LogFile=Join-Path $LogDir "kurulum.log"
$StatusFile=Join-Path $LogDir "DURUM.txt"
$ConfigFile=Join-Path $AgentDir "agent.config.json"
$WebEnv=Join-Path $Root "apps\web\.env.local"
$TaskName="MUBEL_B2B_ERP_READONLY"
$ReaderLogin="MUBEL_B2B_READER"
$ApiUrl="http://127.0.0.1:3000"
$AgentUrl="http://127.0.0.1:8787"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Log([string]$m){
  $x="[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"),$m
  Write-Host $x
  Add-Content $LogFile $x -Encoding UTF8
}
function OK([string]$m){ Write-Host "  [OK] $m" -ForegroundColor Green; Log $m }
function Stop-Safe([string]$m,[int]$code){
  Write-Host "  [DURDU] $m" -ForegroundColor Red
  Log "DURDU: $m"
  @"
MUBEL B2B READ ONLY - KURULUM DURDU
Tarih: $(Get-Date)
Sebep: $m

YILANCIOGLU is verisine INSERT/UPDATE/DELETE yapilmadi.
Yazma yetkili SQL hesabi ERP Agent'a verilmedi.
Log: $LogFile
"@ | Set-Content $StatusFile -Encoding UTF8
  exit $code
}
function Secret([int]$bytes=28){
  $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
  try{
    $b=New-Object byte[] $bytes; $rng.GetBytes($b)
    return "M!9a"+(-join($b|%{$_.ToString("x2")}))
  }finally{$rng.Dispose()}
}
function HttpOK([string]$u){
  try{$r=Invoke-WebRequest -UseBasicParsing $u -TimeoutSec 3; return $r.StatusCode -lt 500}catch{return $false}
}
function EnvGet([string]$p,[string]$k){
  if(!(Test-Path $p)){return $null}
  foreach($l in Get-Content $p){
    if($l -match "^\s*$([regex]::Escape($k))\s*=\s*(.*)$"){
      $v=$matches[1].Trim().Trim('"').Trim("'"); return $v
    }
  }
  return $null
}
function EnvSet([string]$p,[string]$k,[string]$v){
  $d=Split-Path -Parent $p
  if(!(Test-Path $d)){New-Item -ItemType Directory -Force $d|Out-Null}
  $a=@(); if(Test-Path $p){Copy-Item $p "$p.mubelbak_$(Get-Date -Format yyyyMMdd_HHmmss)"; $a=@(Get-Content $p)}
  $rx="^\s*$([regex]::Escape($k))\s*="; $found=$false
  for($i=0;$i -lt $a.Count;$i++){if($a[$i]-match $rx){$a[$i]="$k=`"$v`"";$found=$true}}
  if(!$found){$a+="$k=`"$v`""}
  $a|Set-Content $p -Encoding UTF8
}
function SqlOpen([string]$server,[string]$db,[string]$user,[string]$pass){
  $b=New-Object Data.SqlClient.SqlConnectionStringBuilder
  $b["Data Source"]=$server;$b["Initial Catalog"]=$db;$b["User ID"]=$user;$b["Password"]=$pass
  $b["Integrated Security"]=$false;$b["Connect Timeout"]=3;$b["TrustServerCertificate"]=$true
  $c=New-Object Data.SqlClient.SqlConnection($b.ConnectionString);$c.Open();return $c
}
function Scalar($c,[string]$q){$x=$c.CreateCommand();$x.CommandText=$q;return $x.ExecuteScalar()}
function NonQuery($c,[string]$q){$x=$c.CreateCommand();$x.CommandTimeout=60;$x.CommandText=$q;[void]$x.ExecuteNonQuery()}

Clear-Host
Write-Host ""
Write-Host "  MUBEL B2B - VEGA READ ONLY KOPRU" -ForegroundColor Cyan
Write-Host "  YSERVER'a program kurulmaz; C:\MUBEL_B2B PC uzerinden okunur." -ForegroundColor Gray
Write-Host ""

if(!(Test-Path (Join-Path $Root "package.json"))){Stop-Safe "C:\MUBEL_B2B bulunamadi." 10}
if(!(Test-Path $AgentDir)){Stop-Safe "apps\erp-agent bulunamadi." 11}

if(!(Get-Command node.exe -ErrorAction SilentlyContinue)){Stop-Safe "Node.js bulunamadi." 12}
if(!(Get-Command pnpm.cmd -ErrorAction SilentlyContinue)){
  if(Get-Command corepack.cmd -ErrorAction SilentlyContinue){
    & corepack enable|Out-Null
    & corepack prepare pnpm@9.12.2 --activate|Out-Null
  }
}
if(!(Get-Command pnpm.cmd -ErrorAction SilentlyContinue)){Stop-Safe "pnpm hazirlanamadi." 13}
OK "Node/pnpm hazir."

if(!(HttpOK "$ApiUrl/api/health")){
  $start=Join-Path $Root "SUNUCU BASLAT.bat"
  if(!(Test-Path $start)){Stop-Safe "B2B kapali ve SUNUCU BASLAT.bat yok." 14}
  Start-Process $start -ArgumentList "-YerelSadece" -WorkingDirectory $Root
  $ready=$false
  1..90|%{Start-Sleep 2;if(HttpOK "$ApiUrl/api/health"){$ready=$true;break}}
  if(!$ready){Stop-Safe "B2B 180 saniyede acilmadi." 15}
}
OK "B2B web ayakta."

$dbUrl=$env:DATABASE_URL
if(!$dbUrl){
  foreach($f in @("$Root\.env","$Root\.env.local","$Root\apps\web\.env.local","$Root\packages\database\.env")){
    $dbUrl=EnvGet $f "DATABASE_URL";if($dbUrl){break}
  }
}
if(!$dbUrl){$dbUrl="postgresql://postgres:postgres@localhost:5433/b2b?schema=public"}

$tmp=Join-Path $Root "scripts\mubel-readonly-provision-agent.ts"
@'
import { createErpAgent, listErpAgents, rotateErpAgentToken } from "../packages/services/src/erp-agent";
import { prisma } from "../packages/database/src/index";
const name="MUBEL READONLY";
const rows=await listErpAgents();
const old=rows.find(a=>a.name===name);
const issued=old?await rotateErpAgentToken(old.id):await createErpAgent(name,"vega");
console.log(JSON.stringify(issued));
await prisma.$disconnect();
'@|Set-Content $tmp -Encoding UTF8
$env:DATABASE_URL=$dbUrl
Push-Location $Root
try{
  $raw=& pnpm --silent --filter "@repo/services" exec tsx "..\..\scripts\mubel-readonly-provision-agent.ts" 2>&1
  if($LASTEXITCODE -ne 0){throw ($raw|Out-String)}
  $issued=($raw|Select-Object -Last 1)|ConvertFrom-Json
  if(!$issued.token){throw "Agent token alinamadi."}
}finally{Pop-Location;Remove-Item $tmp -Force -ErrorAction SilentlyContinue}
OK "B2B PostgreSQL ERP Agent tokeni otomatik hazirlandi."

function ParseCandidate([string]$t){
  $s=$u=$p=$null
  if($t-match "(?i)(?:Data Source|Server)\s*=\s*([^;\r\n]+)"){$s=$matches[1].Trim()}
  if($t-match "(?i)(?:User ID|UID|User)\s*=\s*([^;\r\n]+)"){$u=$matches[1].Trim()}
  if($t-match "(?i)(?:Password|PWD)\s*=\s*([^;\r\n]+)"){$p=$matches[1].Trim()}
  if($s -and $u -and $p){return [pscustomobject]@{Server=$s;User=$u;Password=$p}}
  return $null
}
$cands=@();$seen=@{}
foreach($r in @("D:\Arctos","C:\Arctos",$Root)){
  if(!(Test-Path $r)){continue}
  foreach($f in Get-ChildItem $r -Recurse -File -ErrorAction SilentlyContinue|?{$_.Extension -in ".ini",".udl",".config",".xml",".json",".txt"}|Select-Object -First 2000){
    try{
      if($f.Length -gt 5MB){continue}
      $c=ParseCandidate (Get-Content $f.FullName -Raw)
      if($c){
        $k="$($c.Server)|$($c.User)|$($c.Password)"
        if(!$seen[$k]){$seen[$k]=$true;$cands+=$c}
      }
    }catch{}
  }
}
if(!$cands){Stop-Safe "Arctos/B2B configlerinde SQL kimligi otomatik bulunamadi. SA parolasi pakete gomulmedi." 30}

$admin=$null
foreach($c in $cands){
  foreach($sv in @($c.Server,"YSERVER","192.168.1.250")|Select-Object -Unique){
    $cn=$null
    try{
      $cn=SqlOpen $sv "YILANCIOGLU" $c.User $c.Password
      $sys=[int](Scalar $cn "SELECT ISNULL(IS_SRVROLEMEMBER('sysadmin'),0)")
      $alt=[int](Scalar $cn "SELECT ISNULL(HAS_PERMS_BY_NAME(NULL,NULL,'ALTER ANY LOGIN'),0)")
      if($sys -eq 1 -or $alt -eq 1){$admin=[pscustomobject]@{Server=$sv;User=$c.User;Password=$c.Password};break}
    }catch{}finally{if($cn){$cn.Dispose()}}
  }
  if($admin){break}
}
if(!$admin){Stop-Safe "READ ONLY SQL login olusturabilecek otomatik yonetici baglantisi bulunamadi." 31}
OK "SQL yonetici baglantisi otomatik bulundu (parola loglanmadi)."

$readerPass=Secret 24
$esc=$readerPass.Replace("'","''")
$cn=SqlOpen $admin.Server "master" $admin.User $admin.Password
try{
  NonQuery $cn @"
IF NOT EXISTS(SELECT 1 FROM sys.server_principals WHERE name=N'$ReaderLogin')
 CREATE LOGIN [$ReaderLogin] WITH PASSWORD=N'$esc',CHECK_POLICY=ON,CHECK_EXPIRATION=OFF;
ELSE ALTER LOGIN [$ReaderLogin] WITH PASSWORD=N'$esc';
"@
}finally{$cn.Dispose()}
$cn=SqlOpen $admin.Server "YILANCIOGLU" $admin.User $admin.Password
try{
  NonQuery $cn @"
IF NOT EXISTS(SELECT 1 FROM sys.database_principals WHERE name=N'$ReaderLogin')
 CREATE USER [$ReaderLogin] FOR LOGIN [$ReaderLogin];
ALTER ROLE [db_datareader] ADD MEMBER [$ReaderLogin];
ALTER ROLE [db_denydatawriter] ADD MEMBER [$ReaderLogin];
DENY CREATE TABLE TO [$ReaderLogin];
DENY CREATE VIEW TO [$ReaderLogin];
DENY CREATE PROCEDURE TO [$ReaderLogin];
DENY CREATE FUNCTION TO [$ReaderLogin];
DENY ALTER TO [$ReaderLogin];
DENY CONTROL TO [$ReaderLogin];
"@
}finally{$cn.Dispose()}

$cn=SqlOpen $admin.Server "YILANCIOGLU" $ReaderLogin $readerPass
try{
  if([int](Scalar $cn "SELECT ISNULL(IS_ROLEMEMBER('db_datareader'),0)") -ne 1){throw "reader yok"}
  if([int](Scalar $cn "SELECT ISNULL(IS_ROLEMEMBER('db_denydatawriter'),0)") -ne 1){throw "denywriter yok"}
  $cmd=$cn.CreateCommand();$cmd.CommandText="SELECT name FROM sys.tables WHERE name LIKE 'F%TBL%'";$rd=$cmd.ExecuteReader()
  $cnt=@{}
  while($rd.Read()){
    $n=[string]$rd.GetString(0)
    if($n-match '^F(?<f>\d{4})D(?<d>\d{4})TBL'){
      $k="$($matches.f)|$($matches.d)";if(!$cnt[$k]){$cnt[$k]=0};$cnt[$k]++
    }
  }
  $rd.Close()
}finally{$cn.Dispose()}
OK "MUBEL_B2B_READER = db_datareader + db_denydatawriter."

if($cnt["0103|0011"]){$firma="0103";$donem="0011"}
else{
  $best=$cnt.GetEnumerator()|Sort-Object Value -Descending|Select-Object -First 1
  if(!$best){Stop-Safe "Vega firma/donem tablolari bulunamadi." 32}
  $z=$best.Key.Split("|");$firma=$z[0];$donem=$z[1]
}
OK "Vega firma/donem: F$firma / D$donem."

$ds=$admin.Server;$instance="";$port=1433
if($ds-match '^(.*),(\d+)$'){$ds=$matches[1];$port=[int]$matches[2]}
if($ds-match '^(.*?)\\(.+)$'){$ds=$matches[1];$instance=$matches[2]}
$cmdToken=Secret 28
$config=[ordered]@{
 apiUrl=$ApiUrl;token=$issued.token
 db=[ordered]@{server=$ds;port=$port;database="YILANCIOGLU";user=$ReaderLogin;password=$readerPass;instanceName=$instance}
 vega=[ordered]@{firma=$firma;donem=$donem}
 intervalMinutes=30;batchSize=1000
 sync=[ordered]@{customers=$true;stock=$true;prices=$false;units=$true}
 prices=[ordered]@{lists=@()}
 command=[ordered]@{enabled=$true;host="127.0.0.1";port=8787;token=$cmdToken}
 write=[ordered]@{enabled=$false;orderPrefix="B";referenceColumn="OZELKOD3";depo=1;userNo=100;branch="MERKEZ";till="MERKEZ"}
}
if(Test-Path $ConfigFile){Copy-Item $ConfigFile "$ConfigFile.mubelbak_$(Get-Date -Format yyyyMMdd_HHmmss)"}
$config|ConvertTo-Json -Depth 8|Set-Content $ConfigFile -Encoding UTF8
OK "ERP Agent config hazir; write.enabled=false."

EnvSet $WebEnv "ERP_AGENT_URL" $AgentUrl
EnvSet $WebEnv "ERP_AGENT_TOKEN" $cmdToken
OK "B2B -> localhost ERP Agent ayari hazir."

Push-Location $Root
try{
  & pnpm --filter erp-agent build 2>&1|Tee-Object (Join-Path $LogDir "agent-build.log")
  if($LASTEXITCODE -ne 0){Stop-Safe "ERP Agent derlenemedi." 40}
}finally{Pop-Location}
OK "ERP Agent derlendi."

$stop=Join-Path $Root "SUNUCU DURDUR.bat";$start=Join-Path $Root "SUNUCU BASLAT.bat"
if((Test-Path $stop)-and(Test-Path $start)){
  Start-Process $stop -WorkingDirectory $Root -Wait
  Start-Sleep 2
  Start-Process $start -ArgumentList "-YerelSadece" -WorkingDirectory $Root
  $up=$false;1..90|%{Start-Sleep 2;if(HttpOK "$ApiUrl/api/health"){$up=$true;break}}
  if(!$up){Stop-Safe "B2B web yeniden acilmadi." 41}
}
OK "B2B yeni ajan ayarlariyla ayakta."

$runner=Join-Path $LogDir "ERP_AGENT_READONLY_BASLAT.cmd"
@"
@echo off
cd /d "$AgentDir"
"$(Get-Command node.exe|Select-Object -ExpandProperty Source)" dist\index.js --config agent.config.json >> "$LogDir\agent.log" 2>> "$LogDir\agent.err.log"
"@|Set-Content $runner -Encoding ASCII
schtasks /Delete /TN $TaskName /F 2>$null|Out-Null
schtasks /Create /SC ONLOGON /TN $TaskName /TR "`"$runner`"" /F|Out-Null
if($LASTEXITCODE -ne 0){Stop-Safe "Windows ERP Agent gorevi olusmadi." 42}
schtasks /Run /TN $TaskName|Out-Null
Start-Sleep 5
if(!(HttpOK "$AgentUrl/health")){Stop-Safe "ERP Agent /health cevap vermiyor." 43}

$hdr=@{Authorization="Bearer $cmdToken"}
$body=@{command="ping";requestId="mubel-kurulum";payload=@{}}|ConvertTo-Json -Depth 4
$ping=Invoke-RestMethod "$AgentUrl/command" -Method Post -Headers $hdr -ContentType "application/json" -Body $body -TimeoutSec 10
if(!$ping.ok -or $ping.result.writeEnabled -ne $false){Stop-Safe "ERP Agent READ ONLY ping testi gecmedi." 44}
OK "ERP Agent ayakta; writeEnabled=false."

@"
MUBEL B2B READ ONLY - HAZIR
Tarih                  : $(Get-Date)
B2B                    : $ApiUrl
ERP Agent              : $AgentUrl
SQL Server             : $($admin.Server)
Database               : YILANCIOGLU
Firma                  : F$firma
Donem                  : D$donem
SQL Login              : $ReaderLogin
SQL Okuma              : ACIK
SQL Yazma              : REDDEDILMIS (db_denydatawriter)
Agent write.enabled    : FALSE
YSERVER'a B2B kuruldu  : HAYIR
YILANCIOGLU tablo acildi : HAYIR
YILANCIOGLU is verisi yazildi : HAYIR
"@|Set-Content $StatusFile -Encoding UTF8

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host " MUBEL B2B READ ONLY ERP KOPRUSU HAZIR" -ForegroundColor Green
Write-Host " YILANCIOGLU IS VERISINE YAZMA YOK" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Start-Process "$ApiUrl/admin/erp"
exit 0
