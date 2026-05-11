$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkDir = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$BuildTools = Join-Path $SdkDir "build-tools\36.1.0"
$Platform = Join-Path $SdkDir "platforms\android-36.1\android.jar"
$JbrBin = "C:\Program Files\Android\Android Studio\jbr\bin"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$Javac = Join-Path $JbrBin "javac.exe"
$Jar = Join-Path $JbrBin "jar.exe"

$BuildDir = Join-Path $ProjectDir "build"
$ObjDir = Join-Path $BuildDir "obj"
$ClassesDir = Join-Path $BuildDir "classes"
$DexDir = Join-Path $BuildDir "dex"
$OutDir = Join-Path $BuildDir "outputs"
$Manifest = Join-Path $ProjectDir "AndroidManifest.xml"
$UnsignedApk = Join-Path $OutDir "GarminMapShare-unsigned.apk"
$AlignedApk = Join-Path $OutDir "GarminMapShare-aligned.apk"
$SignedApk = Join-Path $OutDir "GarminMapShare-debug.apk"
$Keystore = Join-Path $ProjectDir "debug.keystore"
$CompilePlatform = Join-Path $BuildDir "android.jar"

if (!(Test-Path $Aapt2)) { throw "Missing aapt2: $Aapt2" }
if (!(Test-Path $D8)) { throw "Missing d8: $D8" }
if (!(Test-Path $ZipAlign)) { throw "Missing zipalign: $ZipAlign" }
if (!(Test-Path $ApkSigner)) { throw "Missing apksigner: $ApkSigner" }
if (!(Test-Path $Platform)) { throw "Missing android.jar: $Platform" }
if (!(Test-Path $Javac)) { throw "Missing javac: $Javac" }
if (!(Test-Path $Jar)) { throw "Missing jar: $Jar" }

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)]
        [string] $File,
        [string[]] $Arguments = @()
    )

    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File failed with exit code $LASTEXITCODE"
    }
}

Remove-Item -Recurse -Force $BuildDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $ObjDir, $ClassesDir, $DexDir, $OutDir | Out-Null
Copy-Item -LiteralPath $Platform -Destination $CompilePlatform

Invoke-Native $Aapt2 @(
    "link",
    "-I", $Platform,
    "--manifest", $Manifest,
    "--java", $ObjDir,
    "-o", $UnsignedApk,
    "--min-sdk-version", "23",
    "--target-sdk-version", "36"
)

$Sources = Get-ChildItem -Path (Join-Path $ProjectDir "src"), $ObjDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Invoke-Native $Javac (@("-encoding", "UTF-8", "-source", "8", "-target", "8", "-bootclasspath", $CompilePlatform, "-d", $ClassesDir) + $Sources)

$ClassFiles = Get-ChildItem -Path $ClassesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Invoke-Native $D8 (@("--lib", $Platform, "--output", $DexDir) + $ClassFiles)

Push-Location $DexDir
try {
    Invoke-Native $Jar @("uf", $UnsignedApk, "classes.dex")
} finally {
    Pop-Location
}

Invoke-Native $ZipAlign @("-f", "4", $UnsignedApk, $AlignedApk)

if (!(Test-Path $Keystore)) {
    Invoke-Native (Join-Path $JbrBin "keytool.exe") @(
        "-genkeypair",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US"
    )
}

Invoke-Native $ApkSigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $SignedApk,
    $AlignedApk
)

Invoke-Native $ApkSigner @("verify", $SignedApk)

Write-Host "Built APK: $SignedApk"
