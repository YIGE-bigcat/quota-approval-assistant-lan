$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidPath = Join-Path $ProjectDir "data\bridge.pid"
$config = Get-Content -Raw (Join-Path $ProjectDir "config.local.json") | ConvertFrom-Json
$bridgePid = if (Test-Path $PidPath) { Get-Content $PidPath } else { $null }
if (-not $bridgePid -or -not (Get-Process -Id $bridgePid -ErrorAction SilentlyContinue)) {
  $line = netstat -ano -p tcp | Select-String (":$($config.port)\s+.*LISTENING\s+(\d+)\s*$") | Select-Object -First 1
  if ($line -and $line.Matches.Count) { $bridgePid = [int]$line.Matches[0].Groups[1].Value }
}
if (-not $bridgePid) { Write-Host "Bridge is not running."; exit 0 }
$process = Get-Process -Id $bridgePid -ErrorAction SilentlyContinue
if ($process) { Stop-Process -Id $bridgePid }
Remove-Item -LiteralPath $PidPath -ErrorAction SilentlyContinue
Write-Host "Stopped Codex Watch Bridge."
