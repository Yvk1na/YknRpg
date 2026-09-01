[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerRoot,
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][string]$CraftEnginePack,
    [Parameter(Mandatory = $true)][string]$Version
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-FullPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path)
}

function Assert-Within([string]$Path, [string]$Directory) {
    $fullPath = (Get-FullPath $Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $fullDirectory = (Get-FullPath $Directory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $fullPath.StartsWith($fullDirectory + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify path outside deployment directory: $fullPath"
    }
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

function Assert-Unlocked([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    $stream = $null
    try { $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None) }
    catch { throw "Server file is still in use: $Path" }
    finally { if ($null -ne $stream) { $stream.Dispose() } }
}

function Test-Port([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(500, $false)) { return $false }
        $client.EndConnect($pending)
        return $client.Connected
    }
    catch { return $false }
    finally { $client.Dispose() }
}

$ServerRoot = Get-FullPath $ServerRoot
$PluginJar = Get-FullPath $PluginJar
$CraftEnginePack = Get-FullPath $CraftEnginePack
$pluginsDirectory = Join-Path $ServerRoot 'plugins'
$craftEngineResources = Join-Path $pluginsDirectory 'CraftEngine\resources'
$craftEngineTarget = Join-Path $craftEngineResources 'yknitems'
$remappedDirectory = Join-Path $pluginsDirectory '.paper-remapped'
$backupParent = Join-Path $ServerRoot 'backups\yknitems-deploy'

foreach ($directory in @($ServerRoot, $pluginsDirectory, $craftEngineResources)) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Required directory is missing: $directory" }
}
foreach ($file in @($PluginJar, $CraftEnginePack)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Release artifact is missing: $file" }
}
Assert-Within $pluginsDirectory $ServerRoot
Assert-Within $craftEngineTarget $ServerRoot
Assert-Within $backupParent $ServerRoot

$serverLeaf = [IO.Path]::GetFileName($ServerRoot.TrimEnd([IO.Path]::DirectorySeparatorChar))
try {
    $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop |
        Where-Object {
            $null -ne $_.CommandLine -and
            ($_.CommandLine.IndexOf($ServerRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
             $_.CommandLine.IndexOf($serverLeaf, [StringComparison]::OrdinalIgnoreCase) -ge 0)
        })
    if ($processes.Count -gt 0) {
        throw "Target server is running (PID: $(($processes | ForEach-Object ProcessId) -join ', '))."
    }
}
catch {
    if ($_.Exception.Message -like 'Target server is running*') { throw }
    Write-Warning 'Could not inspect Java command lines; port and lock checks will still run.'
}

$serverPort = 25565
$propertiesFile = Join-Path $ServerRoot 'server.properties'
if (Test-Path -LiteralPath $propertiesFile -PathType Leaf) {
    $portLine = Get-Content -LiteralPath $propertiesFile | Where-Object { $_ -match '^server-port=(\d+)$' } | Select-Object -First 1
    if ($null -ne $portLine -and $portLine -match '^server-port=(\d+)$') { $serverPort = [int]$Matches[1] }
}
if (Test-Port $serverPort) { throw "Target server port $serverPort is open." }
Assert-Unlocked (Join-Path $ServerRoot 'world\session.lock')

$oldPluginJars = @(Get-ChildItem -LiteralPath $pluginsDirectory -Filter 'YknItems*.jar' -File)
$oldRemappedJars = @()
if (Test-Path -LiteralPath $remappedDirectory -PathType Container) {
    $oldRemappedJars = @(Get-ChildItem -LiteralPath $remappedDirectory -Filter 'YknItems*.jar' -File -Recurse)
}
foreach ($file in @($oldPluginJars + $oldRemappedJars)) { Assert-Unlocked $file.FullName }
Assert-Unlocked (Join-Path $craftEngineTarget 'pack.yml')

$token = [Guid]::NewGuid().ToString('N')
$temporaryRoot = Join-Path $backupParent ".staging-$token"
$temporaryJar = Join-Path $temporaryRoot ([IO.Path]::GetFileName($PluginJar))
$temporaryPack = Join-Path $temporaryRoot 'pack'
$temporaryPackRoot = Join-Path $temporaryPack 'yknitems'
$backupDirectory = Join-Path $backupParent ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Version)
Assert-Within $temporaryRoot $backupParent
Assert-Within $backupDirectory $backupParent

[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
Copy-Item -LiteralPath $PluginJar -Destination $temporaryJar
if ((Get-Sha256 $temporaryJar) -ne (Get-Sha256 $PluginJar)) { throw 'Temporary JAR failed SHA-256 verification.' }
Expand-Archive -LiteralPath $CraftEnginePack -DestinationPath $temporaryPack
foreach ($required in @(
    'pack.yml',
    'configuration\items.yml',
    'resourcepack\assets\yknitems\items\gui_crafting_table.json',
    'resourcepack\assets\yknitems\items\gui_arrow.json'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $temporaryPackRoot $required) -PathType Leaf)) {
        throw "CraftEngine pack is missing $required"
    }
}
$forbiddenWeapons = @(Get-ChildItem -LiteralPath $temporaryPackRoot -File -Recurse |
    Where-Object { $_.BaseName -in @('ember_sword', 'worldsplitter', 'firmament_staff') })
if ($forbiddenWeapons.Count -gt 0) { throw 'CraftEngine pack still contains custom weapon assets.' }

$movedPluginJars = @()
$movedRemappedJars = @()
$oldPackBackup = $null
$newJarPath = Join-Path $pluginsDirectory ([IO.Path]::GetFileName($PluginJar))
$newJarInstalled = $false
$newPackInstalled = $false

try {
    [IO.Directory]::CreateDirectory($backupDirectory) | Out-Null
    $jarBackupDirectory = Join-Path $backupDirectory 'plugin-jars'
    $remapBackupDirectory = Join-Path $backupDirectory 'paper-remapped'
    [IO.Directory]::CreateDirectory($jarBackupDirectory) | Out-Null
    [IO.Directory]::CreateDirectory($remapBackupDirectory) | Out-Null

    foreach ($oldJar in $oldPluginJars) {
        $backup = Join-Path $jarBackupDirectory $oldJar.Name
        Move-Item -LiteralPath $oldJar.FullName -Destination $backup
        $movedPluginJars += [pscustomobject]@{ Original = $oldJar.FullName; Backup = $backup }
    }
    $remapIndex = 0
    foreach ($oldJar in $oldRemappedJars) {
        $backup = Join-Path $remapBackupDirectory ("$remapIndex-$($oldJar.Name)")
        Move-Item -LiteralPath $oldJar.FullName -Destination $backup
        $movedRemappedJars += [pscustomobject]@{ Original = $oldJar.FullName; Backup = $backup }
        $remapIndex++
    }
    if (Test-Path -LiteralPath $craftEngineTarget -PathType Container) {
        $oldPackBackup = Join-Path $backupDirectory 'CraftEngine-yknitems'
        Move-Item -LiteralPath $craftEngineTarget -Destination $oldPackBackup
    }

    Move-Item -LiteralPath $temporaryJar -Destination $newJarPath
    $newJarInstalled = $true
    Move-Item -LiteralPath $temporaryPackRoot -Destination $craftEngineTarget
    $newPackInstalled = $true

    if ((Get-Sha256 $newJarPath) -ne (Get-Sha256 $PluginJar)) { throw 'Deployed JAR failed SHA-256 verification.' }
    $deployedAssetFiles = @(Get-ChildItem -LiteralPath (Join-Path $craftEngineTarget 'resourcepack\assets\yknitems') -File -Recurse)
    if ($deployedAssetFiles.Count -ne 15) { throw "Expected 15 CraftEngine asset files, found $($deployedAssetFiles.Count)." }
    if ($deployedAssetFiles | Where-Object { $_.BaseName -in @('ember_sword', 'worldsplitter', 'firmament_staff') }) {
        throw 'Old custom weapon assets remain after deployment.'
    }

    $manifest = @(
        "version=$Version",
        "plugin=$($newJarPath)",
        "plugin_sha256=$(Get-Sha256 $newJarPath)",
        "craftengine_pack_sha256=$(Get-Sha256 $CraftEnginePack)",
        "deployed_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')"
    )
    [IO.File]::WriteAllLines((Join-Path $backupDirectory 'deployment-manifest.txt'), $manifest, [Text.Encoding]::UTF8)
}
catch {
    $deploymentError = $_
    Write-Warning 'Deployment failed; restoring the previous release.'
    if ($newJarInstalled -and (Test-Path -LiteralPath $newJarPath -PathType Leaf)) {
        Remove-Item -LiteralPath $newJarPath -Force
    }
    if ($newPackInstalled -and (Test-Path -LiteralPath $craftEngineTarget -PathType Container)) {
        Remove-Item -LiteralPath $craftEngineTarget -Recurse -Force
    }
    foreach ($record in $movedPluginJars) {
        if (Test-Path -LiteralPath $record.Backup -PathType Leaf) {
            Move-Item -LiteralPath $record.Backup -Destination $record.Original -Force
        }
    }
    foreach ($record in $movedRemappedJars) {
        if (Test-Path -LiteralPath $record.Backup -PathType Leaf) {
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($record.Original)) | Out-Null
            Move-Item -LiteralPath $record.Backup -Destination $record.Original -Force
        }
    }
    if ($null -ne $oldPackBackup -and (Test-Path -LiteralPath $oldPackBackup -PathType Container)) {
        Move-Item -LiteralPath $oldPackBackup -Destination $craftEngineTarget
    }
    throw $deploymentError
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

Write-Host "Deployment completed: $newJarPath" -ForegroundColor Green
Write-Host "CraftEngine resources: $craftEngineTarget"
Write-Host "Backup: $backupDirectory"
