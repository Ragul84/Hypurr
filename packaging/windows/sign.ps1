# Authenticode-signs the given files with a PFX from CI secrets. Skips (exit 0) when no cert is set,
# so forks and unsigned test builds keep working.
#   WINDOWS_CERT_PFX_BASE64  base64 of the .pfx   WINDOWS_CERT_PASSWORD  its password
param([Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Files)
$ErrorActionPreference = 'Stop'
if (-not $env:WINDOWS_CERT_PFX_BASE64) { Write-Host "No WINDOWS_CERT_PFX_BASE64 secret: skipping code signing."; exit 0 }
$pfx = Join-Path $env:RUNNER_TEMP 'hypurr-sign.pfx'
[IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:WINDOWS_CERT_PFX_BASE64))
try {
  $signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" | Sort-Object FullName -Descending | Select-Object -First 1
  if (-not $signtool) { throw "signtool.exe not found" }
  foreach ($f in $Files) {
    & $signtool.FullName sign /fd SHA256 /td SHA256 /tr http://timestamp.digicert.com /f $pfx /p $env:WINDOWS_CERT_PASSWORD /d Hypurr $f
    if ($LASTEXITCODE -ne 0) { throw "signing failed: $f" }
    & $signtool.FullName verify /pa $f
    if ($LASTEXITCODE -ne 0) { throw "signature does not verify: $f" }
  }
} finally { Remove-Item $pfx -Force -ErrorAction SilentlyContinue }
