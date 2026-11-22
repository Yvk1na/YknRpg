param([switch]$Pilot, [string]$SourceDirectory, [string]$PackDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
. (Join-Path $PSScriptRoot 'catalog_hand_poses.ps1')
$project = Split-Path -Parent $PSScriptRoot
$root = Join-Path $project 'docs\design\catalog-32-v2'
$pack = if ($Pilot) { Join-Path $project 'build\catalog-pilot' } else { Join-Path $project 'packs\craftengine' }
if ($SourceDirectory) { $root = [IO.Path]::GetFullPath($SourceDirectory) }
if ($PackDirectory) { $pack = [IO.Path]::GetFullPath($PackDirectory) }
$entries = @{}
foreach ($entry in (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $pack 'catalog-animations.json') | ConvertFrom-Json)) {
    $entries[$entry.id] = $entry
}
$catalog = Get-Content -LiteralPath (Join-Path $root 'catalog.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$ranks = @{COMMON=0; UNCOMMON=1; RARE=2; EPIC=3; LEGENDARY=4; MYTHIC=5}
$ready = @($catalog | Where-Object { $entries.ContainsKey($_.id) } | Sort-Object kind, @{Expression={$ranks[$_.rarity]}}, order)
$previews = if ($Pilot) { Join-Path $project 'build\catalog-pilot-previews' } else { Join-Path $root 'previews' }
New-Item -ItemType Directory -Path $previews -Force | Out-Null
$font = New-Object System.Drawing.Font 'Microsoft YaHei', 10
$titleFont = New-Object System.Drawing.Font 'Microsoft YaHei', 16, ([System.Drawing.FontStyle]::Bold)
$textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(235, 238, 244))
$slotBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(68, 74, 85))
$icons = @{}
foreach ($item in $ready) {
    $entry = $entries[$item.id]
    $assets = Join-Path $pack ('resourcepack/assets/' + $entry.namespace)
    $icons[$item.id] = [Drawing.Bitmap]::new((Join-Path $assets ('textures/item/' + $entry.texture + '.png')))
}
function Draw-Slot($graphics, $icon, [int]$left, [int]$top, [int]$scale, [bool]$hotbar) {
    $side = 18 * $scale
    $background = if ($hotbar) { [Drawing.Brushes]::DarkSlateGray } else { [Drawing.Brushes]::DarkGray }
    $graphics.FillRectangle([Drawing.Brushes]::DimGray, $left, $top, $side, $side)
    $graphics.FillRectangle([Drawing.Brushes]::WhiteSmoke, $left + $scale, $top + $scale, $side - $scale, $side - $scale)
    $rect = [Drawing.Rectangle]::new($left + $scale, $top + $scale, 16 * $scale, 16 * $scale)
    $graphics.FillRectangle($background, $rect)
    $graphics.DrawImage($icon, $rect, 0, 0, 32, 32, [Drawing.GraphicsUnit]::Pixel)
}
function New-BoardGraphics($board) {
    $g = [Drawing.Graphics]::FromImage($board)
    $g.Clear([Drawing.Color]::FromArgb(24,28,37))
    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
    return $g
}
try {
    for ($offset = 0; !$Pilot -and $offset -lt $ready.Count; $offset += 36) {
        $page = [int]($offset / 36) + 1
        $count = [Math]::Min(36, $ready.Count - $offset)
        $rows = [int][Math]::Ceiling($count / 6.0)
        $board = New-Object System.Drawing.Bitmap 1120, (80 + $rows * 188)
        $graphics = New-BoardGraphics $board
        try {
            $graphics.DrawString("32x32 | $($ready.Count) / 327 | $page", $titleFont, $textBrush, 20, 16)
            for ($index = 0; $index -lt $count; $index++) {
                $item = $ready[$offset + $index]
                $left = 20 + ($index % 6) * 182
                $top = 70 + [int][Math]::Floor($index / 6) * 188
                $graphics.FillRectangle($slotBrush, $left, $top, 166, 144)
                $graphics.DrawImage($icons[$item.id], [Drawing.Rectangle]::new($left + 19, $top + 8, 128, 128),
                    0, 0, 32, 32, [Drawing.GraphicsUnit]::Pixel)
                $graphics.DrawString($item.name, $font, $textBrush,
                    [System.Drawing.RectangleF]::new($left, $top + 147, 171, 35))
            }
            $board.Save((Join-Path $previews ('page-{0:D2}.png' -f $page)), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $board.Dispose() }
    }
    # Each 18-unit inventory slot contains 16 units of item, here at GUI scale 2.
    $kinds = @('ITEM','WEAPON','ARMOR')
    $board = [Drawing.Bitmap]::new(1038, 875)
    $graphics = New-BoardGraphics $board
    try {
        for ($col = 0; $col -lt 3; $col++) {
            $group = @($ready | Where-Object kind -eq $kinds[$col])
            $left = 14 + 344 * $col
            $graphics.DrawString(($kinds[$col] + '  ' + $group.Count), $titleFont, $textBrush, $left, 8)
            $top = 45
            foreach ($rarity in ($ranks.Keys | Sort-Object { $ranks[$_] })) {
                $row = @($group | Where-Object rarity -eq $rarity)
                if (!$row.Count) { continue }
                $graphics.DrawString($rarity, $font, $textBrush, $left, $top)
                $top += 22
                for ($i = 0; $i -lt $row.Count; $i++) {
                    Draw-Slot $graphics $icons[$row[$i].id] ($left + ($i % 9) * 36) ($top + [Math]::Floor($i / 9) * 36) 2 $false
                }
                $top += [Math]::Ceiling($row.Count / 9) * 36 + 6
            }
        }
        $board.Save((Join-Path $previews 'catalog-slots.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $board.Dispose() }

    # Orthographic inspection of the released transforms at one common wrist per view.
    $referenceId = 'YKI_SOULPAWN_REQUIEM'
    $wrist = Get-HandWrist
    $samples = @('YKI_FORGED_IRON','YKI_WRAITHFIRE_DRAGONHEART',$referenceId,'YKI_DRAGONSPINE_LANCE',
        'YKI_SKULLBARRAGE_LONGBOW','YKI_PRISM_HANDCANNON','YKI_BLACK_SUN_CODEX','YKI_EXPEDITION_WARHAMMER','YKI_OSSUARY_DRAGOON_HELMET')
    if ($SourceDirectory) { $samples = @($ready | Select-Object -First 9 -ExpandProperty id) }
    $board = [Drawing.Bitmap]::new(948, 578)
    $graphics = New-BoardGraphics $board
    try {
        $graphics.DrawString('GUI 1x / 2x     |     First / third person - projected at the marked grip', $titleFont, $textBrush, 14, 8)
        for ($i = 0; $i -lt $samples.Count; $i++) {
            $entry = $entries[$samples[$i]]
            if (!$entry) { continue }
            $left = 12 + ($i % 3) * 312
            $top = 48 + [Math]::Floor($i / 3) * 176
            $graphics.FillRectangle($slotBrush, $left, $top, 304, 166)
            $graphics.DrawString($entry.name, $font, $textBrush, $left + 7, $top + 5)
            Draw-Slot $graphics $icons[$entry.id] ($left + 7) ($top + 62) 1 $true
            Draw-Slot $graphics $icons[$entry.id] ($left + 33) ($top + 53) 2 $false
            $assets = Join-Path $pack ('resourcepack/assets/' + $entry.namespace)
            $model = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $assets ('models/item/' + $entry.handheld_texture + '.json')) | ConvertFrom-Json
            $image = [Drawing.Bitmap]::new((Join-Path $assets ('textures/item/' + $entry.handheld_texture + '.png')))
            try {
                foreach ($view in @('first','third')) {
                    $context = $view + 'person_righthand'
                    $pose = $model.display.$context
                    $anchor = $wrist[$view]
                    $cx = if ($view -eq 'first') { $left + 119 } else { $left + 223 }
                    $cy = $top + 113
                    $points = [Drawing.PointF[]]@(foreach ($corner in @(@(-0.5,-0.5), @(63.5,-0.5), @(-0.5,63.5))) {
                        $pt = Get-ModelPoint $corner $pose.rotation $pose.scale
                        $dx, $dy, $dz = @(for ($k=0; $k -lt 3; $k++) {
                            $pt[$k] + $pose.translation[$k] - $anchor[$k]
                        })
                        [Drawing.PointF]::new(($cx + 4.0 * (0.48 * $dx + 0.877 * $dz)), ($cy - 4.0 * $dy))
                    })
                    $graphics.DrawImage($image, $points, [Drawing.RectangleF]::new(0,0,64,64), [Drawing.GraphicsUnit]::Pixel)
                    $graphics.DrawEllipse([Drawing.Pens]::Aquamarine, $cx - 4, $cy - 4, 8, 8)
                    $graphics.DrawString($view, $font, $textBrush, $cx - 42, $top + 148)
                }
            } finally { $image.Dispose() }
        }
        $board.Save((Join-Path $previews 'catalog-hand-poses.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $board.Dispose() }
} finally {
    foreach ($icon in $icons.Values) { $icon.Dispose() }
    $font.Dispose(); $titleFont.Dispose(); $textBrush.Dispose(); $slotBrush.Dispose()
}
Write-Output "Previewed $($ready.Count) / 327 item textures."
