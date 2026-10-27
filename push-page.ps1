# Sends app/src/main/assets/index.html to the TV so the phone page updates without reinstalling.
# Usage: .\push-page.ps1            (push)    .\push-page.ps1 -Reset   (go back to the built-in page)
param(
    [string]$Tv = '192.168.1.6',
    [switch]$Reset
)
$adb = Join-Path $PSScriptRoot '..\tools\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$remote = '/sdcard/Android/data/com.sagar.usbmusic/files/index.html'
& $adb connect "${Tv}:5555" | Out-Null
if ($Reset) {
    & $adb shell rm -f $remote
    Write-Host 'Back to the page built into the app.'
} else {
    & $adb push (Join-Path $PSScriptRoot 'app\src\main\assets\index.html') $remote
    Write-Host "Done. Reload http://${Tv}:8080 on the phone."
}
