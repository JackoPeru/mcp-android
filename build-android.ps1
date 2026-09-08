$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw 'Android SDK non trovato. Imposta ANDROID_HOME.' }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $env:JAVA_HOME = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
    } else {
        $candidates = @(
            (Join-Path $PSScriptRoot '.tools/jdk17'),
            (Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'),
            (Join-Path $env:LOCALAPPDATA 'Programs/Android/Android Studio/jbr'),
            (Join-Path $env:USERPROFILE '.jdks'),
            (Join-Path $env:USERPROFILE '.gradle/jdks')
        )
        $found = $null
        foreach ($candidate in $candidates) {
            if (-not (Test-Path -LiteralPath $candidate)) { continue }
            $java = Get-ChildItem -LiteralPath $candidate -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($java) {
                $found = Split-Path (Split-Path $java.FullName -Parent) -Parent
                break
            }
        }
        if (-not $found) { throw 'JDK non trovato. Installa JDK 17+ oppure imposta JAVA_HOME.' }
        $env:JAVA_HOME = $found
    }
}
Push-Location (Join-Path $PSScriptRoot 'android')
try {
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build o verifica Android fallita.' }
    $deliveryDirectory = Join-Path $PSScriptRoot 'dist'
    New-Item -ItemType Directory -Force -Path $deliveryDirectory | Out-Null
    $apkPath = Join-Path $deliveryDirectory 'mcp-android-0.6.0-debug.apk'
    Copy-Item -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk' -Destination $apkPath
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash.ToLowerInvariant()
    "$hash  mcp-android-0.6.0-debug.apk" | Set-Content -Encoding ascii -LiteralPath "$apkPath.sha256"
    Write-Output "APK verificato: $apkPath"
} finally { Pop-Location }
