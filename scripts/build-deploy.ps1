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

function Get-MythicLoreTails([string]$Yaml, [string]$ContentName) {
    $tails = @{}
    $itemMatches = [regex]::Matches(
        $Yaml,
        '(?ms)^(?<id>YKI_[A-Z0-9_]+):[^\r\n]*\r?\n(?<body>.*?)(?=^YKI_[A-Z0-9_]+:|\z)'
    )
    foreach ($itemMatch in $itemMatches) {
        $itemId = $itemMatch.Groups['id'].Value
        if ($tails.ContainsKey($itemId)) { throw "$ContentName contains duplicate item $itemId." }
        $loreMatch = [regex]::Match(
            $itemMatch.Groups['body'].Value,
            '(?ms)^  Lore:[ \t]*\r?\n(?<lore>.*?)(?=^  [A-Za-z][A-Za-z0-9_-]*:|\z)'
        )
        if (-not $loreMatch.Success) { throw "$ContentName item $itemId is missing Lore." }
        $loreLines = @()
        foreach ($lineMatch in [regex]::Matches($loreMatch.Groups['lore'].Value, '(?m)^    - (?<value>[^\r\n]*?)[ \t]*\r?$')) {
            $value = $lineMatch.Groups['value'].Value.Trim()
            if ($value.Length -ge 2 -and $value.StartsWith("'") -and $value.EndsWith("'")) {
                $value = $value.Substring(1, $value.Length - 2).Replace("''", "'")
            }
            $loreLines += $value
        }
        if ($loreLines.Count -eq 0) { throw "$ContentName item $itemId has empty Lore." }
        $tails[$itemId] = $loreLines[-1]
    }
    return $tails
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

Write-Step 'Building and packaging YknRpg'
Push-Location $projectRoot
try {
    & $wrapper '--no-daemon' 'clean' 'build'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
}
finally { Pop-Location }

Write-Step 'Validating plugin, CraftEngine pack, MythicMobs pack, and EcoPets pack'
$jars = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build\libs') -Filter 'YknRpg-*.jar' -File)
if ($jars.Count -ne 1) { throw "Expected one plugin JAR, found $($jars.Count)." }
$pluginJar = $jars[0]
if ($pluginJar.Name -notmatch '^YknRpg-(?<version>.+)\.jar$') { throw 'Cannot determine plugin version.' }
$version = $Matches.version
$craftEnginePack = Join-Path $projectRoot "build\distributions\YknRpg-CraftEngine-Pack-$version.zip"
$mythicMobsPack = Join-Path $projectRoot "build\distributions\YknRpg-MythicMobs-Pack-$version.zip"
$ecoPetsPack = Join-Path $projectRoot "build\distributions\YknRpg-EcoPets-Pack-$version.zip"
if (-not (Test-Path -LiteralPath $craftEnginePack -PathType Leaf)) { throw 'CraftEngine pack is missing.' }
if (-not (Test-Path -LiteralPath $mythicMobsPack -PathType Leaf)) { throw 'MythicMobs pack is missing.' }
if (-not (Test-Path -LiteralPath $ecoPetsPack -PathType Leaf)) { throw 'EcoPets pack is missing.' }

$jarEntries = Get-ZipEntries $pluginJar.FullName
foreach ($required in @(
    'plugin.yml',
    'equipment.yml',
    'rpg.yml',
    'recipes.yml',
    'cn/yknrpg/YknRpgPlugin.class'
)) {
    if ($jarEntries -notcontains $required) { throw "Plugin JAR is missing $required" }
}
foreach ($unobfuscatedClass in @(
    'cn/yknrpg/gui/EquipmentGui.class',
    'cn/yknrpg/item/MaterialUseGuard.class',
    'cn/yknrpg/integration/EcoPetsItems.class',
    'cn/yknrpg/integration/ProtocolLibInputBridge.class',
    'cn/yknrpg/rpg/RpgEngine.class',
    'cn/yknrpg/rpg/RpgRegistry.class',
    'cn/yknrpg/integration/AuraSkillsMana.class'
)) {
    if ($jarEntries -contains $unobfuscatedClass) {
        throw "Plugin JAR still contains unobfuscated class $unobfuscatedClass"
    }
}
$pluginYaml = Read-ZipText $pluginJar.FullName 'plugin.yml'
$equipmentYaml = Read-ZipText $pluginJar.FullName 'equipment.yml'
$rpgYaml = Read-ZipText $pluginJar.FullName 'rpg.yml'
$versionLine = @($pluginYaml -split "`r?`n" | Where-Object { $_ -match '^version\s*:' } | Select-Object -First 1)
if ($versionLine.Count -ne 1) { throw 'plugin.yml does not declare a version.' }
$declaredVersion = (($versionLine[0] -split ':', 2)[1]).Trim().Trim([char[]]@(39, 34))
if ($declaredVersion -ne $version) {
    throw "JAR filename and plugin.yml version do not match ($version)."
}
if ($pluginYaml -notmatch '(?m)^name:\s*YknRpg\s*$') {
    throw 'plugin.yml must declare name: YknRpg.'
}
if ($pluginYaml -notmatch '(?m)^main:\s*cn\.yknrpg\.YknRpgPlugin\s*$') {
    throw 'plugin.yml must declare the YknRpg main class.'
}
if ($pluginYaml -notmatch '(?m)^\s{2}yknrpg:\s*$') {
    throw 'plugin.yml must declare /yknrpg as the primary command.'
}
if ($pluginYaml -notmatch "(?m)^api-version:\s*'1\.21\.11'\s*$") {
    throw 'plugin.yml must require Paper API 1.21.11.'
}
if ($pluginYaml -notmatch '(?m)^depend:\s*\[MythicMobs,\s*AuraSkills\]\s*$') {
    throw 'plugin.yml must declare MythicMobs and AuraSkills as required dependencies.'
}

$packEntries = Get-ZipEntries $craftEnginePack
$requiredPackEntries = @(
    'yknrpg/pack.yml',
    'yknrpg/configuration/items.yml',
    'yknrpg/resourcepack/assets/yknrpg/items/gui_crafting_table.json',
    'yknrpg/resourcepack/assets/yknrpg/items/gui_arrow.json',
    'yknrpg/resourcepack/assets/yknrpg/models/item/gui_crafting_table.json',
    'yknrpg/resourcepack/assets/yknrpg/models/item/gui_arrow.json',
    'yknrpg/resourcepack/assets/yknrpg/textures/item/gui_crafting_table.png',
    'yknrpg/resourcepack/assets/yknrpg/textures/item/gui_arrow.png'
)
foreach ($required in $requiredPackEntries) {
    if ($packEntries -notcontains $required) { throw "CraftEngine pack is missing $required" }
}
$allowedAssets = @($requiredPackEntries | Where-Object { $_ -match '/resourcepack/assets/' })
$unexpectedAssets = @($packEntries | Where-Object {
    $_ -match '^yknrpg/resourcepack/assets/yknrpg/.+\.[^/]+$' -and $_ -notin $allowedAssets
})
if ($unexpectedAssets.Count -gt 0) { throw 'CraftEngine pack may contain GUI assets only.' }

$mythicEntries = Get-ZipEntries $mythicMobsPack
$requiredMythicEntries = @(
    'items/YknRpg_Materials.yml',
    'items/YknRpg_Weapons.yml',
    'items/YknRpg_Armor.yml'
)
foreach ($required in $requiredMythicEntries) {
    if ($mythicEntries -notcontains $required) { throw "MythicMobs pack is missing $required" }
}
$weaponYaml = Read-ZipText $mythicMobsPack 'items/YknRpg_Weapons.yml'
$armorYaml = Read-ZipText $mythicMobsPack 'items/YknRpg_Armor.yml'
$mythicYaml = @(
    Read-ZipText $mythicMobsPack 'items/YknRpg_Materials.yml'
    $weaponYaml
    $armorYaml
) -join "`n"
$mythicItemCount = ([regex]::Matches($mythicYaml, '(?m)^YKI_[A-Z0-9_]+:\s*$')).Count
if ($mythicItemCount -ne 123) { throw "Expected 123 MythicMobs items; found $mythicItemCount." }

$rarityLabels = @{
    COMMON = '&8' + [regex]::Unescape('\u666e\u901a')
    UNCOMMON = '&a' + [regex]::Unescape('\u5c11\u89c1\u7684')
    RARE = '&9' + [regex]::Unescape('\u7a00\u6709')
    EPIC = '&5' + [regex]::Unescape('\u53f2\u8bd7')
    LEGENDARY = '&6' + [regex]::Unescape('\u4f20\u8bf4')
    MYTHIC = '&d' + [regex]::Unescape('\u795e\u8bdd')
}
$catalogMetadata = @{}
$catalogMetadataMatches = [regex]::Matches(
    $equipmentYaml,
    '(?m)^  (?<id>YKI_[A-Z0-9_]+):\s*\{kind:\s*(?<kind>ITEM|WEAPON|ARMOR),\s*rarity:\s*(?<rarity>COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC),\s*order:\s*\d+\}[ \t]*\r?$'
)
foreach ($metadataMatch in $catalogMetadataMatches) {
    $itemId = $metadataMatch.Groups['id'].Value
    if ($catalogMetadata.ContainsKey($itemId)) { throw "equipment.yml contains duplicate metadata for $itemId." }
    $catalogMetadata[$itemId] = [pscustomobject]@{
        Kind = $metadataMatch.Groups['kind'].Value
        Rarity = $metadataMatch.Groups['rarity'].Value
    }
}
if ($catalogMetadata.Count -ne 123) {
    throw "Expected 123 catalog metadata entries in the plugin JAR; found $($catalogMetadata.Count)."
}

$categorySword = [regex]::Unescape('\u5251')
$categoryWand = [regex]::Unescape('\u6cd5\u6756')
$categoryBow = [regex]::Unescape('\u5f13')
$categorySpear = [regex]::Unescape('\u957f\u67aa')
$categoryCodex = [regex]::Unescape('\u6cd5\u5178')
$categoryMaul = [regex]::Unescape('\u6218\u9524')
$categoryGun = [regex]::Unescape('\u67aa\u68b0')
$categoryScythe = [regex]::Unescape('\u9570\u5200')
$categoryArtifact = [regex]::Unescape('\u6cd5\u5668')
$categoryHorn = [regex]::Unescape('\u53f7\u89d2')
$categoryTrident = [regex]::Unescape('\u4e09\u53c9\u621f')
$categoryCrossbow = [regex]::Unescape('\u5f29')
$categoryStandard = [regex]::Unescape('\u6218\u65d7')
$weaponCategories = @{
    YKI_RECRUIT_BLADE = $categorySword
    YKI_BASTION_GREATSWORD = $categorySword
    YKI_RIFTWALKER_EDGE = $categorySword
    YKI_EARTHSHATTER_CLAYMORE = $categorySword
    YKI_VITAL_OATHSWORD = $categorySword
    YKI_CELESTIAL_VERDICT = $categorySword
    YKI_COPPER_FALCHION = $categorySword
    YKI_INFERNO_CHAINBLADE = $categorySword
    YKI_PHOENIX_SABER = $categorySword
    YKI_CHRONOSPLITTER = $categorySword
    YKI_QUARTZ_RAPIER = $categorySword
    YKI_PHANTOM_TWINBLADE = $categorySword
    YKI_SUNFORGED_BLADE = $categorySword
    YKI_SOVEREIGN_HOLYBLADE = $categorySword
    YKI_FIELD_MEDIC_WAND = $categoryWand
    YKI_GLACIER_SCEPTER = $categoryWand
    YKI_HEARTHWARD_BRANCH = $categoryWand
    YKI_ARCFLAME_STAFF = $categoryWand
    YKI_GALE_SHORTBOW = $categoryBow
    YKI_GENESIS_LONGBOW = $categoryBow
    YKI_BRAMBLE_LONGBOW = $categoryBow
    YKI_SKYREND_LONGBOW = $categoryBow
    YKI_DRAGONSTORM_SPEAR = $categorySpear
    YKI_TRAILBLAZER_SPEAR = $categorySpear
    YKI_WARPATH_PIKE = $categorySpear
    YKI_HEAVENPIERCER_LANCE = $categorySpear
    YKI_ASTRAL_GRIMOIRE = $categoryCodex
    YKI_WORLDROOT_CODEX = $categoryCodex
    YKI_SOULRECALL_CODEX = $categoryCodex
    YKI_LAWWEAVER_CODEX = $categoryCodex
    YKI_WARDEN_MAUL = $categoryMaul
    YKI_OATHKEEPER_MAUL = $categoryMaul
    YKI_PRISM_HANDCANNON = $categoryGun
    YKI_VOIDNEEDLE_RAILGUN = $categoryGun
    YKI_STARFALL_CANNON = $categoryGun
    YKI_ECLIPSE_SCYTHE = $categoryScythe
    YKI_VEILHOOK_SICKLE = $categoryScythe
    YKI_NAMELESS_REAPER = $categoryScythe
    YKI_VERDANT_CENSER = $categoryArtifact
    YKI_REQUIEM_BELL = $categoryArtifact
    YKI_WARSONG_HORN = $categoryHorn
    YKI_LEVIATHAN_TRIDENT = $categoryTrident
    YKI_THUNDERHEAD_REPEATER = $categoryCrossbow
    YKI_CITADEL_STANDARD = $categoryStandard
}
$allowedWeaponCategories = @(
    $categorySword, $categoryWand, $categoryBow, $categorySpear, $categoryCodex,
    $categoryMaul, $categoryGun, $categoryScythe, $categoryArtifact, $categoryHorn,
    $categoryTrident, $categoryCrossbow, $categoryStandard
)
$usedWeaponCategories = @($weaponCategories.Values | Sort-Object -Unique)
$weaponCategoryDifference = @(Compare-Object `
    -ReferenceObject @($allowedWeaponCategories | Sort-Object) `
    -DifferenceObject $usedWeaponCategories)
if ($weaponCategoryDifference.Count -ne 0) {
    throw 'The weapon category mapping does not use exactly the required category set.'
}
$weaponTails = Get-MythicLoreTails $weaponYaml 'YknRpg_Weapons.yml'
if ($weaponTails.Count -ne 44 -or $weaponCategories.Count -ne 44) {
    throw "Expected exactly 44 weapon category tails and mappings; found $($weaponTails.Count) tails and $($weaponCategories.Count) mappings."
}
$weaponCategoryLineCount = ([regex]::Matches(
    $weaponYaml,
    "(?m)^    - '(?:$((@($rarityLabels.Values) | ForEach-Object { [regex]::Escape($_) }) -join '|')) (?:$((@($allowedWeaponCategories) | ForEach-Object { [regex]::Escape($_) }) -join '|'))'[ \t]*\r?$"
)).Count
if ($weaponCategoryLineCount -ne 44) {
    throw "Expected exactly 44 formatted weapon category lines; found $weaponCategoryLineCount."
}
foreach ($itemId in $weaponCategories.Keys) {
    if (-not $weaponTails.ContainsKey($itemId)) { throw "Weapon pack is missing category tail for $itemId." }
    if (-not $catalogMetadata.ContainsKey($itemId) -or $catalogMetadata[$itemId].Kind -ne 'WEAPON') {
        throw "$itemId is not WEAPON catalog metadata."
    }
    $expectedTail = $rarityLabels[$catalogMetadata[$itemId].Rarity] + ' ' + $weaponCategories[$itemId]
    if ($weaponTails[$itemId] -cne $expectedTail) {
        throw "$itemId weapon tail must be '$expectedTail'; found '$($weaponTails[$itemId])'."
    }
}

$rpgSlots = @{}
foreach ($slotMatch in [regex]::Matches(
    $rpgYaml,
    '(?m)^  (?<id>YKI_[A-Z0-9_]+):[ \t]*\r?\n    slot:\s*(?<slot>ANY|MAIN_HAND|OFF_HAND|HEAD|CHEST|LEGS|FEET)[ \t]*\r?$'
)) {
    $itemId = $slotMatch.Groups['id'].Value
    if ($rpgSlots.ContainsKey($itemId)) { throw "rpg.yml contains duplicate slot for $itemId." }
    $rpgSlots[$itemId] = $slotMatch.Groups['slot'].Value
}
$categoryHelmet = [regex]::Unescape('\u5934\u76d4')
$categoryChestplate = [regex]::Unescape('\u80f8\u7532')
$categoryLeggings = [regex]::Unescape('\u62a4\u817f')
$categoryBoots = [regex]::Unescape('\u9774\u5b50')
$armorSlotCategories = @{
    HEAD = $categoryHelmet
    CHEST = $categoryChestplate
    LEGS = $categoryLeggings
    FEET = $categoryBoots
}
$armorTails = Get-MythicLoreTails $armorYaml 'YknRpg_Armor.yml'
$armorMetadataIds = @($catalogMetadata.Keys | Where-Object { $catalogMetadata[$_].Kind -eq 'ARMOR' })
if ($armorTails.Count -ne 48 -or $armorMetadataIds.Count -ne 48) {
    throw "Expected exactly 48 armor category tails and metadata entries; found $($armorTails.Count) tails and $($armorMetadataIds.Count) metadata entries."
}
$armorCategoryLineCount = ([regex]::Matches(
    $armorYaml,
    "(?m)^    - '(?:$((@($rarityLabels.Values) | ForEach-Object { [regex]::Escape($_) }) -join '|')) (?:$((@($armorSlotCategories.Values) | ForEach-Object { [regex]::Escape($_) }) -join '|'))'[ \t]*\r?$"
)).Count
if ($armorCategoryLineCount -ne 48) {
    throw "Expected exactly 48 formatted armor category lines; found $armorCategoryLineCount."
}
$armorCategoryCounts = @{}
foreach ($category in $armorSlotCategories.Values) { $armorCategoryCounts[$category] = 0 }
foreach ($itemId in $armorMetadataIds) {
    if (-not $armorTails.ContainsKey($itemId)) { throw "Armor pack is missing category tail for $itemId." }
    if (-not $rpgSlots.ContainsKey($itemId) -or -not $armorSlotCategories.ContainsKey($rpgSlots[$itemId])) {
        throw "$itemId must use an armor slot in rpg.yml."
    }
    $category = $armorSlotCategories[$rpgSlots[$itemId]]
    $expectedTail = $rarityLabels[$catalogMetadata[$itemId].Rarity] + ' ' + $category
    if ($armorTails[$itemId] -cne $expectedTail) {
        throw "$itemId armor tail must be '$expectedTail'; found '$($armorTails[$itemId])'."
    }
    $armorCategoryCounts[$category]++
}
foreach ($category in $armorSlotCategories.Values) {
    if ($armorCategoryCounts[$category] -ne 12) {
        throw "Armor category $category must contain exactly 12 items; found $($armorCategoryCounts[$category])."
    }
}

$ecoPetEntries = @(Get-ZipEntries $ecoPetsPack | Where-Object { -not $_.EndsWith('/') })
$expectedEcoPetEntries = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'packs\ecopets\pets\yknrpg') `
    -Filter '*.yml' -File | Sort-Object Name | ForEach-Object { "pets/yknrpg/$($_.Name)" })
if ($expectedEcoPetEntries.Count -ne 36) {
    throw "Expected 36 EcoPets source files; found $($expectedEcoPetEntries.Count)."
}
if ($ecoPetEntries.Count -ne 36 -or @($ecoPetEntries | Sort-Object -Unique).Count -ne 36) {
    throw "Expected exactly 36 unique EcoPets files in the pack; found $($ecoPetEntries.Count)."
}
if ($ecoPetEntries | Where-Object { $_ -notmatch '^pets/yknrpg/[a-z0-9_]+\.yml$' }) {
    throw 'EcoPets pack may contain only lower-case pets/yknrpg/*.yml files.'
}
$ecoPetEntryDifference = @(Compare-Object -ReferenceObject $expectedEcoPetEntries `
    -DifferenceObject @($ecoPetEntries | Sort-Object))
if ($ecoPetEntryDifference.Count -gt 0) {
    throw 'EcoPets pack entries do not exactly match packs/ecopets/pets/yknrpg.'
}

$catalogCount = ([regex]::Matches($equipmentYaml, '(?m)^  - YKI_[A-Z0-9_]+\s*$')).Count
$metadataCount = ([regex]::Matches($equipmentYaml, '(?m)^  YKI_[A-Z0-9_]+:\s*\{kind:')).Count
$defaultRecipeCount = ([regex]::Matches($equipmentYaml, '(?m)^  YKI_[A-Z0-9_]+:\s*\[')).Count
if ($catalogCount -ne 123) { throw "Expected 123 catalog items; found $catalogCount." }
if ($metadataCount -ne 123) { throw "Expected 123 catalog metadata entries; found $metadataCount." }
if ($defaultRecipeCount -ne 123) { throw "Expected 123 default recipes; found $defaultRecipeCount." }

Write-Host "Plugin: $($pluginJar.FullName)" -ForegroundColor Green
Write-Host "Plugin SHA256: $(Get-Sha256 $pluginJar.FullName)"
Write-Host "CraftEngine pack: $craftEnginePack" -ForegroundColor Green
Write-Host "CraftEngine SHA256: $(Get-Sha256 $craftEnginePack)"
Write-Host "MythicMobs pack: $mythicMobsPack" -ForegroundColor Green
Write-Host "MythicMobs SHA256: $(Get-Sha256 $mythicMobsPack)"
Write-Host "EcoPets pack: $ecoPetsPack" -ForegroundColor Green
Write-Host "EcoPets SHA256: $(Get-Sha256 $ecoPetsPack)"

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
    -MythicMobsPack $mythicMobsPack `
    -EcoPetsPack $ecoPetsPack `
    -Version $version
if ($LASTEXITCODE -ne 0) { throw "Deployment failed with exit code $LASTEXITCODE." }
