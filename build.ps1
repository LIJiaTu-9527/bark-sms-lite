param(
    [string]$Toolchain = "$env:USERPROFILE\.cache\bark-sms-toolchain",
    [string]$Jdk = '',
    [string]$AndroidJar = '',
    [string]$BuildTools = ''
)
$ErrorActionPreference = 'Stop'
if (!$Jdk) {
    $Jdk = (Get-ChildItem -LiteralPath $Toolchain -Directory | Where-Object {
        Test-Path (Join-Path $_.FullName 'bin\javac.exe')
    } | Select-Object -First 1).FullName
}
if (!$AndroidJar) { $AndroidJar = Join-Path $Toolchain 'android-9\android.jar' }
if (!$BuildTools) { $BuildTools = Join-Path $Toolchain 'android-15' }
foreach ($file in @("$Jdk\bin\javac.exe", $AndroidJar, "$BuildTools\aapt2.exe", "$BuildTools\lib\d8.jar")) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing build dependency: $file" }
}
function Invoke-Native([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed ($LASTEXITCODE)" }
}
# Android's Windows packaging tools do not reliably accept non-ASCII paths.
$work = Join-Path $Toolchain ('build-' + [Guid]::NewGuid().ToString('N'))
$output = Join-Path $PSScriptRoot 'dist'
$signing = Join-Path $PSScriptRoot '.local'
foreach ($directory in @($work, "$work\classes", "$work\generated", "$work\dex", $output, $signing)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
Copy-Item -LiteralPath "$PSScriptRoot\res" -Destination "$work\res" -Recurse
Copy-Item -LiteralPath "$PSScriptRoot\src" -Destination "$work\src" -Recurse
Copy-Item -LiteralPath "$PSScriptRoot\AndroidManifest.xml" -Destination "$work\AndroidManifest.xml"
Invoke-Native "$BuildTools\aapt2.exe" @('compile', '--dir', "$work\res", '-o', "$work\resources.zip")
Invoke-Native "$BuildTools\aapt2.exe" @('link', '-I', $AndroidJar, '--manifest', "$work\AndroidManifest.xml",
    '--java', "$work\generated", '-o', "$work\unsigned.apk", "$work\resources.zip")
$sources = @(Get-ChildItem "$work\src", "$work\generated" -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Native "$Jdk\bin\javac.exe" (@('-encoding', 'UTF-8', '--release', '8',
    '-classpath', $AndroidJar, '-d', "$work\classes") + $sources)
Invoke-Native "$Jdk\bin\jar.exe" @('cf', "$work\classes.jar", '-C', "$work\classes", '.')
Invoke-Native "$Jdk\bin\java.exe" @('-cp', "$BuildTools\lib\d8.jar", 'com.android.tools.r8.D8',
    '--release', '--min-api', '26', '--lib', $AndroidJar, '--output', "$work\dex", "$work\classes.jar")
Invoke-Native "$Jdk\bin\jar.exe" @('uf', "$work\unsigned.apk", '-C', "$work\dex", 'classes.dex')
Invoke-Native "$BuildTools\zipalign.exe" @('-p', '-f', '4', "$work\unsigned.apk", "$work\aligned.apk")
$key = Join-Path $signing 'release.p12'
$password = Join-Path $signing 'password.txt'
if (!(Test-Path -LiteralPath $key)) {
    if (!(Test-Path -LiteralPath $password)) {
        $random = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($random) } finally { $rng.Dispose() }
        [IO.File]::WriteAllText($password, [Convert]::ToBase64String($random), [Text.Encoding]::ASCII)
    }
    Invoke-Native "$Jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $key, '-storetype', 'PKCS12',
        '-storepass:file', $password, '-keypass:file', $password, '-alias', 'bark-sms', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-dname', 'CN=BarkSms Local')
}
$apk = Join-Path $output 'BarkSms-1.3.apk'
$stagedApk = Join-Path $work 'BarkSms-1.3.apk'
Invoke-Native "$Jdk\bin\java.exe" @('-jar', "$BuildTools\lib\apksigner.jar", 'sign',
    '--ks', $key, '--ks-pass', "file:$password", '--ks-key-alias', 'bark-sms',
    '--v4-signing-enabled', 'false', '--out', $stagedApk, "$work\aligned.apk")
Invoke-Native "$Jdk\bin\java.exe" @('-jar', "$BuildTools\lib\apksigner.jar", 'verify', '--verbose', $stagedApk)
Invoke-Native "$BuildTools\zipalign.exe" @('-c', '4', $stagedApk)
Copy-Item -LiteralPath $stagedApk -Destination $apk
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$apk.sha256", "$hash  BarkSms-1.3.apk`n", [Text.Encoding]::ASCII)
Get-Item -LiteralPath $apk | Select-Object FullName, Length
