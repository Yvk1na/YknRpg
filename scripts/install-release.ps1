[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerRoot,
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][string]$CraftEnginePack,
    [Parameter(Mandatory = $true)][string]$MythicMobsPack,
    [Parameter(Mandatory = $true)][string]$EcoPetsPack,
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

function Get-RelativePath([string]$Directory, [string]$Path) {
    $fullDirectory = (Get-FullPath $Directory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $fullPath = Get-FullPath $Path
    $prefix = $fullDirectory + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Cannot make path relative to directory: $fullPath"
    }
    return $fullPath.Substring($prefix.Length)
}

function Invoke-RollbackStep(
    [string]$Description,
    [scriptblock]$Action,
    [Collections.Generic.List[string]]$Errors
) {
    try { & $Action }
    catch { $Errors.Add("${Description}: $($_.Exception.Message)") }
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

function Read-ZipText([string]$Path, [string]$EntryName) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) { return '' }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    }
    finally { $archive.Dispose() }
}

function Get-PluginName([string]$Path) {
    try {
        $pluginYaml = Read-ZipText $Path 'plugin.yml'
        $nameLine = @($pluginYaml -split "`r?`n" | Where-Object { $_ -match '^\s*name\s*:' } | Select-Object -First 1)
        if ($nameLine.Count -ne 1) { return '' }
        return (($nameLine[0] -split ':', 2)[1]).Trim().Trim([char[]]@(39, 34))
    }
    catch { return '' }
}

function Get-YknPluginJars([string]$Directory, [switch]$Recurse) {
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { return @() }
    $arguments = @{
        LiteralPath = $Directory
        Filter = '*.jar'
        File = $true
    }
    if ($Recurse) { $arguments.Recurse = $true }
    return @(Get-ChildItem @arguments | Where-Object {
        $pluginName = Get-PluginName $_.FullName
        $pluginName -in @('YknItems', 'YknRpg') -or $_.Name -match '^(?:YknItems|YknRpg).*\.jar$'
    })
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

function Test-VersionAtLeast([string]$Actual, [string]$Minimum) {
    $actualParts = @($Actual.Split('.') | ForEach-Object { [int]$_ })
    $minimumParts = @($Minimum.Split('.') | ForEach-Object { [int]$_ })
    $count = [Math]::Max($actualParts.Count, $minimumParts.Count)
    for ($index = 0; $index -lt $count; $index++) {
        $actualPart = if ($index -lt $actualParts.Count) { $actualParts[$index] } else { 0 }
        $minimumPart = if ($index -lt $minimumParts.Count) { $minimumParts[$index] } else { 0 }
        if ($actualPart -gt $minimumPart) { return $true }
        if ($actualPart -lt $minimumPart) { return $false }
    }
    return $true
}

$ServerRoot = Get-FullPath $ServerRoot
$PluginJar = Get-FullPath $PluginJar
$CraftEnginePack = Get-FullPath $CraftEnginePack
$MythicMobsPack = Get-FullPath $MythicMobsPack
$EcoPetsPack = Get-FullPath $EcoPetsPack
$pluginsDirectory = Join-Path $ServerRoot 'plugins'
$craftEngineResources = Join-Path $pluginsDirectory 'CraftEngine\resources'
$legacyCraftEngineTarget = Join-Path $craftEngineResources 'yknitems'
$craftEngineTarget = Join-Path $craftEngineResources 'yknrpg'
$mythicMobsItems = Join-Path $pluginsDirectory 'MythicMobs\items'
$ecoPetsDirectory = Join-Path $pluginsDirectory 'EcoPets'
$ecoPetsPets = Join-Path $ecoPetsDirectory 'pets'
$ecoPetsTarget = Join-Path $ecoPetsPets 'yknrpg'
$remappedDirectory = Join-Path $pluginsDirectory '.paper-remapped'
$updateDirectory = Join-Path $pluginsDirectory 'update'
$legacyDataDirectory = Join-Path $pluginsDirectory 'YknItems'
$currentDataDirectory = Join-Path $pluginsDirectory 'YknRpg'
$backupParent = Join-Path $ServerRoot 'backups\yknrpg-deploy'

foreach ($directory in @($ServerRoot, $pluginsDirectory, $craftEngineResources, $mythicMobsItems, $ecoPetsPets)) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Required directory is missing: $directory" }
}
foreach ($dependency in @('MythicMobs', 'AuraSkills', 'EcoPets', 'libreforge')) {
    if (@(Get-ChildItem -LiteralPath $pluginsDirectory -Filter "$dependency*.jar" -File).Count -eq 0) {
        throw "Required plugin is missing: $dependency"
    }
}
foreach ($file in @($PluginJar, $CraftEnginePack, $MythicMobsPack, $EcoPetsPack)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Release artifact is missing: $file" }
}
Assert-Within $pluginsDirectory $ServerRoot
Assert-Within $legacyCraftEngineTarget $ServerRoot
Assert-Within $craftEngineTarget $ServerRoot
Assert-Within $mythicMobsItems $ServerRoot
Assert-Within $ecoPetsTarget $ServerRoot
Assert-Within $legacyDataDirectory $ServerRoot
Assert-Within $currentDataDirectory $ServerRoot
Assert-Within $backupParent $ServerRoot

$versionHistory = Join-Path $ServerRoot 'version_history.json'
if (-not (Test-Path -LiteralPath $versionHistory -PathType Leaf)) {
    throw 'Cannot verify target Minecraft version because version_history.json is missing.'
}
$versionInfo = Get-Content -LiteralPath $versionHistory -Raw | ConvertFrom-Json
if ([string]$versionInfo.currentVersion -notmatch 'MC:\s*(?<minecraft>[0-9.]+)') {
    throw 'Cannot determine target Minecraft version from version_history.json.'
}
if (-not (Test-VersionAtLeast $Matches.minecraft '1.21.11')) {
    throw "YknRpg $Version requires Minecraft 1.21.11 or newer; target is $($Matches.minecraft)."
}

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

$managedPluginJars = @()
foreach ($scope in @(
    [pscustomobject]@{ Name = 'plugins-root'; Directory = $pluginsDirectory; Recurse = $false },
    [pscustomobject]@{ Name = 'plugins-update'; Directory = $updateDirectory; Recurse = $false },
    [pscustomobject]@{ Name = 'paper-remapped'; Directory = $remappedDirectory; Recurse = $true }
)) {
    $found = if ($scope.Recurse) {
        Get-YknPluginJars $scope.Directory -Recurse
    } else {
        Get-YknPluginJars $scope.Directory
    }
    foreach ($file in $found) {
        $managedPluginJars += [pscustomobject]@{ Scope = $scope.Name; File = $file }
    }
}

$legacyDataExists = Test-Path -LiteralPath $legacyDataDirectory -PathType Container
$currentDataExists = Test-Path -LiteralPath $currentDataDirectory -PathType Container
if ($legacyDataExists -and $currentDataExists) {
    throw 'Both plugins/YknItems and plugins/YknRpg exist; refusing to guess which data directory should win.'
}

$mythicFileNames = @('YknRpg_Materials.yml', 'YknRpg_Weapons.yml', 'YknRpg_Armor.yml')
$managedMythicFileNames = @(
    'YknItems_Materials.yml', 'YknItems_Weapons.yml', 'YknItems_Armor.yml',
    'YknRpg_Materials.yml', 'YknRpg_Weapons.yml', 'YknRpg_Armor.yml'
)
$oldMythicFiles = @($managedMythicFileNames | ForEach-Object {
    $candidate = Join-Path $mythicMobsItems $_
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { Get-Item -LiteralPath $candidate }
})
foreach ($entry in $managedPluginJars) { Assert-Unlocked $entry.File.FullName }
foreach ($file in $oldMythicFiles) { Assert-Unlocked $file.FullName }
if (Test-Path -LiteralPath $ecoPetsTarget -PathType Container) {
    foreach ($file in @(Get-ChildItem -LiteralPath $ecoPetsTarget -File -Recurse)) {
        Assert-Unlocked $file.FullName
    }
}
Assert-Unlocked (Join-Path $legacyCraftEngineTarget 'pack.yml')
Assert-Unlocked (Join-Path $craftEngineTarget 'pack.yml')

$token = [Guid]::NewGuid().ToString('N')
$temporaryRoot = Join-Path $backupParent ".staging-$token"
$temporaryJar = Join-Path $temporaryRoot ([IO.Path]::GetFileName($PluginJar))
$temporaryPack = Join-Path $temporaryRoot 'pack'
$temporaryPackRoot = Join-Path $temporaryPack 'yknrpg'
$temporaryMythicPack = Join-Path $temporaryRoot 'mythicmobs'
$temporaryMythicItems = Join-Path $temporaryMythicPack 'items'
$temporaryEcoPetsPack = Join-Path $temporaryRoot 'ecopets'
$temporaryEcoPetsRoot = Join-Path $temporaryEcoPetsPack 'pets\yknrpg'
$backupDirectory = Join-Path $backupParent ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Version + '-' + $token.Substring(0, 8))
Assert-Within $temporaryRoot $backupParent
Assert-Within $backupDirectory $backupParent

$movedPluginJars = @()
$movedMythicFiles = @()
$movedCraftEnginePacks = @()
$movedEcoPetsDirectory = $null
$newMythicFiles = @()
$newJarPath = Join-Path $pluginsDirectory ([IO.Path]::GetFileName($PluginJar))
$newJarInstalled = $false
$newPackInstalled = $false
$newEcoPetsInstalled = $false
$dataMigrated = $false
$dataDirectoryCreated = $false
$dataMigrationMode = if ($legacyDataExists) { 'legacy-to-current' } elseif ($currentDataExists) { 'current-upgrade' } else { 'fresh-install' }

try {
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    Copy-Item -LiteralPath $PluginJar -Destination $temporaryJar
    if ((Get-Sha256 $temporaryJar) -ne (Get-Sha256 $PluginJar)) { throw 'Temporary JAR failed SHA-256 verification.' }
    if ((Get-PluginName $temporaryJar) -ne 'YknRpg') { throw 'Release JAR must declare plugin name YknRpg.' }
    Expand-Archive -LiteralPath $CraftEnginePack -DestinationPath $temporaryPack
    Expand-Archive -LiteralPath $MythicMobsPack -DestinationPath $temporaryMythicPack
    Expand-Archive -LiteralPath $EcoPetsPack -DestinationPath $temporaryEcoPetsPack
    foreach ($required in @(
        'pack.yml',
        'configuration\items.yml',
        'resourcepack\assets\yknrpg\items\gui_crafting_table.json',
        'resourcepack\assets\yknrpg\items\gui_arrow.json'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $temporaryPackRoot $required) -PathType Leaf)) {
            throw "CraftEngine pack is missing $required"
        }
    }
    $unexpectedAssets = @(Get-ChildItem -LiteralPath $temporaryPackRoot -File -Recurse |
        Where-Object {
            $_.FullName -like '*resourcepack\assets\yknrpg*' -and
            $_.BaseName -notin @('gui_arrow', 'gui_crafting_table')
        })
    if ($unexpectedAssets.Count -gt 0) { throw 'CraftEngine pack may contain GUI assets only.' }
    $mythicSourceHashes = @{}
    foreach ($name in $mythicFileNames) {
        $source = Join-Path $temporaryMythicItems $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "MythicMobs pack is missing items/$name"
        }
        $mythicSourceHashes[$name] = Get-Sha256 $source
    }
    $mythicYaml = ($mythicFileNames | ForEach-Object {
        Get-Content -LiteralPath (Join-Path $temporaryMythicItems $_) -Raw -Encoding UTF8
    }) -join "`n"
    $mythicItemCount = ([regex]::Matches($mythicYaml, '(?m)^YKI_[A-Z0-9_]+:\s*$')).Count
    if ($mythicItemCount -ne 123) { throw "Expected 123 MythicMobs items; found $mythicItemCount." }

    if (-not (Test-Path -LiteralPath $temporaryEcoPetsRoot -PathType Container)) {
        throw 'EcoPets pack is missing pets/yknrpg.'
    }
    $temporaryEcoPetFiles = @(Get-ChildItem -LiteralPath $temporaryEcoPetsPack -File -Recurse)
    if ($temporaryEcoPetFiles.Count -ne 36) {
        throw "Expected exactly 36 EcoPets files; found $($temporaryEcoPetFiles.Count)."
    }
    $ecoPetSourceHashes = @{}
    foreach ($file in $temporaryEcoPetFiles) {
        $relativePath = (Get-RelativePath $temporaryEcoPetsPack $file.FullName).Replace('\', '/')
        if ($relativePath -notmatch '^pets/yknrpg/[a-z0-9_]+\.yml$') {
            throw "Unexpected EcoPets pack entry: $relativePath"
        }
        if ($ecoPetSourceHashes.ContainsKey($file.Name)) {
            throw "Duplicate EcoPets pet ID: $($file.BaseName)"
        }
        $ecoPetSourceHashes[$file.Name] = Get-Sha256 $file.FullName
    }
    if ($ecoPetSourceHashes.Count -ne 36) { throw 'EcoPets pack must contain 36 unique pet IDs.' }

    [IO.Directory]::CreateDirectory($backupDirectory) | Out-Null
    $jarBackupDirectory = Join-Path $backupDirectory 'plugin-jars'
    $mythicBackupDirectory = Join-Path $backupDirectory 'MythicMobs-items'
    $craftEngineBackupDirectory = Join-Path $backupDirectory 'CraftEngine-resources'
    $ecoPetsBackupDirectory = Join-Path $backupDirectory 'EcoPets-pets'
    $pluginDataBackupDirectory = Join-Path $backupDirectory 'plugin-data'
    foreach ($directory in @(
        $jarBackupDirectory,
        $mythicBackupDirectory,
        $craftEngineBackupDirectory,
        $ecoPetsBackupDirectory,
        $pluginDataBackupDirectory
    )) {
        [IO.Directory]::CreateDirectory($directory) | Out-Null
    }

    $jarIndex = 0
    foreach ($entry in $managedPluginJars) {
        $scopeBackupDirectory = Join-Path $jarBackupDirectory $entry.Scope
        [IO.Directory]::CreateDirectory($scopeBackupDirectory) | Out-Null
        $backup = Join-Path $scopeBackupDirectory ("$jarIndex-$($entry.File.Name)")
        Move-Item -LiteralPath $entry.File.FullName -Destination $backup
        $movedPluginJars += [pscustomobject]@{ Original = $entry.File.FullName; Backup = $backup }
        $jarIndex++
    }

    foreach ($existingTarget in @($legacyCraftEngineTarget, $craftEngineTarget)) {
        if (-not (Test-Path -LiteralPath $existingTarget -PathType Container)) { continue }
        $backup = Join-Path $craftEngineBackupDirectory ([IO.Path]::GetFileName($existingTarget))
        Move-Item -LiteralPath $existingTarget -Destination $backup
        $movedCraftEnginePacks += [pscustomobject]@{ Original = $existingTarget; Backup = $backup }
    }
    if (Test-Path -LiteralPath $ecoPetsTarget -PathType Container) {
        $backup = Join-Path $ecoPetsBackupDirectory 'yknrpg'
        Move-Item -LiteralPath $ecoPetsTarget -Destination $backup
        $movedEcoPetsDirectory = [pscustomobject]@{ Original = $ecoPetsTarget; Backup = $backup }
    }
    foreach ($oldFile in $oldMythicFiles) {
        $backup = Join-Path $mythicBackupDirectory $oldFile.Name
        Move-Item -LiteralPath $oldFile.FullName -Destination $backup
        $movedMythicFiles += [pscustomobject]@{ Original = $oldFile.FullName; Backup = $backup }
    }

    if ($legacyDataExists) {
        Copy-Item -LiteralPath $legacyDataDirectory -Destination (Join-Path $pluginDataBackupDirectory 'YknItems') -Recurse
        Move-Item -LiteralPath $legacyDataDirectory -Destination $currentDataDirectory
        $dataMigrated = $true
    } elseif ($currentDataExists) {
        Copy-Item -LiteralPath $currentDataDirectory -Destination (Join-Path $pluginDataBackupDirectory 'YknRpg') -Recurse
    } else {
        [IO.Directory]::CreateDirectory($currentDataDirectory) | Out-Null
        $dataDirectoryCreated = $true
    }

    Move-Item -LiteralPath $temporaryJar -Destination $newJarPath
    $newJarInstalled = $true
    Move-Item -LiteralPath $temporaryPackRoot -Destination $craftEngineTarget
    $newPackInstalled = $true
    Move-Item -LiteralPath $temporaryEcoPetsRoot -Destination $ecoPetsTarget
    $newEcoPetsInstalled = $true
    foreach ($name in $mythicFileNames) {
        $source = Join-Path $temporaryMythicItems $name
        $target = Join-Path $mythicMobsItems $name
        Move-Item -LiteralPath $source -Destination $target
        $newMythicFiles += $target
        if ((Get-Sha256 $target) -ne $mythicSourceHashes[$name]) {
            throw "Deployed MythicMobs item file failed SHA-256 verification: $name"
        }
    }

    if ((Get-Sha256 $newJarPath) -ne (Get-Sha256 $PluginJar)) { throw 'Deployed JAR failed SHA-256 verification.' }
    if ((Get-PluginName $newJarPath) -ne 'YknRpg') { throw 'Deployed JAR does not identify as YknRpg.' }
    $deployedAssetFiles = @(Get-ChildItem -LiteralPath (Join-Path $craftEngineTarget 'resourcepack\assets\yknrpg') -File -Recurse)
    if ($deployedAssetFiles.Count -ne 6) { throw "Expected 6 CraftEngine GUI asset files, found $($deployedAssetFiles.Count)." }
    if ($deployedAssetFiles | Where-Object { $_.BaseName -notin @('gui_arrow', 'gui_crafting_table') }) {
        throw 'Non-GUI CraftEngine assets remain after deployment.'
    }
    $deployedEcoPetFiles = @(Get-ChildItem -LiteralPath $ecoPetsTarget -File -Recurse)
    if ($deployedEcoPetFiles.Count -ne 36) {
        throw "Expected 36 deployed EcoPets files; found $($deployedEcoPetFiles.Count)."
    }
    foreach ($file in $deployedEcoPetFiles) {
        $relativePath = (Get-RelativePath $ecoPetsTarget $file.FullName).Replace('\', '/')
        if ($relativePath -notmatch '^[a-z0-9_]+\.yml$' -or
            -not $ecoPetSourceHashes.ContainsKey($file.Name)) {
            throw "Unexpected deployed EcoPets file: $relativePath"
        }
        if ((Get-Sha256 $file.FullName) -ne $ecoPetSourceHashes[$file.Name]) {
            throw "Deployed EcoPets file failed SHA-256 verification: $relativePath"
        }
    }
    if (Test-Path -LiteralPath $legacyCraftEngineTarget) { throw 'Legacy CraftEngine resource directory still exists.' }
    foreach ($legacyName in @('YknItems_Materials.yml', 'YknItems_Weapons.yml', 'YknItems_Armor.yml')) {
        if (Test-Path -LiteralPath (Join-Path $mythicMobsItems $legacyName)) {
            throw "Legacy MythicMobs item file still exists: $legacyName"
        }
    }
    if (-not (Test-Path -LiteralPath $currentDataDirectory -PathType Container) -or
        (Test-Path -LiteralPath $legacyDataDirectory)) {
        throw 'Plugin data directory migration did not reach the expected YknRpg-only state.'
    }

    $rootManagedJars = @(Get-YknPluginJars $pluginsDirectory)
    $updateManagedJars = @(Get-YknPluginJars $updateDirectory)
    $remappedManagedJars = @(Get-YknPluginJars $remappedDirectory -Recurse)
    if ($rootManagedJars.Count -ne 1 -or $rootManagedJars[0].FullName -ne $newJarPath) {
        throw "Expected exactly one YknRpg JAR in plugins root; found $($rootManagedJars.Count)."
    }
    if ($updateManagedJars.Count -ne 0 -or $remappedManagedJars.Count -ne 0) {
        throw 'A legacy or duplicate YknItems/YknRpg JAR remains in update or Paper remap cache.'
    }

    $manifest = @(
        "version=$Version",
        "plugin=$($newJarPath)",
        "plugin_sha256=$(Get-Sha256 $newJarPath)",
        "craftengine_pack_sha256=$(Get-Sha256 $CraftEnginePack)",
        "mythicmobs_pack_sha256=$(Get-Sha256 $MythicMobsPack)",
        "ecopets_pack_sha256=$(Get-Sha256 $EcoPetsPack)",
        "ecopets_directory=$ecoPetsTarget",
        "ecopets_files=$($deployedEcoPetFiles.Count)",
        "data_migration=$dataMigrationMode",
        "data_directory=$currentDataDirectory",
        "removed_plugin_jars=$(($movedPluginJars | ForEach-Object { $_.Original }) -join ';')",
        "deployed_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')"
    )
    [IO.File]::WriteAllLines((Join-Path $backupDirectory 'deployment-manifest.txt'), $manifest, [Text.Encoding]::UTF8)
}
catch {
    $deploymentError = $_
    Write-Warning 'Deployment failed; restoring the previous release.'
    $rollbackErrors = [Collections.Generic.List[string]]::new()
    Invoke-RollbackStep 'remove new plugin JAR' {
        if ($newJarInstalled -and (Test-Path -LiteralPath $newJarPath -PathType Leaf)) {
            Remove-Item -LiteralPath $newJarPath -Force
        }
    } $rollbackErrors
    Invoke-RollbackStep 'remove new CraftEngine pack' {
        if ($newPackInstalled -and (Test-Path -LiteralPath $craftEngineTarget -PathType Container)) {
            Remove-Item -LiteralPath $craftEngineTarget -Recurse -Force
        }
    } $rollbackErrors
    Invoke-RollbackStep 'remove new EcoPets directory' {
        if ($newEcoPetsInstalled -and (Test-Path -LiteralPath $ecoPetsTarget -PathType Container)) {
            Remove-Item -LiteralPath $ecoPetsTarget -Recurse -Force
        }
    } $rollbackErrors
    foreach ($file in $newMythicFiles) {
        Invoke-RollbackStep "remove new MythicMobs file $file" {
            if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file -Force }
        } $rollbackErrors
    }
    Invoke-RollbackStep 'restore plugin data directory' {
        if ($dataMigrated -and (Test-Path -LiteralPath $currentDataDirectory -PathType Container)) {
            Move-Item -LiteralPath $currentDataDirectory -Destination $legacyDataDirectory
        } elseif ($dataDirectoryCreated -and (Test-Path -LiteralPath $currentDataDirectory -PathType Container)) {
            Remove-Item -LiteralPath $currentDataDirectory -Recurse -Force
        }
    } $rollbackErrors
    foreach ($record in $movedPluginJars) {
        Invoke-RollbackStep "restore plugin JAR $($record.Original)" {
            if (Test-Path -LiteralPath $record.Backup -PathType Leaf) {
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($record.Original)) | Out-Null
                Move-Item -LiteralPath $record.Backup -Destination $record.Original -Force
            }
        } $rollbackErrors
    }
    foreach ($record in $movedMythicFiles) {
        Invoke-RollbackStep "restore MythicMobs file $($record.Original)" {
            if (Test-Path -LiteralPath $record.Backup -PathType Leaf) {
                Move-Item -LiteralPath $record.Backup -Destination $record.Original -Force
            }
        } $rollbackErrors
    }
    foreach ($record in $movedCraftEnginePacks) {
        Invoke-RollbackStep "restore CraftEngine pack $($record.Original)" {
            if (Test-Path -LiteralPath $record.Backup -PathType Container) {
                Move-Item -LiteralPath $record.Backup -Destination $record.Original
            }
        } $rollbackErrors
    }
    Invoke-RollbackStep 'restore EcoPets directory' {
        if ($null -ne $movedEcoPetsDirectory -and
            (Test-Path -LiteralPath $movedEcoPetsDirectory.Backup -PathType Container)) {
            Move-Item -LiteralPath $movedEcoPetsDirectory.Backup -Destination $movedEcoPetsDirectory.Original
        }
    } $rollbackErrors
    if ($rollbackErrors.Count -gt 0) {
        $rollbackDetails = $rollbackErrors -join "`n"
        throw [InvalidOperationException]::new(
            "Deployment failed and rollback reported errors.`nOriginal error: $($deploymentError.Exception.Message)`nRollback errors:`n$rollbackDetails",
            $deploymentError.Exception
        )
    }
    throw $deploymentError
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        try { Remove-Item -LiteralPath $temporaryRoot -Recurse -Force }
        catch { Write-Warning "Could not remove staging directory $temporaryRoot`: $($_.Exception.Message)" }
    }
}

Write-Host "Deployment completed: $newJarPath" -ForegroundColor Green
Write-Host "CraftEngine resources: $craftEngineTarget"
Write-Host "MythicMobs items: $mythicMobsItems"
Write-Host "EcoPets pets: $ecoPetsTarget"
Write-Host "Backup: $backupDirectory"
