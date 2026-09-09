$ErrorActionPreference = 'Stop'

$package = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'package.json') | ConvertFrom-Json
$version = [string]$package.version
$tag = "v$version"
$env:RELEASE_TAG = $tag

function Get-AndroidVersionCode([string]$gradleText) {
    $match = [regex]::Match($gradleText, '(?m)^\s*versionCode\s+(\d+)\s*$')
    if (-not $match.Success) { throw 'versionCode Android non trovato.' }
    return [long]$match.Groups[1].Value
}

Push-Location $PSScriptRoot
try {
    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') {
        throw 'La release locale può essere pubblicata solo dal branch main.'
    }
    $dirty = (& git status --porcelain)
    if ($LASTEXITCODE -ne 0 -or $dirty) {
        throw 'Working tree non pulito. Committa o annulla le modifiche prima della release.'
    }
    & git fetch origin main
    if ($LASTEXITCODE -ne 0) { throw 'Impossibile aggiornare origin/main.' }
    & git fetch --tags origin
    if ($LASTEXITCODE -ne 0) { throw 'Impossibile aggiornare i tag release.' }
    $head = (& git rev-parse HEAD).Trim()
    $originMain = (& git rev-parse origin/main).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -ne $originMain) {
        throw 'Il branch main locale deve essere sincronizzato esattamente con origin/main.'
    }

    $previousTag = (& git describe --tags --abbrev=0 HEAD^).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $previousTag) { throw 'Tag release precedente non trovato.' }
    $currentGradle = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'android\app\build.gradle')
    $previousGradle = ((& git show "${previousTag}:android/app/build.gradle") -join "`n")
    if ($LASTEXITCODE -ne 0) { throw 'Impossibile leggere il versionCode della release precedente.' }
    $currentVersionCode = Get-AndroidVersionCode $currentGradle
    $previousVersionCode = Get-AndroidVersionCode $previousGradle
    if ($currentVersionCode -le $previousVersionCode) {
        throw "versionCode Android deve aumentare rispetto a $previousTag ($previousVersionCode)."
    }

    & npm.cmd run check
    if ($LASTEXITCODE -ne 0) { throw 'Verifiche Node fallite.' }

    & .\build-android.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Build Android fallita.' }

    $apk = Join-Path $PSScriptRoot "dist\mcp-android-$version-debug.apk"
    $hash = "$apk.sha256"
    if (-not (Test-Path -LiteralPath $apk) -or -not (Test-Path -LiteralPath $hash)) {
        throw 'Asset release mancanti.'
    }

    $java = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools\jdk17') -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($java) { $env:JAVA_HOME = Split-Path (Split-Path $java.FullName -Parent) -Parent }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Android build-tools non trovati.' }
    $signerOutput = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Firma APK non valida.' }
    $expectedSignerSha256 = '7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f'
    $signerLine = $signerOutput | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
    $signerCountLine = $signerOutput | Where-Object { $_ -match '^Number of signers:' } | Select-Object -First 1
    if (-not $signerLine -or -not $signerCountLine) { throw 'Impossibile verificare il certificato APK.' }
    $actualSignerSha256 = (($signerLine -split ':', 2)[1]).Trim().ToLowerInvariant()
    $signerCount = [int](($signerCountLine -split ':', 2)[1]).Trim()
    if ($signerCount -ne 1 -or $actualSignerSha256 -ne $expectedSignerSha256) {
        throw 'Certificato APK diverso dallo signer storico delle release.'
    }

    gh auth status | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI non autenticata.' }

    # Let cmd.exe absorb gh's expected stderr when the release does not exist.
    # With ErrorActionPreference=Stop, invoking gh directly would turn that
    # normal "release not found" probe into a terminating PowerShell error.
    & cmd.exe /d /c "gh release view $tag --repo JackoPeru/mcp-android >nul 2>nul"
    if ($LASTEXITCODE -eq 0) {
        throw "La release $tag esiste già ed è immutabile."
    }
    gh release create $tag $apk $hash --repo JackoPeru/mcp-android --title "MCP Android $tag" --generate-notes
    if ($LASTEXITCODE -ne 0) { throw 'Pubblicazione release fallita.' }
} finally {
    Remove-Item Env:RELEASE_TAG -ErrorAction SilentlyContinue
    Pop-Location
}
