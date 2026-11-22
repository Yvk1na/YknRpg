package cn.yknrpg.assets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CatalogAnimationAssetsTest {
    private static final Path PACK = Path.of("packs/craftengine");
    private static final Path SOURCES = Path.of("docs/design/catalog-32-v2");

    @Test
    void reviewedArtHasRealAnimationWithoutChangingItsSilhouetteOrSource() throws Exception {
        YamlConfiguration manifest = new YamlConfiguration();
        manifest.loadFromString("items: " + Files.readString(PACK.resolve("catalog-animations.json")));
        Map<String, YamlConfiguration> definitions = Map.of(
                "ITEM", load(Path.of("packs/mythicmobs/items/YknRpg_Materials.yml")),
                "WEAPON", load(Path.of("packs/mythicmobs/items/YknRpg_Weapons.yml")),
                "ARMOR", load(Path.of("packs/mythicmobs/items/YknRpg_Armor.yml")));
        Set<String> exported = new HashSet<>();
        Set<String> individualTitles = new HashSet<>();
        Set<String> individualEffectHashes = new HashSet<>();
        Set<String> individualEffectPaths = new HashSet<>();
        Set<String> individualTimelines = new HashSet<>();
        for (Map<?, ?> entry : manifest.getMapList("items")) {
            String id = entry.get("id").toString();
            assertEquals("catalog-32-v2", entry.get("source_catalog"), id);
            Path itemSources = SOURCES;
            String name = entry.get("texture").toString();
            String namespace = entry.get("namespace").toString();
            String modelId = namespace + ":" + name;
            assertTrue(exported.add(id), "Duplicate animated item: " + id);
            assertTrue(individualTitles.add(entry.get("title").toString()), "Animation title must be individual: " + id);
            String effectPath = entry.get("effect_source").toString();
            assertTrue(effectPath.startsWith("effects/cells/"));
            assertTrue(individualEffectPaths.add(effectPath), "Items must not reuse effect artwork: " + id);
            String effectHash = sha256(itemSources.resolve(effectPath));
            assertEquals(entry.get("effect_sha256"), effectHash);
            assertTrue(individualEffectHashes.add(effectHash), "Each item needs its own authored effect: " + id);
            assertEquals(modelId, definitions.get(entry.get("kind").toString())
                    .getString(id + ".Options.ItemModel"), id);
            Path assets = PACK.resolve("resourcepack/assets/" + namespace);
            YamlConfiguration definition = load(assets.resolve("items/" + name + ".json"));
            YamlConfiguration record = load(itemSources.resolve("completed/" + id + ".json"));
            String guiModel = namespace + ":item/" + name;
            String hand = (String) entry.get("handheld_texture");
            assertEquals(32, entry.get("icon_size"), id);
            assertEquals(64, entry.get("handheld_size"), id);
            assertEquals(name + "_in_hand", hand);
            assertEquals("minecraft:select", definition.getString("model.type"));
            assertEquals("minecraft:display_context", definition.getString("model.property"));
            assertEquals("minecraft:model", definition.getString("model.fallback.type"));
            assertEquals(guiModel, definition.getString("model.fallback.model"));
            var cases = definition.getMapList("model.cases");
            assertEquals(1, cases.size());
            var handContexts = new HashSet<>((List<?>) cases.getFirst().get("when"));
            assertEquals(Set.of("firstperson_righthand", "firstperson_lefthand",
                    "thirdperson_righthand", "thirdperson_lefthand"), handContexts);
            String handModel = namespace + ":item/" + hand;
            assertEquals(Map.of("type", "minecraft:model", "model", handModel), cases.getFirst().get("model"));
            YamlConfiguration heldModel = load(assets.resolve("models/item/" + hand + ".json"));
            assertEquals(handModel, heldModel.getString("textures.layer0"));
            for (Object context : handContexts) {
                for (String transform : Set.of("rotation", "translation", "scale")) {
                    String key = "display." + context + "." + transform;
                    List<Double> values = heldModel.getDoubleList(key);
                    assertEquals(3, values.size(), key);
                    assertTrue(values.stream().allMatch(Double::isFinite), key);
                    if (transform.equals("scale")) assertTrue(values.stream().allMatch(v -> v > 0), key);
                    if (!transform.equals("translation") && record.isConfigurationSection("handheld.display")) {
                        assertEquals(record.getDoubleList("handheld." + key), heldModel.getDoubleList(key), key);
                    }
                }
            }
            YamlConfiguration iconModel = load(assets.resolve("models/item/" + name + ".json"));
            assertEquals("minecraft:item/generated", iconModel.getString("parent"));
            assertEquals(guiModel, iconModel.getString("textures.layer0"));
            assertTrue(individualTimelines.add(entry.get("frame_times").toString()),
                    "Each item needs its own timing: " + id);
            Path sourcePath = itemSources.resolve(record.getString("texture"));
            assertEquals(entry.get("source_sha256"), sha256(sourcePath), "Original artwork changed: " + id);
            Path heldSource = itemSources.resolve(record.getString("handheld.texture", record.getString("texture")));
            assertEquals(entry.get("handheld_source_sha256"), sha256(heldSource), id);
            assertEquals(entry.get("handheld_effect_sha256"),
                    sha256(itemSources.resolve(record.getString("handheld.effect_source", effectPath))), id);
            BufferedImage master = ImageIO.read(heldSource.toFile());
            assertEquals(64, master.getWidth());
            assertEquals(64, master.getHeight());
            List<?> grip = (List<?>) entry.get("grip_pixel");
            assertEquals(2, grip.size(), id);
            for (String view : List.of("first", "third")) {
                for (String side : List.of("right", "left")) {
                    String path = "display." + view + "person_" + side + "hand.";
                    var rotation = heldModel.getDoubleList(path + "rotation");
                    var scale = heldModel.getDoubleList(path + "scale");
                    var translation = heldModel.getDoubleList(path + "translation");
                    // ItemTransform mirrors X translation and Y/Z Euler angles for the left hand.
                    double mirror = side.equals("left") ? -1 : 1;
                    double rx = Math.toRadians(rotation.get(0));
                    double ry = Math.toRadians(rotation.get(1) * mirror);
                    double rz = Math.toRadians(rotation.get(2) * mirror);
                    double x = (((Number) grip.get(0)).doubleValue() - 31.5) * scale.get(0) / 4;
                    double y = (31.5 - ((Number) grip.get(1)).doubleValue()) * scale.get(1) / 4;
                    double across = x * Math.cos(rz) - y * Math.sin(rz);
                    double up = x * Math.sin(rz) + y * Math.cos(rz);
                    double depth = -across * Math.sin(ry);
                    double expectedY = view.equals("first") ? -0.579133291275014 : -0.630907200802501;
                    double expectedZ = view.equals("first") ? 0.266199347779313 : 1.92686667126103;
                    assertEquals(expectedY, up * Math.cos(rx) - depth * Math.sin(rx) + translation.get(1),
                            0.001, "Handle must meet vanilla wrist: " + id + " " + path);
                    assertEquals(expectedZ, up * Math.sin(rx) + depth * Math.cos(rx) + translation.get(2),
                            0.001, "Handle must not drift behind wrist: " + id + " " + path);
                }
            }
            assertTrue((master.getRGB(((Number) grip.getFirst()).intValue(), ((Number) grip.getLast()).intValue())
                    >>> 24) >= 192, "Grip must contact solid artwork: " + id);
            assertTexture(assets.resolve("textures/item/" + hand + ".png"), master, 64, entry);
            assertTexture(assets.resolve("textures/item/" + name + ".png"), null, 32, entry);
        }
        Set<String> reviewed = new HashSet<>();
        try (var files = Files.list(SOURCES.resolve("completed"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                YamlConfiguration record = load(path);
                if ("passed".equals(record.getString("visual_review"))) reviewed.add(record.getString("id"));
            }
        }
        assertEquals(reviewed, exported, "Every reviewed sprite must be included exactly once");
        assertEquals(327, exported.size());
        for (YamlConfiguration definition : definitions.values()) {
            for (String id : definition.getKeys(false)) {
                String model = definition.getString(id + ".Options.ItemModel", "");
                if (model.startsWith("yknrpg_items:") || model.startsWith("yknrpg_bone:")) {
                    assertTrue(exported.contains(id), "Template references unpublished artwork: " + id);
                }
            }
        }
        assertTrue(exported.contains("YKI_WINGBREAKER_GREATSWORD"));
        assertTrue(exported.contains("YKI_OSSUARY_DRAGOON_CHESTPLATE"));
        assertTrue(exported.contains("YKI_WRAITHFIRE_DRAGONHEART"));
    }

    private static void assertTexture(Path texture, BufferedImage master, int size, Map<?, ?> entry) throws Exception {
        YamlConfiguration animation = load(Path.of(texture + ".mcmeta"));
        assertEquals(size, animation.getInt("animation.width"));
        assertEquals(size, animation.getInt("animation.height"));
        assertTrue(animation.getBoolean("animation.interpolate"));
        assertEquals(((Number) entry.get("frametime")).intValue(), animation.getInt("animation.frametime"));
        assertTrue(animation.getInt("animation.frametime") >= 2);
        var timeline = animation.getMapList("animation.frames");
        assertEquals(16, timeline.size());
        var expected = (java.util.List<?>) entry.get("frame_times");
        BufferedImage strip = ImageIO.read(texture.toFile());
        assertNotNull(strip);
        assertEquals(size, strip.getWidth());
        assertEquals(size * 16, strip.getHeight());
        int[] sourcePixels = (master != null ? master : strip).getRGB(0, 0, size, size, null, 0, size);
        Set<Integer> frameHashes = new HashSet<>();
        if (master == null) {
            for (int i = 0; i < sourcePixels.length; i++) {
                int alpha = sourcePixels[i] >>> 24;
                assertTrue(alpha == 0 || alpha == 255, "Slot icons need a hard pixel silhouette");
                if (i % size == 0 || i % size == size - 1 || i < size || i >= (size - 1) * size) {
                    assertEquals(0, alpha, "Slot icon clips the border: " + texture);
                }
            }
        }
        for (int phase = 0; phase < 16; phase++) {
            assertEquals(phase, ((Number) timeline.get(phase).get("index")).intValue());
            assertEquals(((Number) expected.get(phase)).intValue(),
                    ((Number) timeline.get(phase).get("time")).intValue());
            int[] pixels = strip.getRGB(0, phase * size, size, size, null, 0, size);
            frameHashes.add(Arrays.hashCode(pixels));
            for (int i = 0; i < pixels.length; i++) {
                if ((sourcePixels[i] >>> 24) != (pixels[i] >>> 24)) {
                    fail("Animation changed silhouette at frame " + phase + ", pixel " + i + ": " + texture);
                }
            }
        }
        assertTrue(frameHashes.size() >= 3, "Animation needs distinct visible frames: " + texture);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static YamlConfiguration load(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path), "Missing resource: " + path);
        YamlConfiguration result = new YamlConfiguration();
        result.load(path.toFile());
        return result;
    }
}
