package cn.yknrpg.integration;

import cn.yknrpg.YknRpgPlugin;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Runtime bridge for EcoPets without coupling the build to one EcoPets release. */
public final class EcoPetsItems {
    private static final String PLUGIN_NAME = "EcoPets";
    private static final String CATALOG_PREFIX = "ecopets:";

    private final YknRpgPlugin plugin;
    private Map<String, String> petIds = Map.of();
    private Map<String, String> names = Map.of();
    private Map<String, String> catalogIdsByPetId = Map.of();
    private Method getPetById;
    private Method getSpawnEgg;
    private Method getPetEgg;
    private Method getPetId;
    private boolean warnedUnavailable;

    public EcoPetsItems(YknRpgPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        petIds = Map.of();
        names = Map.of();
        catalogIdsByPetId = Map.of();
        getPetById = null;
        getSpawnEgg = null;
        getPetEgg = null;
        getPetId = null;

        Plugin ecoPets = plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (ecoPets == null || !ecoPets.isEnabled()) {
            warnUnavailable("未检测到已启用的 EcoPets；宠物蛋图鉴将为空。");
            return;
        }

        try {
            ClassLoader loader = ecoPets.getClass().getClassLoader();
            Class<?> petsClass = Class.forName("com.willfp.ecopets.pets.Pets", true, loader);
            Class<?> petClass = Class.forName("com.willfp.ecopets.pets.Pet", true, loader);
            Class<?> petFunctions = Class.forName("com.willfp.ecopets.pets.PetKt", true, loader);
            Method values = petsClass.getMethod("values");
            Method findPet = petsClass.getMethod("getByID", String.class);
            Method petId = petClass.getMethod("getId");
            Method petName = petClass.getMethod("getName");
            Method spawnEgg = petClass.getMethod("getSpawnEgg");
            Method identifyEgg = petFunctions.getMethod("getPetEgg", ItemStack.class);

            Object configuredPets = values.invoke(null);
            if (!(configuredPets instanceof Iterable<?> iterable)) {
                throw new IllegalStateException("EcoPets Pets.values() 未返回可迭代集合");
            }

            List<PetEntry> loaded = new ArrayList<>();
            for (Object pet : iterable) {
                String id = String.valueOf(petId.invoke(pet)).trim();
                if (!id.matches("[A-Za-z0-9_-]+")) {
                    plugin.getLogger().warning("已跳过无法安全保存配方的 EcoPets 宠物 ID：" + id);
                    continue;
                }
                ItemStack egg;
                try {
                    egg = (ItemStack) spawnEgg.invoke(pet);
                } catch (InvocationTargetException exception) {
                    plugin.getLogger().warning("无法生成 EcoPets 宠物蛋（" + id + "）："
                            + exception.getTargetException().getMessage());
                    continue;
                }
                if (egg == null || egg.getType() == Material.AIR) continue;
                loaded.add(new PetEntry(id, catalogId(id), String.valueOf(petName.invoke(pet))));
            }
            loaded.sort(Comparator.comparing(entry -> entry.petId().toLowerCase(Locale.ROOT)));

            Map<String, String> loadedPetIds = new LinkedHashMap<>();
            Map<String, String> loadedNames = new LinkedHashMap<>();
            Map<String, String> loadedCatalogIds = new LinkedHashMap<>();
            for (PetEntry entry : loaded) {
                if (loadedPetIds.putIfAbsent(entry.catalogId(), entry.petId()) != null) {
                    plugin.getLogger().warning("已跳过重复的 EcoPets 宠物图鉴 ID：" + entry.catalogId());
                    continue;
                }
                loadedNames.put(entry.catalogId(), entry.name());
                loadedCatalogIds.put(entry.petId(), entry.catalogId());
            }
            petIds = Map.copyOf(loadedPetIds);
            names = Map.copyOf(loadedNames);
            catalogIdsByPetId = Map.copyOf(loadedCatalogIds);
            getPetById = findPet;
            getSpawnEgg = spawnEgg;
            getPetEgg = identifyEgg;
            getPetId = petId;
            warnedUnavailable = false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warnUnavailable("EcoPets 宠物蛋接口连接失败，宠物蛋图鉴将为空：" + exception.getMessage());
        }
    }

    public List<String> itemIds() {
        return petIds.keySet().stream()
                .sorted(Comparator.comparing(id -> id.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public boolean contains(String itemId) {
        return petIds.containsKey(itemId);
    }

    public ItemStack create(String itemId) {
        String petId = petIds.get(itemId);
        if (petId == null || getPetById == null || getSpawnEgg == null) {
            throw new IllegalArgumentException("未知 EcoPets 宠物蛋：" + itemId);
        }
        try {
            Object pet = getPetById.invoke(null, petId);
            if (pet == null) throw new IllegalArgumentException("EcoPets 已不再注册宠物：" + petId);
            ItemStack egg = (ItemStack) getSpawnEgg.invoke(pet);
            if (egg == null || egg.getType() == Material.AIR) {
                throw new IllegalArgumentException("EcoPets 宠物未启用宠物蛋：" + petId);
            }
            return egg.clone();
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("无法生成 EcoPets 宠物蛋：" + petId,
                    exception.getTargetException());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法生成 EcoPets 宠物蛋：" + petId, exception);
        }
    }

    public String configuredName(String itemId) {
        return names.getOrDefault(itemId, itemId);
    }

    public Optional<String> itemId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || getPetEgg == null || getPetId == null) {
            return Optional.empty();
        }
        try {
            Object pet = getPetEgg.invoke(null, stack);
            if (pet == null) return Optional.empty();
            String petId = String.valueOf(getPetId.invoke(pet));
            return Optional.ofNullable(catalogIdsByPetId.get(petId));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void warnUnavailable(String message) {
        if (!warnedUnavailable) plugin.getLogger().warning(message);
        warnedUnavailable = true;
    }

    private static String catalogId(String petId) {
        return CATALOG_PREFIX + petId;
    }

    private record PetEntry(String petId, String catalogId, String name) {
    }
}
