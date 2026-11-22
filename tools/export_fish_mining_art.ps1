param([switch]$RequireComplete)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
. (Join-Path $PSScriptRoot 'catalog_hand_poses.ps1')
$project = Split-Path -Parent $PSScriptRoot
$design = Join-Path $project 'docs/design/fish-mining'
$masters = Join-Path $design 'masters'
$staging = Join-Path $project 'build/fish-mining-art'
$items = @(Get-Content -LiteralPath (Join-Path $design 'items.json') -Raw -Encoding UTF8 | ConvertFrom-Json)
$ready = @($items | Where-Object {
    (Test-Path -LiteralPath (Join-Path $masters "completed/$($_.id).json")) -or (
        (Test-Path -LiteralPath (Join-Path $design "sources/$($_.id).png")) -and
        (Test-Path -LiteralPath (Join-Path $design "sources/$($_.id).txt")))
})
if ($RequireComplete -and $ready.Count -ne $items.Count) {
    throw "Expected $($items.Count) original sprites but found $($ready.Count)."
}
if (!$ready.Count) { throw 'No original sprites are ready.' }
$wrist = Get-HandWrist
$handContexts = @('firstperson_righthand', 'firstperson_lefthand', 'thirdperson_righthand', 'thirdperson_lefthand')
$handProfile = @{
    pickaxe = @{ first = @{ rotation = @(0,-90,25); scale = .90 }; third = @{ rotation = @(0,-90,55); scale = 1.07 } }
    fishing_rod = @{ first = @{ rotation = @(0,-90,25); scale = .85 }; third = @{ rotation = @(0,-90,55); scale = 1.06 } }
}
$tints = @{
    yknfish = [Drawing.Color]::FromArgb(26,35,40)
    yknmining = [Drawing.Color]::FromArgb(40,33,32)
}
function Write-Model($Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 10) + "`n")
}
function Export-Icon([Drawing.Bitmap]$Sprite, [string]$Destination) {
    $left = 64; $top = 64; $right = 0; $bottom = 0
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            if ($Sprite.GetPixel($x,$y).A -eq 0) { continue }
            $left = [Math]::Min($left,$x)
            $top = [Math]::Min($top,$y)
            $right = [Math]::Max($right,$x + 1)
            $bottom = [Math]::Max($bottom,$y + 1)
        }
    }
    $width = $right - $left; $height = $bottom - $top
    if ($width -le 0 -or $height -le 0) { throw "Empty artwork: $Destination" }
    $scale = 30.0 / [Math]::Max($width,$height)
    $targetWidth = [int][Math]::Round($width * $scale)
    $targetHeight = [int][Math]::Round($height * $scale)
    $dst = [Drawing.Rectangle]::new([int][Math]::Floor((32-$targetWidth)/2), [int][Math]::Floor((32-$targetHeight)/2), $targetWidth, $targetHeight)
    $icon = [Drawing.Bitmap]::new(32,32)
    $graphics = [Drawing.Graphics]::FromImage($icon)
    try {
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
        $graphics.DrawImage($Sprite,$dst,$left,$top,$width,$height,[Drawing.GraphicsUnit]::Pixel)
        $icon.Save($Destination,[Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $icon.Dispose()
    }
}
foreach ($item in $ready) {
    $id = $item.id
    $ns, $slug = $item.model -split ':', 2
    if ($item.plugin -ne $ns -or $slug -notmatch '^[a-z0-9_]+$') { throw "Invalid item model: $($item.model)" }
    $masterRecord = Join-Path $masters "completed/$id.json"
    if (!(Test-Path -LiteralPath $masterRecord)) {
        & (Join-Path $PSScriptRoot 'export_catalog_texture.ps1') -Id $id `
            -Source (Join-Path $design "sources/$id.png") -PromptFile (Join-Path $design "sources/$id.txt") `
            -OutputRoot $masters -SolidAlpha | Out-Null
    }
    $masterPath = Join-Path $masters ('textures/' + $id.Substring(4).ToLowerInvariant() + '.png')
    $assets = Join-Path $staging "$ns/plugins/CraftEngine/resources/$ns/resourcepack/assets/$ns"
    foreach ($dir in @('textures/item','models/item','items')) { New-Item -ItemType Directory -Path (Join-Path $assets $dir) -Force | Out-Null }
    $sprite = [Drawing.Bitmap]::new($masterPath)
    try { Export-Icon $sprite (Join-Path $assets "textures/item/$slug.png") }
    finally { $sprite.Dispose() }
    $iconModel = "$ns`:item/$slug"
    $definition = [ordered]@{ type = 'minecraft:model'; model = $iconModel }
    Write-Model (Join-Path $assets "models/item/$slug.json") ([ordered]@{ parent = 'minecraft:item/generated'; textures = @{ layer0 = $iconModel } })
    if ($handProfile.ContainsKey($item.kind)) {
        $heldSlug = $slug + '_in_hand'
        $heldModel = "$ns`:item/$heldSlug"
        $grip = Get-GripPixel $masterPath @(14,51)
        $display = Get-HandDisplay $handProfile[$item.kind] $grip $wrist
        Copy-Item -LiteralPath $masterPath -Destination (Join-Path $assets "textures/item/$heldSlug.png") -Force
        Write-Model (Join-Path $assets "models/item/$heldSlug.json") ([ordered]@{
            parent = 'minecraft:item/handheld'; textures = @{ layer0 = $heldModel }; display = $display
        })
        $definition = [ordered]@{
            type = 'minecraft:select'; property = 'minecraft:display_context'
            cases = @(@{ when = $handContexts; model = @{ type = 'minecraft:model'; model = $heldModel } })
            fallback = $definition
        }
    }
    Write-Model (Join-Path $assets "items/$slug.json") @{ model = $definition }
}
$preview = Join-Path $design 'previews'
New-Item -ItemType Directory -Path $preview -Force | Out-Null
$font = [Drawing.Font]::new('Microsoft YaHei',11)
try {
    foreach ($group in ($ready | Group-Object plugin)) {
        $rows = [int][Math]::Ceiling($group.Count / 8.0)
        $bitmap = [Drawing.Bitmap]::new(1088,$rows * 100)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear($tints[$group.Name])
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $i = 0
            foreach ($item in $group.Group) {
                $ns, $slug = $item.model -split ':', 2
                $icon = [Drawing.Bitmap]::new((Join-Path $staging "$ns/plugins/CraftEngine/resources/$ns/resourcepack/assets/$ns/textures/item/$slug.png"))
                try {
                    $x = ($i % 8) * 136
                    $y = [int][Math]::Floor($i / 8) * 100
                    $graphics.DrawImage($icon,$x + 10,$y + 5,64,64)
                    $graphics.DrawImage($icon,$x + 84,$y + 35,32,32)
                    $graphics.DrawString($item.name,$font,[Drawing.Brushes]::WhiteSmoke,$x + 8,$y + 72)
                } finally { $icon.Dispose() }
                $i++
            }
            $bitmap.Save((Join-Path $preview "$($group.Name).png"),[Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
} finally { $font.Dispose() }
Write-Output "Exported $($ready.Count) of $($items.Count) item textures."
