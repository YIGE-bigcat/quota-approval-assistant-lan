$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidPath = Join-Path $ProjectDir "data\bridge.pid"
New-Item -ItemType Directory -Force (Split-Path -Parent $PidPath) | Out-Null
$config = Get-Content -Raw (Join-Path $ProjectDir "config.local.json") | ConvertFrom-Json

function Get-BridgeProcessId([int]$Port) {
  $line = netstat -ano -p tcp | Select-String (":$Port\s+.*LISTENING\s+(\d+)\s*$") | Select-Object -First 1
  if ($line -and $line.Matches.Count) { return [int]$line.Matches[0].Groups[1].Value }
  return $null
}

try {
  $health = Invoke-RestMethod "http://127.0.0.1:$($config.port)/health" -TimeoutSec 2
  if ($health.ok) {
    $runningPid = Get-BridgeProcessId $config.port
    if ($runningPid) { [IO.File]::WriteAllText($PidPath, [string]$runningPid) }
    Write-Host "Codex Watch Bridge is already running$(if ($runningPid) { " (PID $runningPid)" })."
    exit 0
  }
} catch {}

if (Test-Path $PidPath) {
  $oldPid = Get-Content $PidPath -ErrorAction SilentlyContinue
  if ($oldPid -and (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
    Write-Host "Codex Watch Bridge is already running (PID $oldPid)."
    exit 0
  }
}

$process = Start-Process -FilePath node -ArgumentList "--no-warnings", "src/server.mjs" -WorkingDirectory $ProjectDir -WindowStyle Hidden -PassThru
[IO.File]::WriteAllText($PidPath, [string]$process.Id)
Start-Sleep -Milliseconds 700
if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
  Remove-Item -LiteralPath $PidPath -ErrorAction SilentlyContinue
  throw "Codex Watch Bridge exited during startup. Run 'node src/server.mjs' to inspect the error."
}

$address = [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName()) |
  Where-Object { $_.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and -not $_.IPAddressToString.StartsWith("127.") } |
  Select-Object -First 1 |
  ForEach-Object { $_.IPAddressToString }
if (-not $address) { $address = "127.0.0.1" }
$baseUrl = if ($config.publicBaseUrl) { $config.publicBaseUrl.TrimEnd("/") } else { "http://${address}:$($config.port)" }
Write-Host "Running (PID $($process.Id))"
Write-Host "Dashboard: $baseUrl/?token=$($config.bridgeToken)"
Write-Host "Health: http://127.0.0.1:$($config.port)/health"
