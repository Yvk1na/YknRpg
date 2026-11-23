param([string]$Id = 'template', [string]$Source)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'docs\design\catalog-64'
$destination = Join-Path $root 'armor'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
# Native Java armor UVs at four times vanilla density, arranged as two layers
# in one square authoring atlas. These rectangles only define the UV mask.
$main = @(@(8,0,16,8), @(0,8,32,8), @(20,16,16,4), @(16,20,24,12),
    @(44,16,8,4), @(40,20,16,12), @(4,16,8,4), @(0,26,16,6))
$legs = @(@(4,16,8,4), @(0,20,16,12), @(16,28,24,4))
$mask = New-Object System.Drawing.Bitmap 256, 256, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($mask)
$brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(190,190,190))
$border = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(90,90,90)), 1
try {
    foreach ($layer in @(@{offset=0; regions=$main}, @{offset=128; regions=$legs})) {
        foreach ($rectangle in $layer.regions) {
            $x = $rectangle[0] * 4; $y = $rectangle[1] * 4 + $layer.offset
            $w = $rectangle[2] * 4; $h = $rectangle[3] * 4
            $graphics.FillRectangle($brush, $x, $y, $w, $h)
            $graphics.DrawRectangle($border, $x, $y, $w - 1, $h - 1)
        }
    }
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $transparent = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Transparent)
    try { $graphics.FillRectangle($transparent, 40, 48, 16, 12) }
    finally { $transparent.Dispose() }
    if (-not $Source) {
        $mask.Save((Join-Path $destination 'uv-template.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output 'Wrote technical UV template.'
        return
    }
    $original = [System.Drawing.Image]::FromFile($Source)
    $atlas = New-Object System.Drawing.Bitmap 256, 256, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $painter = [System.Drawing.Graphics]::FromImage($atlas)
    try {
        $painter.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $painter.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $painter.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $painter.DrawImage($original, 0, 0, 256, 256)
        for ($row = 0; $row -lt 256; $row++) {
            for ($column = 0; $column -lt 256; $column++) {
                if ($mask.GetPixel($column, $row).A -eq 0) {
                    $atlas.SetPixel($column, $row, [System.Drawing.Color]::Transparent)
                }
            }
        }
        foreach ($layer in @(@{offset=0; name='humanoid'}, @{offset=128; name='humanoid_leggings'})) {
            $output = $atlas.Clone([System.Drawing.Rectangle]::new(0,$layer.offset,256,128),
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try { $output.Save((Join-Path $destination ($Id + '-' + $layer.name + '.png')),
                [System.Drawing.Imaging.ImageFormat]::Png) }
            finally { $output.Dispose() }
        }
    } finally { $painter.Dispose(); $atlas.Dispose(); $original.Dispose() }
} finally { $graphics.Dispose(); $brush.Dispose(); $border.Dispose(); $mask.Dispose() }
