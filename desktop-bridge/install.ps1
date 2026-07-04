param(
  [string]$NtfyTopic = "",
  [string]$PublicBaseUrl = "",
  [switch]$RegisterStartup
)

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CodexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE ".codex" }
$ConfigPath = Join-Path $ProjectDir "config.local.json"
$CodexConfigPath = Join-Path $CodexHome "config.toml"
$HooksPath = Join-Path $CodexHome "hooks.json"
$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

function New-Secret {
  $bytes = New-Object byte[] 32
  $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
  return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Test-NotifyCommand($Value) {
  $items = @($Value)
  return $items.Count -gt 0 -and $items[0] -is [string]
}

if (Test-Path $ConfigPath) {
  $bridgeConfig = Get-Content -Raw $ConfigPath | ConvertFrom-Json
} else {
  $topic = if ($NtfyTopic) { $NtfyTopic } else { "codex-watch-$(New-Secret)".Substring(0, 36) }
  $bridgeConfig = [ordered]@{
    port = 8787
    bind = "0.0.0.0"
    publicBaseUrl = $PublicBaseUrl
    bridgeToken = New-Secret
    internalSecret = New-Secret
    approvalTimeoutSeconds = 300
    previousNotify = @()
    ntfy = [ordered]@{
      enabled = [bool]$NtfyTopic
      baseUrl = "https://ntfy.sh"
      topic = $topic
    }
  }
}

if (-not ($bridgeConfig.PSObject.Properties.Name -contains "previousNotify")) {
  $bridgeConfig | Add-Member -NotePropertyName previousNotify -NotePropertyValue ([object[]]@())
} elseif (-not (Test-NotifyCommand $bridgeConfig.previousNotify)) {
  $bridgeConfig.previousNotify = [object[]]@()
}

if ($NtfyTopic) {
  $bridgeConfig.ntfy.enabled = $true
  $bridgeConfig.ntfy.topic = $NtfyTopic
}
if ($PublicBaseUrl) { $bridgeConfig.publicBaseUrl = $PublicBaseUrl }

if (Test-Path $CodexConfigPath) {
  Copy-Item -LiteralPath $CodexConfigPath -Destination "$CodexConfigPath.watch-backup-$Timestamp"
  $toml = Get-Content -Raw $CodexConfigPath
  $notifyMatch = [regex]::Match($toml, '(?m)^notify\s*=\s*(\[[^\r\n]+\])\s*$')
  if ($notifyMatch.Success -and -not $notifyMatch.Value.Contains("notify-dispatcher.mjs")) {
    try { $bridgeConfig.previousNotify = [object[]](ConvertFrom-Json -InputObject $notifyMatch.Groups[1].Value) } catch {}
  } elseif (@($bridgeConfig.previousNotify).Count -eq 0) {
    $backups = Get-ChildItem $CodexHome -Filter "config.toml.watch-backup-*" |
      Sort-Object LastWriteTime -Descending
    foreach ($backup in $backups) {
      $backupToml = Get-Content -Raw $backup.FullName
      $backupNotify = [regex]::Match($backupToml, '(?m)^notify\s*=\s*(\[[^\r\n]+\])\s*$')
      if ($backupNotify.Success -and -not $backupNotify.Value.Contains("notify-dispatcher.mjs")) {
        try {
          $bridgeConfig.previousNotify = [object[]](ConvertFrom-Json -InputObject $backupNotify.Groups[1].Value)
          break
        } catch {}
      }
    }
  }

  $dispatcher = (Join-Path $ProjectDir "hooks\notify-dispatcher.mjs").Replace("\", "\\")
  $notifyLine = "notify = [ `"node`", `"$dispatcher`" ]"
  if ($notifyMatch.Success) {
    $toml = $toml.Remove($notifyMatch.Index, $notifyMatch.Length).Insert($notifyMatch.Index, $notifyLine)
  } else {
    $toml = "$notifyLine`r`n$toml"
  }
  [IO.File]::WriteAllText($CodexConfigPath, $toml, [Text.UTF8Encoding]::new($false))
}

if (Test-Path $HooksPath) {
  Copy-Item -LiteralPath $HooksPath -Destination "$HooksPath.watch-backup-$Timestamp"
  $hooksDocument = Get-Content -Raw $HooksPath | ConvertFrom-Json
} else {
  $hooksDocument = [pscustomobject]@{ hooks = [pscustomobject]@{} }
}
if (-not ($hooksDocument.PSObject.Properties.Name -contains "hooks")) {
  $hooksDocument | Add-Member -NotePropertyName hooks -NotePropertyValue ([pscustomobject]@{})
}

$permissionScript = Join-Path $ProjectDir "hooks\permission-request.mjs"
$command = "node `"$permissionScript`""
$existing = if ($hooksDocument.hooks.PSObject.Properties.Name -contains "PermissionRequest") {
  @($hooksDocument.hooks.PermissionRequest)
} else {
  @()
}
$alreadyInstalled = $false
foreach ($entry in $existing) {
  foreach ($hook in @($entry.hooks)) {
    if ($hook.command -eq $command) {
      $alreadyInstalled = $true
      $entry.matcher = "*"
    }
  }
}
if (-not $alreadyInstalled) {
  $existing += @{
    matcher = "*"
    hooks = @(@{ type = "command"; command = $command; timeout = 330 })
  }
}
if ($hooksDocument.hooks.PSObject.Properties.Name -contains "PermissionRequest") {
  $hooksDocument.hooks.PermissionRequest = [object[]]@($existing)
} else {
  $hooksDocument.hooks | Add-Member -NotePropertyName PermissionRequest -NotePropertyValue ([object[]]@($existing))
}
[IO.File]::WriteAllText($HooksPath, ($hooksDocument | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($ConfigPath, ($bridgeConfig | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))

if ($RegisterStartup) {
  try {
    $taskName = "Codex Watch Bridge"
    $node = (Get-Command node).Source
    $server = Join-Path $ProjectDir "src\server.mjs"
    $action = New-ScheduledTaskAction -Execute $node -Argument "`"$server`"" -WorkingDirectory $ProjectDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Description "Codex notifications and approvals bridge" -Force -ErrorAction Stop | Out-Null
    Write-Host "Startup: scheduled task"
  } catch {
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    $startScript = Join-Path $ProjectDir "start.ps1"
    $runCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$startScript`""
    New-Item -Path $runKey -Force | Out-Null
    New-ItemProperty -Path $runKey -Name "CodexWatchBridge" -Value $runCommand -PropertyType String -Force | Out-Null
    Write-Host "Startup: current-user Run entry"
  }
}

Write-Host "Installed Codex Watch Bridge."
Write-Host "Restart Codex, then run: .\start.ps1"
Write-Host "Dashboard token: $($bridgeConfig.bridgeToken)"
Write-Host "ntfy topic: $($bridgeConfig.ntfy.topic) (enabled=$($bridgeConfig.ntfy.enabled))"
