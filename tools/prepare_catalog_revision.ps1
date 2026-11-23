param([string[]]$ReviewedIds, [string[]]$ReviewedMotionIds)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$root = Join-Path $project 'docs\design\catalog-32-v2'
$previous = Join-Path $project 'docs\design\catalog-64'
foreach ($id in $ReviewedIds) {
    if ($id -notmatch '^YKI_[A-Z0-9_]+$') { throw "Invalid item ID: $id" }
    $path = Join-Path $root "completed\$id.json"
    $record = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    $record.visual_review = 'passed'
    $record | Add-Member -Force -NotePropertyName visual_review_notes -NotePropertyValue 'Checked enlarged silhouette and native 32-pixel draft on a dark inventory background.'
    $effect = 'effects\items\' + $id.Substring(4).ToLowerInvariant() + '.png'
    if (-not (Test-Path -LiteralPath (Join-Path $root $effect))) {
        Copy-Item -LiteralPath (Join-Path $previous $effect) -Destination (Join-Path $root $effect)
    }
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding UTF8
}
if ($ReviewedMotionIds) {
    $staged = Get-Content (Join-Path $project 'build/catalog-32-v2/catalog-animations.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $checks = Get-Content (Join-Path $root 'previews/motion-checks.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($id in $ReviewedMotionIds) {
        $entry = $staged | Where-Object id -eq $id
        $check = $checks | Where-Object id -eq $id
        if (!$entry -or !$check -or $check.'32'.distinct_frames -lt 3 -or $check.'64'.distinct_frames -lt 3) {
            throw "Animation must have distinct frames at both sizes: $id"
        }
        $path = Join-Path $root "completed/$id.json"
        $record = Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($entry.source_sha256 -ne $record.sha256 -or $entry.effect_sha256 -ne (Get-FileHash (Join-Path $root $entry.effect_source)).Hash) {
            throw "Staged animation no longer matches artwork: $id"
        }
        $record | Add-Member -Force -NotePropertyName motion_review -NotePropertyValue 'passed'
        $record | Add-Member -Force -NotePropertyName motion_review_sha256 -NotePropertyValue $entry.effect_sha256
        $record | ConvertTo-Json -Depth 12 | Set-Content $path -Encoding UTF8
    }
}
$progressPath = Join-Path $root 'progress.json'
$progress = Get-Content -LiteralPath $progressPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($item in $progress.items) {
    $path = Join-Path $root "completed\$($item.id).json"
    if (Test-Path -LiteralPath $path) {
        $record = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
        $item.status = if ($record.motion_review -eq 'passed') { 'motion_reviewed' }
            elseif ($record.visual_review -eq 'passed') { 'static_reviewed' } else { 'static_draft' }
    }
}
$progress | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $progressPath -Encoding UTF8
if ($ReviewedMotionIds -and !$ReviewedIds) { return }
$ready = @(Get-ChildItem -LiteralPath (Join-Path $root 'completed') -Filter '*.json' | ForEach-Object {
    $record = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($record.visual_review -eq 'passed') { $record.id }
})
if ($ready.Count -gt 0) {
    & (Join-Path $PSScriptRoot 'generate_catalog_animations.ps1') -PreviewIds $ready -SourceDirectory $root -OutputDirectory (Join-Path $project 'build\catalog-32-v2')
    & (Join-Path $PSScriptRoot 'preview_catalog_assets.ps1') -SourceDirectory $root -PackDirectory (Join-Path $project 'build\catalog-32-v2')
    & (Join-Path $PSScriptRoot 'preview_catalog_animations.ps1') -HtmlOnly -SourceDirectory $root -PackDirectory (Join-Path $project 'build\catalog-32-v2')
}
