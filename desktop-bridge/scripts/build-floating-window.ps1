param(
  [switch]$RenderPreview,
  [string]$PreviewPath = (Join-Path $PSScriptRoot '..\docs\desktop-widget-preview.png')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing, System.Windows.Forms

$source = Join-Path $PSScriptRoot 'QuotaFloatingWindow.cs'
$output = Join-Path $PSScriptRoot 'QuotaFloatingWindow.exe'
$pidPath = Join-Path $PSScriptRoot '..\data\desktop-widget.pid'

if (Test-Path -LiteralPath $pidPath) {
  $widgetPid = Get-Content -LiteralPath $pidPath -Raw
  if ($widgetPid -match '^\s*(\d+)\s*$') {
    Stop-Process -Id $Matches[1] -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
  }
  Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
}

Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue

Add-Type -LiteralPath $source -ReferencedAssemblies @(
  [System.Drawing.Graphics].Assembly.Location,
  [System.Windows.Forms.Form].Assembly.Location
) -OutputAssembly $output -OutputType WindowsApplication

if ($RenderPreview) {
  $directory = Split-Path -Parent $PreviewPath
  New-Item -ItemType Directory -Path $directory -Force | Out-Null
  $process = Start-Process -FilePath $output -ArgumentList @('--render-demo', $PreviewPath) -Wait -PassThru
  if ($process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $PreviewPath)) {
    throw 'Desktop widget preview could not be rendered.'
  }
}

Write-Host "Built $output"
