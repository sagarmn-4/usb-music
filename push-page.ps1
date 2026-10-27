# Sends app/src/main/assets/index.html to the TV so the phone page updates without reinstalling.
# Usage: .\push-page.ps1            (push)    .\push-page.ps1 -Reset   (go back to the built-in page)
# Needs the debug build of the app (run-as only works on debuggable apps).
param(
    [string]$Tv = '192.168.1.6',
    [switch]$Reset
)
$adb = Join-Path $PSScriptRoot '..\tools\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$pkg = 'com.sagar.usbmusic'
& $adb connect "${Tv}:5555" | Out-Null
if ($Reset) {
    & $adb shell run-as $pkg rm -f files/index.html
    Write-Host 'Back to the page built into the app.'
} else {
    & $adb push (Join-Path $PSScriptRoot 'app\src\main\assets\index.html') /data/local/tmp/usbmusic-index.html | Out-Null
    & $adb shell run-as $pkg mkdir -p files
    & $adb shell run-as $pkg cp /data/local/tmp/usbmusic-index.html files/index.html
    & $adb shell rm -f /data/local/tmp/usbmusic-index.html
    Write-Host "Done. Reload http://${Tv}:8080 on the phone."
}
