param([switch]$PendingOnly)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = Split-Path -Parent $PSScriptRoot
$pack = Join-Path $project 'build\catalog-32-v2'
$root = Join-Path $project 'docs\design\catalog-32-v2'
$entries = @(Get-Content (Join-Path $pack 'catalog-animations.json') -Raw -Encoding UTF8 | ConvertFrom-Json)
if ($PendingOnly) {
    $entries = @($entries | Where-Object {
        (Get-Content (Join-Path $root "completed/$($_.id).json") -Raw -Encoding UTF8 | ConvertFrom-Json).motion_review -ne 'passed'
    })
}
$prefix = if ($PendingOnly) { 'review-motion' } else { 'motion' }
$font = [Drawing.Font]::new('Microsoft YaHei', 9)
try {
    $perPage = 24
    for ($start=0; $start -lt $entries.Count; $start += $perPage) {
        $page = @($entries | Select-Object -Skip $start -First $perPage)
        $board = [Drawing.Bitmap]::new(1056, ([int][Math]::Ceiling($page.Count / 2.0) * 64))
        $g = [Drawing.Graphics]::FromImage($board)
        try {
            $g.Clear([Drawing.Color]::FromArgb(38,42,51))
            $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            for ($i=0; $i -lt $page.Count; $i++) {
                $entry = $page[$i]
                $path = Join-Path $pack ('resourcepack/assets/' + $entry.namespace + '/textures/item/' + $entry.texture + '.png')
                $bmp = [Drawing.Bitmap]::new($path)
                try {
                    $left = ($i % 2) * 528
                    $top = [int][Math]::Floor($i / 2) * 64
                    $g.DrawString($entry.name, $font, [Drawing.Brushes]::White, $left, $top + 34)
                    for ($frame=0; $frame -lt 16; $frame++) {
                        $rect = [Drawing.Rectangle]::new($left + $frame * 32, $top, 32, 32)
                        $g.DrawImage($bmp, $rect, 0, $frame*32, 32, 32, [Drawing.GraphicsUnit]::Pixel)
                    }
                } finally { $bmp.Dispose() }
            }
            $stem = "$prefix-{0:d2}" -f ([int]($start/$perPage)+1)
            $board.Save((Join-Path $root "previews/$stem.png"))
            @($page.id) | ConvertTo-Json | Set-Content (Join-Path $root "previews/$stem-ids.json") -Encoding UTF8
        } finally { $g.Dispose(); $board.Dispose() }
    }
} finally { $font.Dispose() }
Write-Output "Previewed all 16 phases for $($entries.Count) sprites."
