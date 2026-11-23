# 骨龙系列材质设计提示词

生成方式：imagegen 内置图像生成工具。生成结果是设计参考图
`bone-dragon-concept.png`；游戏内实际使用的 16×16 像素图标与 64×32 护甲穿戴贴图由
`tools/generate_bone_dragon_assets.ps1` 按参考方向重新绘制，实际图标见
`bone-dragon-items-preview.png`。参考图不直接作为游戏纹理。

```text
Use case: stylized-concept. Create a design reference sprite sheet for a Minecraft Java survival RPG resource pack. Theme: an ancient skeletal dragon and the Ossuary Dragoon set. Arrange exactly nine separate, unconnected item icons in a simple, evenly spaced 3 by 3 grid: (1) diagonal wingbone greatsword, (2) skeletal dragon skull helmet, (3) ribcage chestplate, (4) bone-plated leggings, (5) bone-spurred boots, (6) long dragon fang, (7) ribbed bone carapace fragment, (8) fractured wingbone, (9) blue soul-fire dragon heart. One sprite per cell; do not merge items. Original Minecraft vanilla-inspired pixel art: crisp square pixels, restrained low-resolution 16x16 or 32x32 silhouette, hand-placed pixels, dark charcoal outlines, ivory/weathered bone, muted blue-gray shadows, tiny cyan soul-fire accents. Plain transparent background, no grid lines, no labels, no words, no UI, no glow haze, no anti-aliasing, no photorealism, no perspective mockup. The intended use is as a coherent reference for actual small Minecraft item textures.
```
