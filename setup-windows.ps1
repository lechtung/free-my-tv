# setup-windows.ps1 - Automated build environment setup for Windows
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

Write-Host "=== 1/5. Installing base packages via WinGet ===" -ForegroundColor Cyan
$packages = @(
    "Git.Git",
    "EclipseAdoptium.Temurin.17.JDK",
    "Google.AndroidCLI",
    "Google.PlatformTools"
)

foreach ($pkg in $packages) {
    Write-Host "Checking/installing: $pkg..." -ForegroundColor Yellow
    winget install --id $pkg -e --source winget --accept-package-agreements --accept-source-agreements --silent
}

Write-Host "`n=== 2/5. Reloading environment paths in memory ===" -ForegroundColor Cyan
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
$env:LOCALAPPDATA = [System.Environment]::GetFolderPath("LocalApplicationData")

Write-Host "`n=== 3/5. Downloading Android SDK 34 and tools ===" -ForegroundColor Cyan
$sdkManager = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat"

if (-not (Test-Path $sdkManager)) {
    throw "sdkmanager.bat not found at: $sdkManager"
}

# Automatically accept licenses via pipe
Write-Host "Accepting licenses..." -ForegroundColor Yellow
cmd.exe /c "echo y | `"$sdkManager`" --licenses" | Out-Null

Write-Host "Installing platforms;android-34, build-tools;34.0.0, and platform-tools..." -ForegroundColor Yellow
& $sdkManager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

Write-Host "`n=== 4/5. Configuring persistent environment variables ===" -ForegroundColor Cyan
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", "$env:LOCALAPPDATA\Android\Sdk", "User")

# Detect JDK 17 installation path
$jdkDir = (Get-ChildItem -Path "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java" -Filter "jdk-17*" -Directory -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if ($jdkDir) {
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkDir, "User")
    Write-Host "JAVA_HOME configured to: $jdkDir" -ForegroundColor Green
}

# Register paths in User PATH
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$extraPaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools",
    "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin",
    "$env:LOCALAPPDATA\Android\Sdk\build-tools\34.0.0"
)

foreach ($p in $extraPaths) {
    if ($userPath -notlike "*$p*") {
        $userPath = "$p;$userPath"
    }
}
[Environment]::SetEnvironmentVariable("Path", $userPath, "User")

Write-Host "`n=== 5/5. Environment successfully configured ===" -ForegroundColor Green
Write-Host "Setup completed. Close this window and open a new terminal to start building." -ForegroundColor Green
