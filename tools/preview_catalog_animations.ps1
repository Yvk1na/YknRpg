param([switch]$HtmlOnly, [string]$SourceDirectory, [string]$PackDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = Split-Path -Parent $PSScriptRoot
$pack = Join-Path $project 'packs\craftengine'
$root = Join-Path $project 'docs\design\catalog-32-v2'
if ($SourceDirectory) { $root = [IO.Path]::GetFullPath($SourceDirectory) }
if ($PackDirectory) { $pack = [IO.Path]::GetFullPath($PackDirectory) }
$manifest = @(Get-Content -LiteralPath (Join-Path $pack 'catalog-animations.json') -Raw -Encoding UTF8 | ConvertFrom-Json)
if ($SourceDirectory -and $manifest[0].source_catalog) {
    $revision = Split-Path -Leaf $root
    $manifest = @($manifest | Where-Object source_catalog -eq $revision)
}
$data = @($manifest | ForEach-Object {
    $textures = Join-Path $pack ('resourcepack\assets\' + $_.namespace + '\textures\item')
    [ordered]@{id=$_.id; name=$_.name; effect=$_.effect; title=$_.title; frame_times=$_.frame_times;
        icon=('data:image/png;base64,' + [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $textures ($_.texture + '.png')))));
        held=('data:image/png;base64,' + [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $textures ($_.handheld_texture + '.png')))))}
}) | ConvertTo-Json -Depth 5 -Compress
$html = @'
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>YknRpg 动态材质预览</title><style>
body{margin:32px;background:#181c25;color:#edf0f6;font:15px system-ui,"Microsoft YaHei",sans-serif}
h1{font-size:26px}button,select{padding:8px 16px;margin-right:12px;background:#303846;color:inherit;border:1px solid #526078;border-radius:6px}
main{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:16px;margin-top:24px}
article{padding:16px;background:#242b37;border-radius:10px}
canvas{display:block;width:32px;height:32px;margin:auto;background:#8b8b8b;border:2px solid #555;border-bottom-color:#eee;border-right-color:#eee;image-rendering:pixelated}
main.held canvas{width:128px;height:128px;background:#444a55;border-color:transparent}
p{margin:12px 0 4px}small{color:#a7b8ce}header{position:sticky;top:0;background:#181c25;padding:8px 0 16px}
</style><header><h1>YknRpg 动态材质</h1><p id="count"></p><button id="play">暂停播放</button>
<select id="view" aria-label="材质"><option value="icon">32×32 图标（2 倍格子）</option><option value="held">64×64 手持纹理（放大）</option></select>
<select id="filter" aria-label="动效主题"><option value="">全部主题</option></select></header><main></main>
<script>
const entries = __CATALOG_DATA__;
const main = document.querySelector('main'), filter = document.querySelector('#filter');
const view = document.querySelector('#view'), play = document.querySelector('#play');
const labels = {soulfire:'魂火',ember:'余烬',arcane:'秘法',starlight:'星光',nature:'生息',tidal:'潮汐',sheen:'微光'};
document.querySelector('#count').textContent = `${entries.length} 件 · 16 帧 · 按游戏时间线插值`;
for (const effect of new Set(entries.map(entry => entry.effect))) {
    filter.add(new Option(labels[effect], effect));
}
const visible = new Set();
const observer = new IntersectionObserver(changes => {
    for (const change of changes) {
        if (change.isIntersecting) visible.add(change.target.entry);
        else visible.delete(change.target.entry);
    }
});
for (const entry of entries) {
    entry.card = document.createElement('article');
    entry.card.entry = entry;
    entry.canvas = document.createElement('canvas');
    entry.ctx = entry.canvas.getContext('2d');
    entry.period = entry.frame_times.reduce((sum, time) => sum + time, 0);
    const name = document.createElement('p'), motion = document.createElement('small');
    name.textContent = entry.name;
    motion.textContent = `${entry.title} · ${(entry.period / 20).toFixed(2)} 秒循环`;
    entry.card.append(entry.canvas, name, motion);
    main.append(entry.card);
    observer.observe(entry.card);
}
view.onchange = () => main.classList.toggle('held', view.value === 'held');
filter.onchange = () => entries.forEach(entry => entry.card.hidden = !!filter.value && filter.value !== entry.effect);
let playing = true, previous = performance.now(), elapsed = 0;
play.onclick = () => {
    playing = !playing;
    play.textContent = playing ? '暂停播放' : '继续播放';
};
function tick(now) {
    if (playing) elapsed += now - previous;
    previous = now;
    for (const entry of visible) {
        if (entry.mode !== view.value) {
            entry.mode = view.value;
            entry.imageObject = new Image();
            entry.imageObject.src = entry[entry.mode];
            entry.canvas.width = entry.canvas.height = entry.mode === 'held' ? 64 : 32;
        }
        const image = entry.imageObject, ctx = entry.ctx, size = entry.canvas.width;
        if (!image.complete || !image.naturalWidth) continue;
        let time = (elapsed / 50) % entry.period, frame = 0;
        while (time >= entry.frame_times[frame]) time -= entry.frame_times[frame++];
        const blend = time / entry.frame_times[frame];
        ctx.clearRect(0, 0, size, size);
        ctx.globalCompositeOperation = 'lighter';
        ctx.globalAlpha = 1 - blend;
        ctx.drawImage(image, 0, frame * size, size, size, 0, 0, size, size);
        ctx.globalAlpha = blend;
        ctx.drawImage(image, 0, ((frame + 1) % 16) * size, size, size, 0, 0, size, size);
    }
    requestAnimationFrame(tick);
}
requestAnimationFrame(tick);
</script></html>
'@
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $root 'animated-preview.html'), $html.Replace('__CATALOG_DATA__', $data), $utf8)

if ($HtmlOnly) {
    Write-Output "Previewed $($manifest.Count) animated textures in HTML."
    return
}

$sampleIds = @('YKI_ENDSUN_GUARD_HELMET', 'YKI_FROSTWOOD_HUNTSMAN_CHESTPLATE', 'YKI_BRAMBLE_RANGER_BOOTS',
    'YKI_ASTRAL_WAYFARER_STAFF', 'YKI_EXPEDITION_WARHAMMER', 'YKI_GLOOM_SICKLE',
    'YKI_BLACK_SUN_CORE', 'YKI_FUNERAL_PETAL', 'YKI_HOMECOMING_CHRONICLE')
if ($SourceDirectory) {
    $sampleIds = @('YKI_WINGBREAKER_GREATSWORD', 'YKI_GLACIER_SCEPTER', 'YKI_ASTRAL_GRIMOIRE',
        'YKI_OSSUARY_DRAGOON_HELMET', 'YKI_ENDSUN_GUARD_CHESTPLATE', 'YKI_ENDWITCH_REBEL_BOOTS',
        'YKI_FORGED_IRON', 'YKI_CHARGED_COAL', 'YKI_ABYSS_ECHO_CRYSTAL')
}
$samples = @($sampleIds | ForEach-Object { $id = $_; $manifest | Where-Object {$_.id -eq $id} })
$images = @($samples | ForEach-Object {
    [Drawing.Bitmap]::FromFile((Join-Path $pack ('resourcepack\assets\' + $_.namespace + '\textures\item\' + $_.texture + '.png')))
})
$frames = Join-Path $project 'build\previews\catalog-animations\frames'
New-Item -ItemType Directory -Force -Path $frames | Out-Null
$font = New-Object Drawing.Font 'Microsoft YaHei', 13
$detailFont = New-Object Drawing.Font 'Microsoft YaHei', 10
$titleFont = New-Object Drawing.Font 'Microsoft YaHei', 18, ([Drawing.FontStyle]::Bold)
$text = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(235,238,244))
$slot = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(68,74,85))
$attributes = New-Object Drawing.Imaging.ImageAttributes
$matrix = New-Object Drawing.Imaging.ColorMatrix
try {
    for ($frame = 0; $frame -lt 96; $frame++) {
        $board = New-Object Drawing.Bitmap 792, 900
        $graphics = [Drawing.Graphics]::FromImage($board)
        try {
            $graphics.Clear([Drawing.Color]::FromArgb(24,28,37))
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $graphics.DrawString('YknRpg | Animated textures', $titleFont, $text, 24, 20)
            for ($index = 0; $index -lt $samples.Count; $index++) {
                $sample = $samples[$index]
                $left = 24 + ($index % 3) * 256
                $top = 76 + [int][Math]::Floor($index / 3) * 272
                $graphics.FillRectangle($slot, $left, $top, 232, 224)
                $times = @($sample.frame_times | ForEach-Object {[int]$_})
                $tick = $frame % ($times | Measure-Object -Sum).Sum
                $phase = 0
                while ($tick -ge $times[$phase]) { $tick -= $times[$phase]; $phase++ }
                $destination = [Drawing.Rectangle]::new($left + 20, $top + 16, 192, 192)
                $size = $images[$index].Width
                $graphics.DrawImage($images[$index], $destination, 0, $phase * $size, $size, $size, [Drawing.GraphicsUnit]::Pixel)
                $matrix.Matrix33 = $tick / [double]$times[$phase]
                $attributes.SetColorMatrix($matrix)
                $graphics.DrawImage($images[$index], $destination, 0, (($phase + 1) % 16) * $size,
                    $size, $size, [Drawing.GraphicsUnit]::Pixel, $attributes)
                $graphics.DrawString($sample.name, $font, $text, $left, $top + 232)
                $graphics.DrawString($sample.title, $detailFont, $text, $left, $top + 254)
            }
            $board.Save((Join-Path $frames ('frame-{0:D3}.png' -f $frame)), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $board.Dispose() }
    }
} finally {
    foreach ($image in $images) { $image.Dispose() }
    $font.Dispose(); $detailFont.Dispose(); $titleFont.Dispose(); $text.Dispose(); $slot.Dispose(); $attributes.Dispose()
}
Copy-Item -LiteralPath (Join-Path $frames 'frame-000.png') -Destination (Join-Path $root 'animated-preview.png') -Force
& ffmpeg -hide_banner -loglevel error -y -framerate 20 -i (Join-Path $frames 'frame-%03d.png') -filter_complex '[0:v]split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3' -loop 0 (Join-Path $root 'animated-preview.gif')
if ($LASTEXITCODE -ne 0) { throw 'Animated GIF preview export failed.' }
Write-Output "Previewed $($manifest.Count) animated textures in HTML and nine examples in GIF."
