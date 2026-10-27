# Builds an (unsigned) MSIX for Microsoft Store upload from dist/windows (hypurr.exe, hypurr-host.exe).
#   build.ps1 -Version 2.6.3 -SrcDir dist\windows -Out dist\Hypurr-2.6.3.msix
# Store identity comes from STORE_IDENTITY_NAME / STORE_PUBLISHER / STORE_PUBLISHER_DISPLAY
# (repo variables, not secrets; copy them from Partner Center). Without them a TEST identity is used.
param([Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)][string]$SrcDir, [Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$name = if ($env:STORE_IDENTITY_NAME) { $env:STORE_IDENTITY_NAME } else { 'TEST.Hypurr.NotForStore' }
$pub = if ($env:STORE_PUBLISHER) { $env:STORE_PUBLISHER } else { 'CN=TEST-Hypurr-NotForStore' }
$pubName = if ($env:STORE_PUBLISHER_DISPLAY) { $env:STORE_PUBLISHER_DISPLAY } else { 'TEST publisher (set STORE_PUBLISHER_DISPLAY)' }
if (-not $env:STORE_IDENTITY_NAME) { Write-Host "::warning::STORE_* variables not set: building a TEST-identity MSIX (not for Store upload)." }
$v = ($Version -split '[-+]')[0]
if (($v -split '\.').Count -ne 3) { throw "version must be x.y.z, got $Version" }
$stage = Join-Path $env:RUNNER_TEMP 'msix-stage'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "$SrcDir\*" $stage -Recurse
Copy-Item "$here\Assets" $stage -Recurse
(Get-Content "$here\AppxManifest.xml" -Raw).
  Replace('__STORE_IDENTITY_NAME__', $name).Replace('__STORE_PUBLISHER_DISPLAY__', [Security.SecurityElement]::Escape($pubName)).
  Replace('__STORE_PUBLISHER__', [Security.SecurityElement]::Escape($pub)).Replace('__VERSION__', "$v.0") |
  Set-Content "$stage\AppxManifest.xml" -Encoding utf8
$makeappx = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\makeappx.exe" | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $makeappx) { throw "makeappx.exe not found (Windows SDK)" }
& $makeappx.FullName pack /o /d $stage /p $Out
if ($LASTEXITCODE -ne 0) { throw "makeappx failed" }
Write-Host "MSIX: $Out"
