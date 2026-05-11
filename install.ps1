$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkDir = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$Adb = Join-Path $SdkDir "platform-tools\adb.exe"
$Apk = Join-Path $ProjectDir "build\outputs\GarminMapShare-debug.apk"

if (!(Test-Path $Adb)) { throw "Missing adb: $Adb" }
if (!(Test-Path $Apk)) { throw "Missing APK. Run .\build.ps1 first: $Apk" }

& $Adb install -r $Apk
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}
