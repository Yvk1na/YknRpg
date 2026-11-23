param([int]$PageSize = 36, [switch]$PendingOnly)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'docs\design\catalog-32-v2'
$catalog = Get-Content (Join-Path $root 'catalog.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$records = @(Get-ChildItem (Join-Path $root 'completed') -Filter '*.json' | Sort-Object Name | ForEach-Object {
    Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
})
if ($PendingOnly) { $records = @($records | Where-Object visual_review -ne 'passed') }
$prefix = if ($PendingOnly) { 'pending' } else { 'draft' }
$font = [Drawing.Font]::new('Microsoft YaHei', 9)
try {
    for ($start = 0; $start -lt $records.Count; $start += $PageSize) {
        $page = @($records | Select-Object -Skip $start -First $PageSize)
        $board = [Drawing.Bitmap]::new(900, [int][Math]::Ceiling($page.Count / 6.0) * 180)
        $g = [Drawing.Graphics]::FromImage($board)
        try {
            $g.Clear([Drawing.Color]::FromArgb(38,42,51))
            $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            for ($i = 0; $i -lt $page.Count; $i++) {
                $record = $page[$i]
                $bmp = [Drawing.Bitmap]::new((Join-Path $root $record.texture))
                try {
                    $x = ($i % 6) * 150; $y = [int][Math]::Floor($i / 6) * 180
                    $g.DrawImage($bmp, $x + 11, $y, 128, 128)
                    $g.DrawImage($bmp, $x + 11, $y + 132, 32, 32)
                    $name = ($catalog | Where-Object id -eq $record.id).name
                    $g.DrawString($name, $font, [Drawing.Brushes]::White, $x + 45, $y + 135)
                } finally { $bmp.Dispose() }
            }
            $stem = "$prefix-{0:d2}" -f ([int]($start / $PageSize) + 1)
            $board.Save((Join-Path $root "previews\$stem.png"))
            @($page.id) | ConvertTo-Json | Set-Content (Join-Path $root "previews\$stem-ids.json") -Encoding UTF8
        } finally { $g.Dispose(); $board.Dispose() }
    }
} finally { $font.Dispose() }
Write-Output "Previewed $($records.Count) draft sprites."
