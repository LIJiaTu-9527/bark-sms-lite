param([string]$Toolchain = "$env:USERPROFILE\.cache\bark-sms-toolchain")
$ErrorActionPreference = 'Stop'
$jdk = (Get-ChildItem -LiteralPath $Toolchain -Directory | Where-Object {
    Test-Path (Join-Path $_.FullName 'bin\javac.exe')
} | Select-Object -First 1).FullName
$project = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $project '.build\host-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$json = Join-Path $Toolchain 'json.jar'
if (!(Test-Path -LiteralPath $json)) { throw 'Host tests need org.json:json:20240303 (not bundled in APK).' }
& "$jdk\bin\javac.exe" -encoding UTF-8 --release 8 -cp $json -d $classes `
    "$project\src\local\barksms\Policy.java" "$project\src\local\barksms\BarkClient.java" "$PSScriptRoot\CoreTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& "$jdk\bin\java.exe" -cp "$classes;$json" local.barksms.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
