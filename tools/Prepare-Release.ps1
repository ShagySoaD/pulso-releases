param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9]+(\.[0-9]+){1,3}$')][string]$Version,
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$VersionCode,
    [Parameter(Mandatory=$true)][string]$ApkDirectory,
    [string]$Repository = 'ShagySoaD/pulso-releases',
    [string]$Notes = 'Mejoras y correcciones de PULSO.'
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}$') { throw 'Repositorio invalido' }
$releaseDirectory = (Resolve-Path -LiteralPath $ApkDirectory).Path
$assets = @()
$hashes = @()
foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
    $name = "Pulso-$Version-$abi.apk"
    $path = Join-Path $releaseDirectory $name
    $file = Get-Item -LiteralPath $path
    $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $assets += [ordered]@{abi=$abi;url="https://github.com/$Repository/releases/download/v$Version/$name";sha256=$digest;size=$file.Length}
    $hashes += "$digest  $name"
}
$manifest = [ordered]@{schema=1;packageName='app.pulso.music';versionCode=$VersionCode;versionName=$Version;minSdk=29;notes=$Notes;assets=$assets}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $releaseDirectory 'update.json'), ($manifest | ConvertTo-Json -Depth 6), $utf8)
[System.IO.File]::WriteAllText((Join-Path $releaseDirectory 'SHA256.txt'), ($hashes -join [Environment]::NewLine), $utf8)
Write-Output "Preparado update.json para v$Version. Publicar junto a las tres APK verificadas; no modificar ni sustituir archivos despues de publicar."
