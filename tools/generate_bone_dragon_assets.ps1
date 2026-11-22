$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$projectRoot = Split-Path -Parent $PSScriptRoot
$assetRoot = Join-Path $projectRoot 'packs\craftengine\resourcepack\assets\yknrpg_bone'
$equipmentRoot = Join-Path $assetRoot 'equipment'
$humanoidRoot = Join-Path $assetRoot 'textures\entity\equipment\humanoid'
$leggingsRoot = Join-Path $assetRoot 'textures\entity\equipment\humanoid_leggings'
$previewRoot = Join-Path $projectRoot 'docs\design'
New-Item -ItemType Directory -Force -Path $equipmentRoot, $humanoidRoot, $leggingsRoot, $previewRoot | Out-Null

# Approved 64x64 inventory art; native UV layers retain their authored layout.
$colors = [System.Collections.Generic.Dictionary[string,System.Drawing.Color]]::new([System.StringComparer]::Ordinal)
$colors.Add('k', [System.Drawing.ColorTranslator]::FromHtml('#202631'))
$colors.Add('d', [System.Drawing.ColorTranslator]::FromHtml('#384452'))
$colors.Add('s', [System.Drawing.ColorTranslator]::FromHtml('#56697a'))
$colors.Add('h', [System.Drawing.ColorTranslator]::FromHtml('#8799a0'))
$colors.Add('b', [System.Drawing.ColorTranslator]::FromHtml('#948a7a'))
$colors.Add('B', [System.Drawing.ColorTranslator]::FromHtml('#c8bda8'))
$colors.Add('W', [System.Drawing.ColorTranslator]::FromHtml('#eee3cb'))
$colors.Add('t', [System.Drawing.ColorTranslator]::FromHtml('#15858a'))
$colors.Add('c', [System.Drawing.ColorTranslator]::FromHtml('#61ded5'))
$sprites = [ordered]@{
    wingbreaker_greatsword = $null
    ossuary_dragoon_helmet = $null
    ossuary_dragoon_chestplate = $null
    ossuary_dragoon_leggings = $null
    ossuary_dragoon_boots = $null
    bone_dragon_fang = $null
    ossuary_carapace = $null
    shattered_wingbone = $null
    wraithfire_dragonheart = $null
}
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Fill-Region {
    param($Bitmap, [int]$Left, [int]$Top, [int]$Width, [int]$Height, [string]$Tone)
    for ($py = $Top; $py -lt $Top + $Height; $py++) {
        for ($px = $Left; $px -lt $Left + $Width; $px++) {
            $color = if ((($px * 5 + $py * 3) % 13) -eq 0) { $colors['s'] } else { $colors[$Tone] }
            $Bitmap.SetPixel($px, $py, $color)
        }
    }
}

# 64x32 vanilla humanoid equipment UV layout. The two layers are separate,
# so leggings keep their own texture when mixed with other armor pieces.
$humanoid = New-Object System.Drawing.Bitmap 64, 32, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$leggings = New-Object System.Drawing.Bitmap 64, 32, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
try {
    Fill-Region $humanoid 0 0 32 16 'd'    # helmet
    Fill-Region $humanoid 16 16 24 16 'd'  # chest
    Fill-Region $humanoid 40 16 16 16 'd'  # arms
    Fill-Region $humanoid 0 26 16 6 'd'    # boots: lower legs only
    Fill-Region $humanoid 4 16 8 4 'd'     # soles
    Fill-Region $leggings 16 28 24 4 'd'   # waist: lower torso only
    Fill-Region $leggings 0 16 16 16 'd'   # legs

    for ($face = 0; $face -lt 4; $face++) {
        $x0 = $face * 8
        for ($x = $x0 + 1; $x -lt $x0 + 7; $x++) {
            $humanoid.SetPixel($x, 8, $colors['B'])
            $humanoid.SetPixel($x, 9, $colors['W'])
        }
        $humanoid.SetPixel($x0 + 1, 10, $colors['B'])
        $humanoid.SetPixel($x0 + 6, 10, $colors['B'])
        $humanoid.SetPixel($x0 + 1, 11, $colors['b'])
        $humanoid.SetPixel($x0 + 6, 11, $colors['b'])
        for ($x = $x0 + 2; $x -lt $x0 + 6; $x++) {
            $humanoid.SetPixel($x, 15, $colors['B'])
        }
    }
    # Front face: an opening for the wearer's face and small cyan eye details.
    for ($y = 12; $y -le 14; $y++) {
        for ($x = 10; $x -le 13; $x++) {
            $humanoid.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
        }
    }
    $humanoid.SetPixel(10, 11, $colors['c'])
    $humanoid.SetPixel(13, 11, $colors['c'])
    for ($x = 8; $x -le 23; $x++) {
        $humanoid.SetPixel($x, 2, $colors['B'])
        $humanoid.SetPixel($x, 3, $colors['W'])
    }

    # Chest and arms: mirrored horizontal ribs with a soul-fire heart.
    foreach ($x0 in @(20, 32)) {
        for ($y = 22; $y -le 28; $y += 3) {
            for ($x = $x0; $x -lt $x0 + 8; $x++) {
                if ($x -eq $x0 + 3 -or $x -eq $x0 + 4) { continue }
                $humanoid.SetPixel($x, $y, $colors['W'])
                $humanoid.SetPixel($x, $y + 1, $colors['B'])
            }
        }
    }
    $humanoid.SetPixel(23, 26, $colors['c'])
    $humanoid.SetPixel(24, 26, $colors['c'])
    $humanoid.SetPixel(23, 27, $colors['t'])
    $humanoid.SetPixel(24, 27, $colors['t'])
    for ($x = 40; $x -le 55; $x++) {
        if ($x % 4 -eq 0) { continue }
        $humanoid.SetPixel($x, 21, $colors['B'])
        $humanoid.SetPixel($x, 22, $colors['W'])
        $humanoid.SetPixel($x, 27, $colors['B'])
    }
    for ($x = 0; $x -le 15; $x++) {
        $humanoid.SetPixel($x, 27, $colors['B'])
        $humanoid.SetPixel($x, 28, $colors['W'])
        $humanoid.SetPixel($x, 31, $colors['k'])
        $leggings.SetPixel($x, 23, $colors['B'])
        $leggings.SetPixel($x, 24, $colors['W'])
        $leggings.SetPixel($x, 29, $colors['B'])
    }
    for ($x = 16; $x -le 39; $x++) {
        $leggings.SetPixel($x, 28, $colors['B'])
        $leggings.SetPixel($x, 29, $colors['W'])
        $leggings.SetPixel($x, 31, $colors['b'])
    }
    $humanoid.Save((Join-Path $humanoidRoot 'ossuary_dragoon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $leggings.Save((Join-Path $leggingsRoot 'ossuary_dragoon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $humanoid.Dispose()
    $leggings.Dispose()
}

$equipment = [ordered]@{
    layers = [ordered]@{
        humanoid = @([ordered]@{ texture = 'yknrpg_bone:ossuary_dragoon' })
        humanoid_leggings = @([ordered]@{ texture = 'yknrpg_bone:ossuary_dragoon' })
    }
} | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $equipmentRoot 'ossuary_dragoon.json'), $equipment + "`n", $utf8)

# Preview the exact in-game sprites, enlarged without smoothing.
$preview = New-Object System.Drawing.Bitmap 600, 600, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($preview)
try {
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#c6c6c6'))
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $border = New-Object System.Drawing.Pen ([System.Drawing.ColorTranslator]::FromHtml('#555555')), 2
    $slotBackground = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml('#8b8b8b'))
    try {
        $index = 0
        foreach ($name in $sprites.Keys) {
            $left = 20 + ($index % 3) * 195
            $top = 20 + [Math]::Floor($index / 3) * 195
            $graphics.FillRectangle($slotBackground, $left, $top, 170, 170)
            $graphics.DrawRectangle($border, $left, $top, 170, 170)
            $sprite = [System.Drawing.Image]::FromFile((Join-Path $previewRoot ('bone-dragon-64-v1\textures\' + $name + '.png')))
            try { $graphics.DrawImage($sprite, $left + 13, $top + 13, 144, 144) } finally { $sprite.Dispose() }
            $index++
        }
    } finally { $slotBackground.Dispose(); $border.Dispose() }
    $preview.Save((Join-Path $previewRoot 'bone-dragon-items-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $preview.Dispose()
}
Write-Host 'Generated the bone dragon preview and two wearable armor layers.'
