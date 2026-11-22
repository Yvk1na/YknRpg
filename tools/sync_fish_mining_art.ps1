param(
    [Parameter(Mandatory)][ValidateSet('yknfish','yknmining')][string]$Plugin,
    [switch]$AllowPartial
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$staged = Join-Path $project "build/fish-mining-art/$Plugin/plugins"
$catalog = Get-Content (Join-Path $project 'docs/design/fish-mining/items.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$items = @($catalog | Where-Object plugin -eq $Plugin)
$target = switch ($Plugin) {
    yknfish { 'C:\Users\y\IdeaProjects\YknFish' }
    yknmining { 'D:\codex projects\YknMining' }
}
$ready = @(foreach ($item in $items) {
    $slug = ($item.model -split ':',2)[1]
    $definition = Join-Path $staged "CraftEngine/resources/$Plugin/resourcepack/assets/$Plugin/items/$slug.json"
    $texture = Join-Path $staged "CraftEngine/resources/$Plugin/resourcepack/assets/$Plugin/textures/item/$slug.png"
    if ((Test-Path -LiteralPath $definition -PathType Leaf) -and (Test-Path -LiteralPath $texture -PathType Leaf)) {
        $item
    } elseif (!$AllowPartial) {
        throw "Missing staged model or texture: $($item.id)"
    }
})
if (!$ready.Count) { throw "No verified staged artwork for $Plugin." }
$data = Join-Path $target 'integration'
foreach ($folder in @("CraftEngine/resources/$Plugin", 'MythicMobs/items')) {
    $from = Join-Path $staged $folder
    foreach ($file in Get-ChildItem -LiteralPath $from -Recurse -File) {
        $relative = $file.FullName.Substring($staged.Length + 1)
        $destination = Join-Path $data $relative
        if ((Test-Path -LiteralPath $destination) -and
            (Get-FileHash -LiteralPath $destination).Hash -eq (Get-FileHash -LiteralPath $file.FullName).Hash) { continue }
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
    }
}
Write-Output "Integrated $($ready.Count) of $($items.Count) staged item models in $target."
