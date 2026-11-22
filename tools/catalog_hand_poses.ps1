# Minecraft scales around the item centre, then applies Z, Y and X rotations.
# Pixel coordinates have their origin in the top-left of the 64x64 held sprite.
function Get-ModelPoint($Pixel, $Rotation, $Scale) {
    $x = ($Pixel[0] - 31.5) * $Scale[0] / 4
    $y = (31.5 - $Pixel[1]) * $Scale[1] / 4
    $rx, $ry, $rz = @($Rotation | ForEach-Object { $_ * [Math]::PI / 180 })
    $across = $x * [Math]::Cos($rz) - $y * [Math]::Sin($rz)
    $up = $x * [Math]::Sin($rz) + $y * [Math]::Cos($rz)
    $depth = -$across * [Math]::Sin($ry)
    return @(($across * [Math]::Cos($ry)), ($up * [Math]::Cos($rx) - $depth * [Math]::Sin($rx)),
        ($up * [Math]::Sin($rx) + $depth * [Math]::Cos($rx)))
}

function Get-GripPixel($Path, $Hint) {
    $sprite = [Drawing.Bitmap]::new($Path)
    try {
        $gx, $gy = $Hint
        if ($sprite.GetPixel($gx, $gy).A -ge 192) { return @($gx, $gy) }
        $best = [double]::MaxValue
        for ($y = 0; $y -lt $sprite.Height; $y++) {
            for ($x = 0; $x -lt $sprite.Width; $x++) {
                $distance = ($x - $gx) * ($x - $gx) + ($y - $gy) * ($y - $gy)
                if ($distance -lt $best -and $sprite.GetPixel($x, $y).A -ge 192) {
                    $best = $distance
                    $nearest = @($x, $y)
                }
            }
        }
        if ($null -eq $nearest) { throw "No solid grip pixel in $Path" }
        return $nearest
    } finally { $sprite.Dispose() }
}

function Get-HandWrist {
    # Vanilla 1.21.11 handheld.json, with the centre of sword handle texel (4,12).
    # Express the 16x16 texel centre on our 64x64 pixel-centre coordinate system.
    $wrist = @{}
    $reference = @{
        first = @{rotation=@(0,-90,25); translation=@(1.13,3.2,1.13); scale=@(.68,.68,.68)}
        third = @{rotation=@(0,-90,55); translation=@(0,4,.5); scale=@(.85,.85,.85)}
    }
    foreach ($view in @('first','third')) {
        $pose = $reference[$view]
        $point = Get-ModelPoint @(17.5,49.5) $pose.rotation $pose.scale
        $wrist[$view] = @(for ($i=0; $i -lt 3; $i++) { $point[$i] + $pose.translation[$i] })
    }
    return $wrist
}

function Get-HandDisplay($Profile, $Grip, $Wrist, $AuthoredDisplay) {
    $display = [ordered]@{}
    foreach ($view in @('first', 'third')) {
        $pose = $Profile.$view
        $scale = @($pose.scale, $pose.scale, [Math]::Round($pose.scale * 0.65, 3))
        $rotation = $pose.rotation
        if ($AuthoredDisplay) {
            $authored = $AuthoredDisplay.($view + 'person_righthand')
            $rotation = $authored.rotation
            $scale = $authored.scale
        }
        $point = Get-ModelPoint $Grip $rotation $scale
        $translation = @(for ($i = 0; $i -lt 3; $i++) { [Math]::Round($Wrist[$view][$i] - $point[$i], 3) })
        $display[$view + 'person_righthand'] = [ordered]@{rotation=$rotation; translation=$translation; scale=$scale}
        $left = @($rotation[0], (-$rotation[1]), (-$rotation[2]))
        $display[$view + 'person_lefthand'] = [ordered]@{rotation=$left; translation=$translation; scale=$scale}
    }
    return $display
}
