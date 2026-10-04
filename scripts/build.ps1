$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
    & .\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    Write-Output 'Unsigned release APK: app/build/outputs/apk/release/app-release-unsigned.apk'
} finally { Pop-Location }
