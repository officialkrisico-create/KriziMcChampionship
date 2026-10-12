# Builds everything and copies the plugin jars to your server.
#
#   .\deploy.ps1 -Plugins "C:\server\plugins"            # build + copy
#   .\deploy.ps1 -Plugins "C:\server\plugins" -NoBuild   # only copy the jars that are already built
#   .\deploy.ps1 -Plugins "C:\server\plugins" -WhatIf    # show what would be copied
#
# Only modules that have a plugin.yml are copied: libraries such as kmc-storage stay out of plugins/.
param(
    [Parameter(Mandatory = $true)][string]$Plugins,
    [switch]$NoBuild,
    [switch]$WhatIf,
    [string]$Mvn = "mvn"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path $Plugins)) { throw "Plugins folder not found: $Plugins" }

if (-not $NoBuild) {
    Write-Host "[KMC] Building all modules..." -ForegroundColor Cyan
    & $Mvn -B clean package
    if ($LASTEXITCODE -ne 0) { throw "Build failed - nothing copied." }
}

$copied = 0
foreach ($dir in Get-ChildItem -Directory) {
    if (-not (Test-Path (Join-Path $dir.FullName "src\main\resources\plugin.yml"))) { continue }
    $jar = Get-ChildItem (Join-Path $dir.FullName "target") -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "original-*" -and $_.Name -notlike "*-sources.jar" } |
        Select-Object -First 1
    if (-not $jar) { Write-Host "  - $($dir.Name): no jar (build it first)" -ForegroundColor Yellow; continue }
    if ($WhatIf) { Write-Host "  would copy $($jar.Name)"; $copied++; continue }
    Copy-Item $jar.FullName -Destination $Plugins -Force
    Write-Host "  + $($jar.Name)"
    $copied++
}
Write-Host "[KMC] $copied jars -> $Plugins" -ForegroundColor Green
Write-Host "Restart the server (plugins are not safe to reload)."
