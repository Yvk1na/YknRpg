param([string[]]$PreviewIds, [string]$SourceDirectory, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
. (Join-Path $PSScriptRoot 'catalog_hand_poses.ps1')
$drawingReferences = @('System.Drawing')
if ($PSVersionTable.PSVersion.Major -ge 6) {
    $drawingReferences = @([Drawing.Color].Assembly.Location, [Drawing.Bitmap].Assembly.Location, 'System.Runtime') +
        @(Get-ChildItem -LiteralPath (Split-Path ([Drawing.Bitmap].Assembly.Location)) -Filter 'System.Private.Windows.*.dll' |
            ForEach-Object { $_.FullName })
}
Add-Type -Path (Join-Path $PSScriptRoot 'CatalogTextureAnimation.cs') -ReferencedAssemblies $drawingReferences
$project = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $project 'docs\design\catalog-32-v2'
$packRoot = Join-Path $project 'packs\craftengine'
if ($PreviewIds) { $packRoot = Join-Path $project 'build\catalog-pilot' }
if ($SourceDirectory) { $sourceRoot = [IO.Path]::GetFullPath($SourceDirectory) }
if ($OutputDirectory) { $packRoot = [IO.Path]::GetFullPath($OutputDirectory) }
$catalog = Get-Content -LiteralPath (Join-Path $sourceRoot 'catalog.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$rules = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'catalog_animation_rules.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$individual = Get-Content -LiteralPath (Join-Path $sourceRoot 'animations.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$samples = Get-Content -LiteralPath (Join-Path $project 'docs\design\bone-dragon-64-v1\manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$sampleNames = @($samples.assets | ForEach-Object { $_.id })
$utf8 = New-Object System.Text.UTF8Encoding($false)
$manifest = @()
$grips = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'catalog_grips.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$wrist = Get-HandWrist
function Get-SourceHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '') }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
foreach ($recordFile in Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'completed') -Filter '*.json' | Sort-Object Name) {
    if ($PreviewIds -and $recordFile.BaseName -notin $PreviewIds) { continue }
    $record = Get-Content -LiteralPath $recordFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($record.visual_review -ne 'passed') { continue }
    if (!$PreviewIds -and $record.motion_review -ne 'passed') { throw "Motion review is missing: $($record.id)" }
    $item = $catalog | Where-Object { $_.id -eq $record.id }
    if (-not $item) { throw "Unknown catalog asset: $($record.id)" }
    $name = $record.id.Substring(4).ToLowerInvariant()
    $namespace = if ($name -in $sampleNames) { 'yknrpg_bone' } else { 'yknrpg_items' }
    $assets = Join-Path $packRoot ('resourcepack\assets\' + $namespace)
    foreach ($folder in @('textures\item', 'models\item', 'items')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $assets $folder) | Out-Null
    }
    $source = Join-Path $sourceRoot $record.texture
    if ((Get-SourceHash $source) -ne $record.sha256) {
        throw "Source texture no longer matches its reviewed record: $($record.id)"
    }
    $rule = $rules.rules | Where-Object { $record.id -match $_.match } | Select-Object -First 1
    $profile = $individual.profiles | Where-Object { $_.id -eq $record.id }
    if (-not $profile -or @($profile).Count -ne 1 -or $profile.frame_times.Count -ne 16) {
        throw "Missing or invalid individual animation profile: $($record.id)"
    }
    $effectSource = Join-Path $sourceRoot $profile.effect_source
    if (-not (Test-Path -LiteralPath $effectSource -PathType Leaf)) {
        throw "Individual effect artwork is not ready: $($record.id)"
    }
    $strength = $rule.strength * $rules.rarity_strength.($item.rarity)
    if ($null -ne $profile.strength_multiplier) {
        $strength *= $profile.strength_multiplier
    }
    $texture = Join-Path $assets ('textures\item\' + $name + '.png')
    $effectHash = Get-SourceHash $effectSource
    if ($record.motion_review_sha256 -and $record.motion_review_sha256 -ne $effectHash) {
        throw "Effect artwork no longer matches its reviewed record: $($record.id)"
    }
    $handName = $name + '_in_hand'
    $handOutput = Join-Path $assets ('textures\item\' + $handName + '.png')
    $handSource = $source
    $handEffectHash = $effectHash
    $handSourceHash = $record.sha256
    $offsetX, $offsetY = if ($profile.effect_offset) { $profile.effect_offset } else { @(0, 0) }
    if ($record.handheld) {
        $hand = $record.handheld
        $handSource = Join-Path $sourceRoot $hand.texture
        $handEffect = Join-Path $sourceRoot $hand.effect_source
        $handEffectHash = Get-SourceHash $handEffect
        $handSourceHash = Get-SourceHash $handSource
        if ($handSourceHash -ne $hand.sha256 -or $handEffectHash -ne $hand.effect_sha256) {
            throw "Handheld artwork no longer matches its reviewed record: $($record.id)"
        }
        [CatalogTextureAnimation]::Build($handSource, $handEffect, $handOutput, '', $profile.effect, $strength, 0, 0)
        [CatalogTextureAnimation]::Build($source, $effectSource, '', $texture, $profile.effect, $strength, $offsetX, $offsetY)
    } else {
        [CatalogTextureAnimation]::Build($source, $effectSource, $handOutput, $texture, $profile.effect, $strength, $offsetX, $offsetY)
    }
    $frameEntries = @(for ($phase = 0; $phase -lt 16; $phase++) {
        $duration = [int]$profile.frame_times[$phase]
        if ($duration -lt 1 -or $duration -gt 20) { throw "Invalid frame duration: $($record.id)" }
        [ordered]@{index=$phase; time=$duration}
    })
    foreach ($size in @(32, 64)) {
        $path = if ($size -eq 32) { $texture } else { $handOutput }
        $animation = [ordered]@{animation=[ordered]@{width=$size; height=$size; frametime=$profile.frametime;
            interpolate=$true; frames=$frameEntries}}
        [IO.File]::WriteAllText(($path + '.mcmeta'), ($animation | ConvertTo-Json -Depth 5) + "`n", $utf8)
    }
    $parent = if ($item.kind -eq 'WEAPON') { 'minecraft:item/handheld' } else { 'minecraft:item/generated' }
    if ($item.material -eq 'BOW') { $parent = 'minecraft:item/bow' }
    if ($item.material -eq 'CROSSBOW') { $parent = 'minecraft:item/crossbow' }
    $style = switch ($item.kind) {
        'WEAPON' { $grips.categories.($item.lore[-1].Split(' ')[-1]) }
        'ARMOR'  { 'armor' }
        'ITEM'   { 'material' }
    }
    if (!$style) { throw "No grip profile for $($record.id): $($item.lore[-1])" }
    $hint = $grips.grip_overrides.($record.id)
    if (!$hint) { $hint = $grips.profiles.$style.grip }
    $grip = Get-GripPixel $handSource $hint
    if ($record.handheld.display) {
        $display = Get-HandDisplay $grips.profiles.$style $grip $wrist $record.handheld.display
        $style = 'authored'
    } else {
        $display = Get-HandDisplay $grips.profiles.$style $grip $wrist
    }
    $guiModelId = $namespace + ':item/' + $name
    $handModelId = $namespace + ':item/' + $handName
    $model = [ordered]@{parent='minecraft:item/generated'; textures=[ordered]@{layer0=$guiModelId}}
    $handModel = [ordered]@{parent=$parent; textures=[ordered]@{layer0=$handModelId}; display=$display}
    [IO.File]::WriteAllText((Join-Path $assets ('models\item\' + $handName + '.json')),
        ($handModel | ConvertTo-Json -Depth 7) + "`n", $utf8)
    $definition = [ordered]@{model=[ordered]@{
        type='minecraft:select'; property='minecraft:display_context'
        cases=@([ordered]@{
            when=@('firstperson_righthand', 'firstperson_lefthand', 'thirdperson_righthand', 'thirdperson_lefthand')
            model=[ordered]@{type='minecraft:model'; model=$handModelId}
        })
        fallback=[ordered]@{type='minecraft:model'; model=$guiModelId}
    }}
    [IO.File]::WriteAllText((Join-Path $assets ('models\item\' + $name + '.json')),
        ($model | ConvertTo-Json -Depth 5) + "`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $assets ('items\' + $name + '.json')),
        ($definition | ConvertTo-Json -Depth 7) + "`n", $utf8)
    $entry = [ordered]@{id=$record.id; name=$item.name; kind=$item.kind; rarity=$item.rarity;
        namespace=$namespace; texture=$name; effect=$profile.effect; title=$profile.title;
        description=$profile.description; frametime=$profile.frametime; frame_times=$profile.frame_times; frames=16;
        source_sha256=$record.sha256; effect_source=$profile.effect_source; effect_sha256=$effectHash;
        icon_size=32; handheld_size=64; handheld_texture=$handName; handheld_source_sha256=$handSourceHash;
        handheld_effect_sha256=$handEffectHash; grip_profile=$style; grip_pixel=$grip}
    if ($profile.effect_offset) { $entry.effect_offset = $profile.effect_offset }
    $entry.source_catalog = Split-Path -Leaf $sourceRoot
    $manifest += $entry
}
$expected = if ($PreviewIds) { $PreviewIds.Count } else { $catalog.Count }
if ($manifest.Count -ne $expected) { throw "Expected $expected reviewed items, generated $($manifest.Count)." }
[IO.File]::WriteAllText((Join-Path $packRoot 'catalog-animations.json'),
    (ConvertTo-Json -InputObject @($manifest) -Depth 5) + "`n", $utf8)
Write-Output "Generated $($manifest.Count) animated 32x32 icons and 64x64 held textures."
