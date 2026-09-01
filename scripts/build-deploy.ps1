[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [string]$ServerRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '') }
    finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

function Get-ZipEntries([string]$Path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try { return @($archive.Entries | ForEach-Object { $_.FullName }) }
    finally { $archive.Dispose() }
}

function Read-ZipText([string]$Path, [string]$EntryName) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) { throw "Archive is missing $EntryName" }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    }
    finally { $archive.Dispose() }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdkRoot = 'C:\java\25'
$javaExe = Join-Path $jdkRoot 'bin\java.exe'
$wrapper = Join-Path $projectRoot 'gradlew.bat'
$gradleHome = 'C:\Users\y\.gradle'

Write-Step 'Checking Java 25 and Gradle Wrapper'
foreach ($required in @($javaExe, $wrapper)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required file is missing: $required" }
}
$env:JAVA_HOME = $jdkRoot
$env:GRADLE_USER_HOME = $gradleHome
$env:PATH = (Join-Path $jdkRoot 'bin') + ';' + $env:PATH
& $javaExe -version
if ($LASTEXITCODE -ne 0) { throw 'JDK 25 failed to start.' }

Write-Step 'Building and packaging YknItems'
Push-Location $projectRoot
try {
    & $wrapper '--no-daemon' 'clean' 'build'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
}
finally { Pop-Location }

Write-Step 'Validating plugin and CraftEngine pack'
$jars = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build\libs') -Filter 'YknItems-*.jar' -File)
if ($jars.Count -ne 1) { throw "Expected one plugin JAR, found $($jars.Count)." }
$pluginJar = $jars[0]
if ($pluginJar.Name -notmatch '^YknItems-(?<version>.+)\.jar$') { throw 'Cannot determine plugin version.' }
$version = $Matches.version
$craftEnginePack = Join-Path $projectRoot "build\distributions\YknItems-CraftEngine-Pack-$version.zip"
if (-not (Test-Path -LiteralPath $craftEnginePack -PathType Leaf)) { throw 'CraftEngine pack is missing.' }

$jarEntries = Get-ZipEntries $pluginJar.FullName
foreach ($required in @('plugin.yml', 'equipment.yml', 'recipes.yml', 'cn/yknitems/YknItemsPlugin.class')) {
    if ($jarEntries -notcontains $required) { throw "Plugin JAR is missing $required" }
}
$pluginYaml = Read-ZipText $pluginJar.FullName 'plugin.yml'
$versionLine = @($pluginYaml -split "`r?`n" | Where-Object { $_ -match '^version\s*:' } | Select-Object -First 1)
if ($versionLine.Count -ne 1) { throw 'plugin.yml does not declare a version.' }
$declaredVersion = (($versionLine[0] -split ':', 2)[1]).Trim().Trim([char[]]@(39, 34))
if ($declaredVersion -ne $version) {
    throw "JAR filename and plugin.yml version do not match ($version)."
}

$packEntries = Get-ZipEntries $craftEnginePack
$requiredPackEntries = @(
    'yknitems/pack.yml',
    'yknitems/configuration/items.yml',
    'yknitems/resourcepack/assets/yknitems/items/gui_crafting_table.json',
    'yknitems/resourcepack/assets/yknitems/items/gui_arrow.json',
    'yknitems/resourcepack/assets/yknitems/items/eclipse_core.json',
    'yknitems/resourcepack/assets/yknitems/models/item/resonant_crystal.json',
    'yknitems/resourcepack/assets/yknitems/textures/item/tempered_ingot.png'
)
foreach ($required in $requiredPackEntries) {
    if ($packEntries -notcontains $required) { throw "CraftEngine pack is missing $required" }
}
if ($packEntries | Where-Object { $_ -match '/(items|models/item|textures/item)/(worldsplitter|ember_sword)\.' }) {
    throw 'CraftEngine pack must not contain custom weapon textures or models.'
}

$equipmentYaml = Get-Content -LiteralPath (Join-Path $projectRoot 'src\main\resources\equipment.yml') -Raw -Encoding UTF8
$weaponCount = ([regex]::Matches($equipmentYaml, '(?m)^      [a-z0-9_]+:\s*$')).Count
$armorSection = ($equipmentYaml -split '(?m)^armor-sets:\s*$', 2)[1]
$armorSetCount = ([regex]::Matches($armorSection, '(?m)^  [a-z0-9_]+:\s*$')).Count
if ($weaponCount -ne 18) { throw "Expected 18 weapon definitions, found $weaponCount." }
if ($armorSetCount -ne 9) { throw "Expected 9 armor sets, found $armorSetCount." }

Write-Host "Plugin: $($pluginJar.FullName)" -ForegroundColor Green
Write-Host "Plugin SHA256: $(Get-Sha256 $pluginJar.FullName)"
Write-Host "CraftEngine pack: $craftEnginePack" -ForegroundColor Green
Write-Host "CraftEngine SHA256: $(Get-Sha256 $craftEnginePack)"

if ($BuildOnly) {
    Write-Host "`nBuild-only validation completed. No server files were changed." -ForegroundColor Green
    return
}

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    $serverFolder = ([char]0x670D).ToString() + [char]0x52A1 + [char]0x7AEF
    $ServerRoot = "F:\mc$serverFolder\Paper-1.21.10-Inf-Skyblock"
}
& (Join-Path $PSScriptRoot 'install-release.ps1') `
    -ServerRoot $ServerRoot `
    -PluginJar $pluginJar.FullName `
    -CraftEnginePack $craftEnginePack `
    -Version $version
if ($LASTEXITCODE -ne 0) { throw "Deployment failed with exit code $LASTEXITCODE." }
