package cn.yknrpg.assets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoneDragonAssetsTest {
    private static final Path ASSETS = Path.of("packs/craftengine/resourcepack/assets/yknrpg_bone");

    @Test
    void mythicItemsReferenceActualTexturesAndWearableArmorLayers() throws Exception {
        Map<String, String> weapons = Map.of("YKI_WINGBREAKER_GREATSWORD", "wingbreaker_greatsword");
        Map<String, String> armor = new LinkedHashMap<>();
        armor.put("YKI_OSSUARY_DRAGOON_HELMET", "ossuary_dragoon_helmet");
        armor.put("YKI_OSSUARY_DRAGOON_CHESTPLATE", "ossuary_dragoon_chestplate");
        armor.put("YKI_OSSUARY_DRAGOON_LEGGINGS", "ossuary_dragoon_leggings");
        armor.put("YKI_OSSUARY_DRAGOON_BOOTS", "ossuary_dragoon_boots");
        Map<String, String> materials = new LinkedHashMap<>();
        materials.put("YKI_BONE_DRAGON_FANG", "bone_dragon_fang");
        materials.put("YKI_OSSUARY_CARAPACE", "ossuary_carapace");
        materials.put("YKI_SHATTERED_WINGBONE", "shattered_wingbone");
        materials.put("YKI_WRAITHFIRE_DRAGONHEART", "wraithfire_dragonheart");

        checkItems("YknRpg_Weapons.yml", weapons);
        checkItems("YknRpg_Armor.yml", armor);
        checkItems("YknRpg_Materials.yml", materials);

        YamlConfiguration armorYaml = load(Path.of("packs/mythicmobs/items/YknRpg_Armor.yml"));
        String[] slots = {"HEAD", "CHEST", "LEGS", "FEET"};
        int index = 0;
        for (String item : armor.keySet()) {
            assertEquals("yknrpg_bone:ossuary_dragoon", armorYaml.getString(item + ".Equippable.Model"));
            assertEquals(slots[index++], armorYaml.getString(item + ".Equippable.Slot"));
        }

        YamlConfiguration equipment = load(ASSETS.resolve("equipment/ossuary_dragoon.json"));
        for (String layer : new String[]{"humanoid", "humanoid_leggings"}) {
            assertEquals("yknrpg_bone:ossuary_dragoon",
                    equipment.getMapList("layers." + layer).getFirst().get("texture"));
            assertPng(ASSETS.resolve("textures/entity/equipment/" + layer + "/ossuary_dragoon.png"), 64, 32);
        }
        BufferedImage main = ImageIO.read(ASSETS.resolve(
                "textures/entity/equipment/humanoid/ossuary_dragoon.png").toFile());
        BufferedImage legs = ImageIO.read(ASSETS.resolve(
                "textures/entity/equipment/humanoid_leggings/ossuary_dragoon.png").toFile());
        assertEquals(0, main.getRGB(4, 20) >>> 24, "靴子不应遮盖整条腿");
        assertEquals(255, main.getRGB(4, 28) >>> 24);
        assertEquals(0, legs.getRGB(20, 20) >>> 24, "护腿不应遮盖整个胸部");
        assertEquals(255, legs.getRGB(20, 28) >>> 24);
    }

    private static void checkItems(String file, Map<String, String> items) throws Exception {
        YamlConfiguration definitions = load(Path.of("packs/mythicmobs/items", file));
        for (Map.Entry<String, String> entry : items.entrySet()) {
            String name = entry.getValue();
            assertEquals("yknrpg_bone:" + name, definitions.getString(entry.getKey() + ".Options.ItemModel"));

            YamlConfiguration item = load(ASSETS.resolve("items/" + name + ".json"));
            assertEquals("minecraft:select", item.getString("model.type"));
            assertEquals("yknrpg_bone:item/" + name, item.getString("model.fallback.model"));
            YamlConfiguration model = load(ASSETS.resolve("models/item/" + name + ".json"));
            assertEquals("yknrpg_bone:item/" + name, model.getString("textures.layer0"));
            assertEquals("minecraft:item/generated", model.getString("parent"));
            assertPng(ASSETS.resolve("textures/item/" + name + ".png"), 32, 512);
            assertPng(ASSETS.resolve("textures/item/" + name + "_in_hand.png"), 64, 1024);
        }
    }

    private static YamlConfiguration load(Path path) {
        assertTrue(path.toFile().isFile(), "缺少资源：" + path);
        return YamlConfiguration.loadConfiguration(path.toFile());
    }

    private static void assertPng(Path path, int width, int height) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image, "无效图片：" + path);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha(), "纹理必须带透明通道：" + path);
        boolean transparent = false;
        boolean opaque = false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                transparent |= alpha == 0;
                opaque |= alpha == 255;
            }
        }
        assertTrue(transparent && opaque, "纹理应包含透明区域和可见像素：" + path);
    }
}
