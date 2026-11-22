param(
    [Parameter(Mandatory = $true)][ValidatePattern('^YK[IFM]_[A-Z0-9_]+$')][string]$Id,
    [Parameter(Mandatory = $true)][string]$Source,
    [string]$PromptFile,
    [string]$OutputRoot,
    [switch]$SolidAlpha
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'docs\design\catalog-64'
if ($OutputRoot) { $root = [IO.Path]::GetFullPath($OutputRoot) }
$destination = Join-Path $root ('textures\' + $Id.Substring(4).ToLowerInvariant() + '.png')
$record = Join-Path $root ('completed\' + $Id + '.json')
if ($PromptFile) {
    $prompt = Get-Content -LiteralPath $PromptFile -Raw -Encoding UTF8
} else {
    $plan = Get-Content -LiteralPath (Join-Path $root 'plan.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $prompt = ($plan.items | Where-Object { $_.id -eq $Id }).prompt
    if (-not $prompt) { throw "Missing prompt: $Id" }
}
if (Test-Path -LiteralPath $record) { throw "Already exported: $Id" }
New-Item -ItemType Directory -Force -Path (Split-Path $destination), (Split-Path $record) | Out-Null
$original = [System.Drawing.Image]::FromFile($Source)
$bitmap = New-Object System.Drawing.Bitmap 64, 64, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $scale = [Math]::Min(64.0 / $original.Width, 64.0 / $original.Height)
    $width = [Math]::Max(1, [int][Math]::Round($original.Width * $scale))
    $height = [Math]::Max(1, [int][Math]::Round($original.Height * $scale))
    $graphics.DrawImage($original, [System.Drawing.Rectangle]::new(
        [int][Math]::Floor((64 - $width) / 2), [int][Math]::Floor((64 - $height) / 2), $width, $height))
    $transparent = 0; $visible = 0
    for ($row = 0; $row -lt 64; $row++) {
        for ($column = 0; $column -lt 64; $column++) {
            $alpha = $bitmap.GetPixel($column, $row).A
            if ($SolidAlpha) {
                $alpha = if ($alpha -lt 128) { 0 } else { 255 }
                $bitmap.SetPixel($column, $row, [Drawing.Color]::FromArgb($alpha, $bitmap.GetPixel($column, $row)))
            }
            if ($alpha -eq 0) { $transparent++ }
            if ($alpha -gt 127) { $visible++ }
        }
    }
    if ($transparent -lt 100 -or $visible -lt 100) { throw "Invalid sprite alpha: $Id; source=$Source" }
    $bitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose(); $bitmap.Dispose(); $original.Dispose()
}
[ordered]@{
    id = $Id
    source = $Source
    texture = ('textures/' + $Id.Substring(4).ToLowerInvariant() + '.png')
    prompt = $prompt
    mode = 'imagegen built-in; nearest-neighbor 64x64 export'
    solid_alpha = $SolidAlpha.IsPresent
    sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    visual_review = 'pending'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $record -Encoding UTF8
Write-Output "Exported $Id"
