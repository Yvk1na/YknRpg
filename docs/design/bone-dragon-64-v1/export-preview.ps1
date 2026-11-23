$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$textures = Join-Path $PSScriptRoot 'textures'
$sources = Join-Path $PSScriptRoot 'source-images'
New-Item -ItemType Directory -Force -Path $textures, $sources | Out-Null

foreach ($asset in $manifest.assets) {
    $sourcePath = Join-Path $sources ($asset.id + '.png')
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        Copy-Item -LiteralPath $asset.generated_source -Destination $sourcePath
    }
    $source = [System.Drawing.Bitmap]::FromFile($sourcePath)
    $result = New-Object System.Drawing.Bitmap 64, 64, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($result)
    try {
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $destination = New-Object System.Drawing.Rectangle 0, 0, 64, 64
        $graphics.DrawImage($source, $destination, 0, 0, $source.Width, $source.Height, [System.Drawing.GraphicsUnit]::Pixel)
        $result.Save((Join-Path $textures ($asset.id + '.png')), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $result.Dispose()
        $source.Dispose()
    }
}

function New-PreviewCanvas {
    param([int]$Width, [int]$Height)
    $bitmap = New-Object System.Drawing.Bitmap $Width, $Height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#c6c6c6'))
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    return @{ Bitmap = $bitmap; Graphics = $graphics }
}

function Draw-Texture {
    param($Graphics, [string]$Path, [int]$Left, [int]$Top)
    $source = [System.Drawing.Bitmap]::FromFile($Path)
    try {
        $rectangle = New-Object System.Drawing.Rectangle $Left, $Top, 256, 256
        $Graphics.DrawImage($source, $rectangle, 0, 0, $source.Width, $source.Height, [System.Drawing.GraphicsUnit]::Pixel)
    } finally { $source.Dispose() }
}

$font = New-Object System.Drawing.Font 'Microsoft YaHei UI', 12
$titleFont = New-Object System.Drawing.Font 'Microsoft YaHei UI', 20, ([System.Drawing.FontStyle]::Bold)
$ink = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml('#24282e'))
$slot = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml('#8b8b8b'))
$border = New-Object System.Drawing.Pen ([System.Drawing.ColorTranslator]::FromHtml('#555555')), 2
$canvas = New-PreviewCanvas 944 1044
try {
    $canvas.Graphics.DrawString($manifest.title, $titleFont, $ink, 24, 16)
    $canvas.Graphics.DrawString($manifest.subtitle, $font, $ink, 24, 52)
    $index = 0
    foreach ($asset in $manifest.assets) {
        $left = 24 + ($index % 3) * 304
        $top = 90 + [int][Math]::Floor($index / 3) * 312
        $canvas.Graphics.FillRectangle($slot, $left, $top, 288, 272)
        $canvas.Graphics.DrawRectangle($border, $left, $top, 288, 272)
        Draw-Texture $canvas.Graphics (Join-Path $textures ($asset.id + '.png')) ($left + 16) ($top + 8)
        $canvas.Graphics.DrawString($asset.name, $font, $ink, $left + 4, $top + 279)
        $index++
    }
    $canvas.Bitmap.Save((Join-Path $PSScriptRoot 'bone-dragon-64-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $canvas.Graphics.Dispose()
    $canvas.Bitmap.Dispose()
}

$comparison = New-PreviewCanvas 632 1000
try {
    $comparison.Graphics.DrawString($manifest.comparison_title, $titleFont, $ink, 24, 16)
    $comparison.Graphics.DrawString('16 x 16', $font, $ink, 24, 60)
    $comparison.Graphics.DrawString('64 x 64', $font, $ink, 328, 60)
    $sampleIds = @('wingbreaker_greatsword', 'ossuary_dragoon_chestplate', 'wraithfire_dragonheart')
    $oldRoot = Join-Path $PSScriptRoot '..\..\..\packs\craftengine\resourcepack\assets\yknrpg_bone\textures\item'
    for ($index = 0; $index -lt $sampleIds.Count; $index++) {
        $id = $sampleIds[$index]
        $top = 100 + $index * 296
        foreach ($left in @(24, 328)) {
            $comparison.Graphics.FillRectangle($slot, $left, $top, 280, 280)
            $comparison.Graphics.DrawRectangle($border, $left, $top, 280, 280)
        }
        Draw-Texture $comparison.Graphics (Join-Path $oldRoot ($id + '.png')) 36 ($top + 12)
        Draw-Texture $comparison.Graphics (Join-Path $textures ($id + '.png')) 340 ($top + 12)
    }
    $comparison.Bitmap.Save((Join-Path $PSScriptRoot 'bone-dragon-16-vs-64.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $comparison.Graphics.Dispose()
    $comparison.Bitmap.Dispose()
    $font.Dispose()
    $titleFont.Dispose()
    $ink.Dispose()
    $slot.Dispose()
    $border.Dispose()
}

$checks = foreach ($asset in $manifest.assets) {
    $image = [System.Drawing.Bitmap]::FromFile((Join-Path $textures ($asset.id + '.png')))
    try {
        if ($image.Width -ne 64 -or $image.Height -ne 64) { throw 'Incorrect output dimensions' }
        $transparent = 0
        $visible = 0
        for ($y = 0; $y -lt 64; $y++) {
            for ($x = 0; $x -lt 64; $x++) {
                if ($image.GetPixel($x, $y).A -eq 0) { $transparent++ } else { $visible++ }
            }
        }
        if ($transparent -eq 0 -or $visible -eq 0) { throw 'Texture must have visible pixels and transparent background' }
        [ordered]@{ id = $asset.id; width = $image.Width; height = $image.Height; visible_pixels = $visible; transparent_pixels = $transparent }
    } finally { $image.Dispose() }
}
$checks | ConvertTo-Json | Write-Output
