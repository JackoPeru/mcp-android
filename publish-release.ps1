$ErrorActionPreference = 'Stop'

$package = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'package.json') | ConvertFrom-Json
$version = [string]$package.version
$tag = "v$version"
$env:RELEASE_TAG = $tag

Push-Location $PSScriptRoot
try {
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
    & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'Firma APK non valida.' }

    gh auth status | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI non autenticata.' }

    gh release view $tag --repo JackoPeru/mcp-android *> $null
    if ($LASTEXITCODE -eq 0) {
        gh release upload $tag $apk $hash --repo JackoPeru/mcp-android --clobber
    } else {
        gh release create $tag $apk $hash --repo JackoPeru/mcp-android --title "MCP Android $tag" --generate-notes
    }
    if ($LASTEXITCODE -ne 0) { throw 'Pubblicazione release fallita.' }
} finally {
    Remove-Item Env:RELEASE_TAG -ErrorAction SilentlyContinue
    Pop-Location
}
