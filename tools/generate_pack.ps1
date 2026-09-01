$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$projectRoot = Split-Path -Parent $PSScriptRoot
$assetRoot = Join-Path $projectRoot 'packs\craftengine\resourcepack\assets\yknitems'
$textureRoot = Join-Path $assetRoot 'textures\item'
$modelRoot = Join-Path $assetRoot 'models\item'
$itemRoot = Join-Path $assetRoot 'items'
if (Test-Path -LiteralPath $assetRoot -PathType Container) {
    Remove-Item -LiteralPath $assetRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $textureRoot, $modelRoot, $itemRoot | Out-Null

$assets = @(
    @{ Id = 'tempered_ingot'; Kind = 'component'; Stage = 'early' },
    @{ Id = 'resonant_crystal'; Kind = 'component'; Stage = 'mid' },
    @{ Id = 'eclipse_core'; Kind = 'component'; Stage = 'late' },
    @{ Id = 'gui_crafting_table'; Kind = 'table'; Stage = 'gui' },
    @{ Id = 'gui_arrow'; Kind = 'arrow'; Stage = 'gui' }
)

$palettes = @{
    early = @('#542C1C', '#C9783D', '#FFD166', '#E7F5A9')
    mid = @('#25164B', '#734BC5', '#63D4FF', '#F2D6FF')
    late = @('#120F1D', '#50256E', '#C946FF', '#FFF2A6')
    gui = @('#3D2A1F', '#9B6A3C', '#D5D9DC', '#78E08F')
}

function Set-PixelSafe {
    param($Bitmap, [int]$X, [int]$Y, $Color)
    if ($X -ge 0 -and $X -lt 16 -and $Y -ge 0 -and $Y -lt 16) {
        $Bitmap.SetPixel($X, $Y, $Color)
    }
}

function Draw-Line {
    param($Bitmap, [int]$X1, [int]$Y1, [int]$X2, [int]$Y2, $Color, [int]$Width = 1)
    $graphics = [System.Drawing.Graphics]::FromImage($Bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
        $pen = New-Object System.Drawing.Pen($Color, $Width)
        try { $graphics.DrawLine($pen, $X1, $Y1, $X2, $Y2) } finally { $pen.Dispose() }
    } finally { $graphics.Dispose() }
}

function Draw-Asset {
    param($Asset, [string]$Path)
    $colors = @($palettes[$Asset.Stage] | ForEach-Object { [System.Drawing.ColorTranslator]::FromHtml($_) })
    $bitmap = New-Object System.Drawing.Bitmap 16, 16, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $bitmap.SetResolution(96, 96)
        $bitmap.MakeTransparent()
        switch ($Asset.Kind) {
            'component' {
                for ($y = 3; $y -le 12; $y++) {
                    $half = [Math]::Min($y - 2, 13 - $y)
                    for ($x = 8 - $half; $x -le 7 + $half; $x++) { Set-PixelSafe $bitmap $x $y $colors[1] }
                }
                Draw-Line $bitmap 5 6 10 9 $colors[2] 1
                Set-PixelSafe $bitmap 7 5 $colors[3]
            }
            'sword' {
                Draw-Line $bitmap 3 13 12 4 $colors[0] 3
                Draw-Line $bitmap 4 12 12 4 $colors[1] 2
                Draw-Line $bitmap 6 12 3 9 $colors[2] 2
                Set-PixelSafe $bitmap 12 3 $colors[3]
            }
            'greatsword' {
                Draw-Line $bitmap 3 13 12 4 $colors[0] 5
                Draw-Line $bitmap 4 12 12 4 $colors[1] 3
                Draw-Line $bitmap 6 13 2 9 $colors[2] 2
                Set-PixelSafe $bitmap 12 3 $colors[3]
            }
            'dagger' {
                Draw-Line $bitmap 4 12 10 6 $colors[0] 3
                Draw-Line $bitmap 5 11 10 6 $colors[2] 2
                Draw-Line $bitmap 6 12 3 9 $colors[1] 2
                Set-PixelSafe $bitmap 10 5 $colors[3]
            }
            'bow' {
                Draw-Line $bitmap 5 2 12 8 $colors[1] 2
                Draw-Line $bitmap 12 8 5 14 $colors[1] 2
                Draw-Line $bitmap 5 2 5 14 $colors[3] 1
                Draw-Line $bitmap 3 8 13 8 $colors[2] 1
                Set-PixelSafe $bitmap 13 8 $colors[3]
            }
            'spear' {
                Draw-Line $bitmap 2 14 12 4 $colors[0] 2
                Draw-Line $bitmap 3 13 12 4 $colors[1] 1
                Draw-Line $bitmap 10 3 14 2 $colors[2] 2
                Draw-Line $bitmap 14 2 13 6 $colors[3] 2
            }
            'staff' {
                Draw-Line $bitmap 3 14 10 6 $colors[0] 3
                Draw-Line $bitmap 4 13 10 6 $colors[1] 1
                for ($y = 2; $y -le 7; $y++) {
                    for ($x = 8; $x -le 13; $x++) {
                        if (([Math]::Abs($x - 10.5) + [Math]::Abs($y - 4.5)) -le 3.2) { Set-PixelSafe $bitmap $x $y $colors[2] }
                    }
                }
                Set-PixelSafe $bitmap 10 3 $colors[3]
            }
            'table' {
                for ($y = 3; $y -le 13; $y++) { for ($x = 2; $x -le 13; $x++) { Set-PixelSafe $bitmap $x $y $colors[1] } }
                Draw-Line $bitmap 2 3 13 3 $colors[3] 2
                Draw-Line $bitmap 2 8 13 8 $colors[0] 1
                Draw-Line $bitmap 6 4 6 13 $colors[0] 1
                Draw-Line $bitmap 10 4 10 13 $colors[0] 1
            }
            'arrow' {
                Draw-Line $bitmap 2 8 12 8 $colors[2] 3
                Draw-Line $bitmap 9 4 13 8 $colors[3] 3
                Draw-Line $bitmap 13 8 9 12 $colors[3] 3
            }
        }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
foreach ($asset in $assets) {
    Draw-Asset $asset (Join-Path $textureRoot ($asset.Id + '.png'))
    $parent = 'minecraft:item/handheld'
    if ($asset.Kind -in @('component', 'table', 'arrow')) { $parent = 'minecraft:item/generated' }
    $modelJson = [ordered]@{
        parent = $parent
        textures = [ordered]@{ layer0 = 'yknitems:item/' + $asset.Id }
    } | ConvertTo-Json -Depth 4
    $itemJson = [ordered]@{
        model = [ordered]@{ type = 'minecraft:model'; model = 'yknitems:item/' + $asset.Id }
    } | ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText((Join-Path $modelRoot ($asset.Id + '.json')), $modelJson + "`n", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $itemRoot ($asset.Id + '.json')), $itemJson + "`n", $utf8)
}

Write-Host ("Generated {0} YknItems CraftEngine assets." -f $assets.Count)
