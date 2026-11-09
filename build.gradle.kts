import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

plugins {
    java
}

group = "cn.yknrpg"
version = "1.2.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("dev.aurelium:auraskills-api:2.3.12")
    compileOnly("dev.aurelium:auraskills-api-bukkit:2.3.12")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

val yamlValidator by configurations.creating
val proguard by configurations.creating

dependencies {
    yamlValidator("org.yaml:snakeyaml:2.2")
    proguard("com.guardsquare:proguard-base:7.9.1") { isTransitive = false }
    proguard("com.guardsquare:proguard-core:9.3.2") { isTransitive = false }
    proguard("org.apache.logging.log4j:log4j-api:2.25.3") { isTransitive = false }
    proguard("org.apache.logging.log4j:log4j-core:2.25.3") { isTransitive = false }
    proguard("com.google.code.gson:gson:2.11.0") { isTransitive = false }
    proguard("org.json:json:20231013") { isTransitive = false }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-proc:none")
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}

val ecoPetFiles = fileTree("packs/ecopets/pets/yknrpg") {
    include("*.yml")
}

val validateEcoPets by tasks.registering {
    val petValidationLibraries = configurations.named("compileClasspath")
    group = "verification"
    description = "严格验证全部 YknRpg EcoPets 配置"
    inputs.files(ecoPetFiles)

    doLast {
        val urls = (yamlValidator.files + petValidationLibraries.get().files)
            .map { it.toURI().toURL() }
            .toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val yamlClass = loader.loadClass("org.yaml.snakeyaml.Yaml")
            val loaderOptionsClass = loader.loadClass("org.yaml.snakeyaml.LoaderOptions")
            val loaderOptions = loaderOptionsClass.getConstructor().newInstance()
            loaderOptionsClass.getMethod(
                "setAllowDuplicateKeys", Boolean::class.javaPrimitiveType!!
            ).invoke(loaderOptions, false)
            val yaml = yamlClass.getConstructor(loaderOptionsClass).newInstance(loaderOptions)
            val validMaterials = loader.loadClass("org.bukkit.Material").enumConstants
                .map { (it as Enum<*>).name }
                .toSet()
            val parsedFiles = linkedMapOf<String, Map<*, *>>()
            for (file in ecoPetFiles.files.sortedBy { it.name }) {
                try {
                    file.inputStream().use { input ->
                        val parsed = yamlClass.getMethod("load", InputStream::class.java)
                            .invoke(yaml, input)
                        if (parsed !is Map<*, *>) {
                            throw GradleException("${file.name} 根节点必须是映射")
                        }
                        parsedFiles[file.name] = parsed
                    }
                } catch (exception: InvocationTargetException) {
                    throw GradleException(
                        "${file.name} YAML 解析失败",
                        exception.targetException
                    )
                }
            }

            val petFiles = ecoPetFiles.files.sortedBy { it.name }
            if (petFiles.size != 72) {
                throw GradleException("EcoPets 内容必须正好包含 72 个 pets/yknrpg/*.yml 文件")
            }
            val petIds = petFiles.map { it.nameWithoutExtension }
            if (petIds.any { !Regex("yknrpg_[a-z0-9_]+").matches(it) }) {
                throw GradleException("EcoPets 持久宠物 ID 必须保留 yknrpg_ 前缀并仅使用小写字母、数字和下划线")
            }
            if (petIds.size != petIds.toSet().size) {
                throw GradleException("EcoPets 宠物 ID 不得重复")
            }
            val petIdManifest = petIds.sorted().joinToString("\n")
            val petIdManifestHash = MessageDigest.getInstance("SHA-256")
                .digest(petIdManifest.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val expectedPetIdManifestHash =
                "a62d8c144a8eb3a0493db864d61c82273e408be0580e0facc9c0122bb5fc720d"
            if (petIdManifestHash != expectedPetIdManifestHash) {
                throw GradleException("72 个已投入使用的 EcoPets 持久 ID 集合不得新增、删除或重命名")
            }

            val auraSkillsStats = setOf(
                "strength", "health", "regeneration", "luck", "wisdom",
                "toughness", "crit_chance", "crit_damage", "speed"
            )
            val petXpTriggers = setOf(
                "mine_block", "catch_fish", "kill", "move", "brew", "enchant_item",
                "bow_attack", "melee_attack", "shield_block", "breed", "tame_animal",
                "jump", "pick_up_item", "place_block", "craft", "gain_xp"
            )
            val permanentPetEffects = setOf(
                "add_stat", "skill_xp_multiplier", "pet_xp_multiplier",
                "mining_speed_multiplier", "underwater_mining_speed_multiplier",
                "water_movement_efficiency_multiplier", "reel_speed_multiplier",
                "brew_time_multiplier", "potion_duration_multiplier", "food_multiplier",
                "oxygen_bonus", "safe_fall_distance", "block_reach", "increase_step_height",
                "dont_consume_lapis_chance", "dont_consume_xp_chance", "replant_crops",
                "item_magnet", "feather_step", "telekinesis"
            )
            val triggeredPetEffects = mapOf(
                "multiply_drops" to setOf("block_item_drop", "catch_fish"),
                "autosmelt" to setOf("block_item_drop")
            )
            val multiplierPetEffects = setOf(
                "skill_xp_multiplier", "pet_xp_multiplier", "mining_speed_multiplier",
                "underwater_mining_speed_multiplier", "water_movement_efficiency_multiplier",
                "reel_speed_multiplier", "brew_time_multiplier", "potion_duration_multiplier",
                "food_multiplier"
            )
            val allowedSkillXpSkills = setOf(
                "farming", "foraging", "mining", "fishing", "excavation",
                "agility", "alchemy", "enchanting"
            )
            val flatPetEffects = mapOf(
                "oxygen_bonus" to "amount",
                "safe_fall_distance" to "distance",
                "block_reach" to "reach",
                "increase_step_height" to "height"
            )
            val chancePetEffects = setOf(
                "dont_consume_lapis_chance", "dont_consume_xp_chance"
            )
            val staticPetEffects = setOf(
                "replant_crops", "item_magnet", "feather_step", "telekinesis"
            )
            val cropBlocks = setOf(
                "wheat", "carrots", "potatoes", "beetroots",
                "nether_wart", "cocoa", "melon", "pumpkin"
            )
            val cropDrops = setOf(
                "wheat", "carrot", "potato", "beetroot",
                "nether_wart", "cocoa_beans", "melon_slice", "pumpkin"
            )
            val logBlocks = setOf(
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
                "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log",
                "crimson_stem", "warped_stem"
            )
            val oreBlocks = setOf(
                "coal_ore", "deepslate_coal_ore", "copper_ore", "deepslate_copper_ore",
                "iron_ore", "deepslate_iron_ore", "gold_ore", "deepslate_gold_ore",
                "redstone_ore", "deepslate_redstone_ore", "lapis_ore",
                "deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore",
                "emerald_ore", "deepslate_emerald_ore", "nether_quartz_ore",
                "nether_gold_ore", "ancient_debris"
            )
            val oreDrops = setOf(
                "coal", "raw_copper", "raw_iron", "raw_gold", "redstone",
                "lapis_lazuli", "diamond", "emerald", "quartz", "gold_nugget",
                "ancient_debris"
            )
            val fishDrops = setOf("cod", "salmon", "pufferfish", "tropical_fish")
            val naturalBlockDropProfiles = mapOf(
                cropBlocks to cropDrops,
                logBlocks to logBlocks,
                oreBlocks to oreDrops
            )
            val naturalXpBlocks = cropBlocks + logBlocks + oreBlocks +
                setOf("stone", "deepslate")
            val smeltableOreBlocks = setOf(
                "iron_ore", "deepslate_iron_ore", "gold_ore", "deepslate_gold_ore",
                "copper_ore", "deepslate_copper_ore"
            )
            val rarityLines = setOf(
                "&8普通宠物", "&a少见的宠物", "&9稀有宠物",
                "&5史诗宠物", "&6传说宠物", "&d神话宠物"
            )
            val rarityCounts = rarityLines.associateWith { 0 }.toMutableMap()
            fun petCaps(
                strength: Double, health: Double, regeneration: Double, luck: Double,
                wisdom: Double, toughness: Double, critChance: Double,
                critDamage: Double, speed: Double
            ) = mapOf(
                "strength" to strength, "health" to health, "regeneration" to regeneration,
                "luck" to luck, "wisdom" to wisdom, "toughness" to toughness,
                "crit_chance" to critChance, "crit_damage" to critDamage, "speed" to speed
            )
            // 独立内容包直接使用 AuraSkills 属性单位。
            val petStatCaps = mapOf(
                "&8普通宠物" to petCaps(8.0, 1.0, 4.0, 4.0, 3.0, 8.0, 2.0, 4.0, 3.0),
                "&a少见的宠物" to petCaps(14.0, 2.0, 7.0, 7.0, 5.0, 12.0, 3.0, 8.0, 4.0),
                "&9稀有宠物" to petCaps(20.0, 3.0, 11.0, 11.0, 8.0, 18.0, 5.0, 12.0, 6.0),
                "&5史诗宠物" to petCaps(28.0, 4.0, 16.0, 16.0, 10.0, 26.0, 7.0, 18.0, 9.0),
                "&6传说宠物" to petCaps(38.0, 6.0, 22.0, 22.0, 14.0, 36.0, 9.0, 24.0, 12.0),
                "&d神话宠物" to petCaps(50.0, 8.0, 30.0, 30.0, 18.0, 48.0, 12.0, 32.0, 15.0)
            )
            val petStatCounts = mapOf(
                "&8普通宠物" to 1, "&a少见的宠物" to 2, "&9稀有宠物" to 2,
                "&5史诗宠物" to 3, "&6传说宠物" to 3, "&d神话宠物" to 4
            )
            val minimumPetSpecialEffectCounts = mapOf(
                "&8普通宠物" to 1, "&a少见的宠物" to 1, "&9稀有宠物" to 1,
                "&5史诗宠物" to 2, "&6传说宠物" to 2, "&d神话宠物" to 3
            )
            val petBudgetCaps = mapOf(
                "&8普通宠物" to 1.01, "&a少见的宠物" to 1.61,
                "&9稀有宠物" to 1.70, "&5史诗宠物" to 2.21,
                "&6传说宠物" to 2.21, "&d神话宠物" to 2.81
            )
            val petBudgetFloors = mapOf(
                "&8普通宠物" to 0.99, "&a少见的宠物" to 1.49,
                "&9稀有宠物" to 1.59, "&5史诗宠物" to 1.99,
                "&6传说宠物" to 2.07, "&d神话宠物" to 2.60
            )
            val levelAmount = Regex("^%level%\\s*\\*\\s*([0-9]+(?:\\.[0-9]+)?)$")
            val levelMultiplier = Regex(
                "^1\\s*([+-])\\s*\\(\\s*%level%\\s*\\*\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\)$"
            )
            val xpFormulaByRarity = mapOf(
                "&8普通宠物" to "(%level% * %level% * 8) + (%level% * 40)",
                "&a少见的宠物" to "(%level% * %level% * 10) + (%level% * 50)",
                "&9稀有宠物" to "(%level% * %level% * 13) + (%level% * 65)",
                "&5史诗宠物" to "(%level% * %level% * 17) + (%level% * 85)",
                "&6传说宠物" to "(%level% * %level% * 22) + (%level% * 110)",
                "&d神话宠物" to "(%level% * %level% * 28) + (%level% * 140)"
            )
            val safeEffectIdentifier = Regex("[#a-z0-9_:/.-]+")

            fun stringList(value: Any?, context: String): List<String> {
                val values = value as? List<*>
                    ?: throw GradleException("$context 必须是非空字符串列表")
                val strings = values.map { it?.toString() ?: "" }
                if (strings.isEmpty() || strings.any { !safeEffectIdentifier.matches(it) } ||
                    strings.size != strings.toSet().size) {
                    throw GradleException("$context 必须是非空、不重复的安全标识符列表")
                }
                return strings
            }

            fun validateMaterialIdentifiers(values: List<String>, context: String) {
                val invalid = values.filter { identifier ->
                    !identifier.startsWith("#") && ':' !in identifier &&
                        identifier.uppercase() !in validMaterials
                }
                if (invalid.isNotEmpty()) {
                    throw GradleException("$context 包含无效 Paper 材料：$invalid")
                }
            }

            fun positiveLevelAmount(value: Any?, context: String): String {
                val formula = value?.toString()
                    ?: throw GradleException("$context 缺少等级公式")
                val match = levelAmount.matchEntire(formula)
                    ?: throw GradleException("$context 必须是 %level% 的正数线性公式")
                val level100Value = match.groupValues[1].toDouble() * 100.0
                if (!level100Value.isFinite() || level100Value <= 0.0) {
                    throw GradleException("$context 满级数值必须为有限正数")
                }
                return formula
            }

            fun levelMultiplierFormula(
                value: Any?, context: String, requiredSign: String? = null
            ): Double {
                val formula = value?.toString()
                    ?: throw GradleException("$context 缺少倍率公式")
                val match = levelMultiplier.matchEntire(formula)
                    ?: throw GradleException(
                        "$context 必须严格使用 1 + (%level% * c) 或 1 - (%level% * c)"
                    )
                val sign = match.groupValues[1]
                if (requiredSign != null && sign != requiredSign) {
                    throw GradleException("$context 必须使用 1 $requiredSign (%level% * c)")
                }
                val coefficient = match.groupValues[2].toDouble()
                val level100Multiplier = if (sign == "+") {
                    1.0 + coefficient * 100.0
                } else {
                    1.0 - coefficient * 100.0
                }
                if (!coefficient.isFinite() || coefficient <= 0.0 || level100Multiplier <= 0.0) {
                    throw GradleException("$context 必须在 1..100 级保持有限正倍率")
                }
                return level100Multiplier
            }

            fun collectStrings(value: Any?, output: MutableList<String>) {
                when (value) {
                    is String -> output += value
                    is Map<*, *> -> value.values.forEach { collectStrings(it, output) }
                    is Iterable<*> -> value.forEach { collectStrings(it, output) }
                }
            }

            val petTexturePattern = Regex(
                """^\{"textures":\{"SKIN":\{"url":"http://textures\.minecraft\.net/texture/([0-9a-f]{32,64})"\}\}\}$"""
            )
            val petTextureHashes = mutableSetOf<String>()
            for (petFile in petFiles) {
                val petId = petFile.nameWithoutExtension
                val pet = parsedFiles[petFile.name]
                    ?: throw GradleException("$petId 未能解析为 EcoPets 配置")
                val maxLevel = pet["max-level"] as? Number
                if (maxLevel == null || maxLevel.toDouble() != 100.0) {
                    throw GradleException("$petId 的 max-level 必须为 100")
                }
                for (key in listOf("name", "description", "entity-texture", "icon", "xp-formula")) {
                    if ((pet[key] as? String).isNullOrBlank()) {
                        throw GradleException("$petId 缺少非空 $key")
                    }
                }

                val entityTexture = pet["entity-texture"].toString()
                val decodedTexture = try {
                    String(Base64.getDecoder().decode(entityTexture), Charsets.UTF_8)
                } catch (exception: IllegalArgumentException) {
                    throw GradleException("$petId 的 entity-texture 不是有效 Base64", exception)
                }
                val textureMatch = petTexturePattern.matchEntire(decodedTexture)
                    ?: throw GradleException(
                        "$petId 的 entity-texture 必须指向安全的 Minecraft 纹理哈希"
                    )
                if (!petTextureHashes.add(textureMatch.groupValues[1])) {
                    throw GradleException("$petId 与其他宠物重复使用了同一头颅纹理")
                }
                if (pet["icon"]?.toString() != "player_head texture:$entityTexture") {
                    throw GradleException("$petId 的 icon 必须与 entity-texture 使用同一头颅纹理")
                }

                val xpGainMethods = pet["xp-gain-methods"] as? List<*>
                    ?: throw GradleException("$petId 缺少 xp-gain-methods 列表")
                if (xpGainMethods.isEmpty()) {
                    throw GradleException("$petId 必须至少定义一种经验来源")
                }
                val configuredXpTriggers = mutableSetOf<String>()
                for ((index, rawMethod) in xpGainMethods.withIndex()) {
                    val method = rawMethod as? Map<*, *>
                        ?: throw GradleException("$petId 的 xp-gain-methods[$index] 必须是映射")
                    val methodKeys = method.keys.map { it.toString() }.toSet()
                    if ("id" !in methodKeys ||
                        methodKeys.any {
                            it !in setOf("id", "value", "multiplier", "filters", "conditions")
                        }) {
                        throw GradleException(
                            "$petId 的 xp-gain-methods[$index] 参数无效：$methodKeys"
                        )
                    }
                    val trigger = method["id"]?.toString()
                    if (trigger == null || trigger !in petXpTriggers) {
                        throw GradleException("$petId 使用了未经验证的经验触发器：$trigger")
                    }
                    if (!configuredXpTriggers.add(trigger)) {
                        throw GradleException("$petId 重复定义了经验触发器：$trigger")
                    }
                    val hasValue = method["value"] is Number
                    val hasMultiplier = method["multiplier"] is Number
                    if (hasValue == hasMultiplier) {
                        throw GradleException(
                            "$petId 的 xp-gain-methods[$index] 必须且只能配置数值 value 或 multiplier"
                        )
                    }
                    val xpNumber = (method[if (hasValue) "value" else "multiplier"] as Number)
                        .toDouble()
                    if (!xpNumber.isFinite() || xpNumber <= 0.0) {
                        throw GradleException(
                            "$petId 的 xp-gain-methods[$index] 经验值必须为有限正数"
                        )
                    }
                    if ((hasValue && xpNumber > 50.0) || (hasMultiplier && xpNumber > 10.0)) {
                        throw GradleException(
                            "$petId 的 xp-gain-methods[$index] 经验倍率超出安全上限"
                        )
                    }
                    val filters = method["filters"] as? Map<*, *>
                    if ("filters" in methodKeys && filters == null) {
                        throw GradleException("$petId 的 xp-gain-methods[$index].filters 必须是映射")
                    }
                    if (filters != null) {
                        val filterKeys = filters.keys.map { it.toString() }.toSet()
                        when (trigger) {
                            "kill", "melee_attack", "bow_attack" -> {
                                if (filterKeys != setOf("not_entities") ||
                                    stringList(
                                        filters["not_entities"],
                                        "$petId 的 xp-gain-methods[$index].filters.not_entities"
                                    ).toSet() != setOf("player", "armor_stand")) {
                                    throw GradleException(
                                        "$petId 的 $trigger 经验必须严格排除 player 与 armor_stand"
                                    )
                                }
                            }
                            "mine_block" -> {
                                val blocks = stringList(
                                    filters["blocks"],
                                    "$petId 的 xp-gain-methods[$index].filters.blocks"
                                )
                                validateMaterialIdentifiers(
                                    blocks,
                                    "$petId 的 xp-gain-methods[$index].filters.blocks"
                                )
                                if (blocks.any { it !in naturalXpBlocks }) {
                                    throw GradleException("$petId 的采集经验只能来自明确白名单内的自然资源")
                                }
                                val isCropProfile = blocks.all { it in cropBlocks }
                                if (isCropProfile) {
                                    if (filterKeys != setOf("blocks", "fully_grown") ||
                                        filters["fully_grown"] != true) {
                                        throw GradleException("$petId 的作物经验必须严格限定八种成熟作物")
                                    }
                                } else if (filterKeys != setOf("blocks", "player_placed") ||
                                    filters["player_placed"] != false) {
                                    throw GradleException("$petId 的自然资源经验必须排除玩家放置方块")
                                }
                            }
                            else -> throw GradleException("$petId 的 $trigger 经验不得配置 filters")
                        }
                    }
                    val conditions = method["conditions"] as? List<*>
                    if ("conditions" in methodKeys) {
                        val validSprintCondition = trigger == "move" &&
                            conditions?.size == 1 &&
                            (conditions.single() as? Map<*, *>)?.let { condition ->
                                condition.keys.map { it.toString() }.toSet() == setOf("id") &&
                                    condition["id"]?.toString() == "is_sprinting"
                            } == true
                        if (!validSprintCondition) {
                            throw GradleException(
                                "$petId 的经验 conditions 只允许 move 使用单一 is_sprinting 条件"
                            )
                        }
                    }
                }

                val spawnEgg = pet["spawn-egg"] as? Map<*, *>
                    ?: throw GradleException("$petId 缺少 spawn-egg")
                if (spawnEgg["enabled"] != true || (spawnEgg["item"] as? String).isNullOrBlank()) {
                    throw GradleException("$petId 必须启用并配置 spawn-egg")
                }
                val spawnEggMaterial = spawnEgg["item"].toString()
                    .substringBefore(' ')
                    .uppercase()
                if (spawnEggMaterial !in validMaterials) {
                    throw GradleException(
                        "$petId 的 spawn-egg.item 使用了无效 Paper 材料 $spawnEggMaterial"
                    )
                }
                if (spawnEgg["craftable"] != false ||
                    (spawnEgg["recipe"] as? List<*>)?.isNotEmpty() != false ||
                    spawnEgg["withdrawable"] != true ||
                    spawnEgg["recipe-permission"]?.toString() != "ecopets.craft.$petId") {
                    throw GradleException(
                        "$petId 的宠物蛋必须禁止 EcoPets 配方、允许收回并使用独立权限"
                    )
                }

                val placeholders = pet["level-placeholders"] as? List<*>
                    ?: throw GradleException("$petId 缺少 level-placeholders")
                val placeholderValues = linkedMapOf<String, String>()
                for (rawPlaceholder in placeholders) {
                    val placeholder = rawPlaceholder as? Map<*, *>
                        ?: throw GradleException("$petId 的 level-placeholders 必须是映射列表")
                    if (placeholder.keys.map { it.toString() }.toSet() != setOf("id", "value")) {
                        throw GradleException(
                            "$petId 的 level-placeholders 只能配置 id 与 value"
                        )
                    }
                    val id = placeholder["id"]?.toString()
                        ?: throw GradleException("$petId 的 level-placeholders 缺少 id")
                    val value = placeholder["value"]?.toString()
                        ?: throw GradleException("$petId 的 $id 缺少 value")
                    if (!Regex("[a-z0-9_]+").matches(id) ||
                        placeholderValues.putIfAbsent(id, value) != null) {
                        throw GradleException(
                            "$petId 的 level-placeholders ID 必须安全且不得重复：$id"
                        )
                    }
                }

                val effects = pet["effects"] as? List<*>
                    ?: throw GradleException("$petId 缺少 effects 列表")
                if (effects.isEmpty()) {
                    throw GradleException("$petId 必须至少定义一个 add_stat 效果")
                }
                val petStats = mutableSetOf<String>()
                val petStatValues = mutableMapOf<String, Double>()
                val uniqueSpecialEffects = mutableSetOf<String>()
                for ((index, rawEffect) in effects.withIndex()) {
                    val effect = rawEffect as? Map<*, *>
                        ?: throw GradleException("$petId 的 effects[$index] 必须是映射")
                    val effectId = effect["id"]?.toString()
                        ?: throw GradleException("$petId 的 effects[$index] 缺少 id")
                    if (effectId !in permanentPetEffects && effectId !in triggeredPetEffects) {
                        throw GradleException("$petId 使用了未经验证的宠物效果：$effectId")
                    }
                    if (effectId != "add_stat" && !uniqueSpecialEffects.add(effectId)) {
                        throw GradleException("$petId 重复定义了特殊效果：$effectId")
                    }
                    val effectKeys = effect.keys.map { it.toString() }.toSet()
                    if (effectId in permanentPetEffects && "triggers" in effectKeys) {
                        throw GradleException("$petId 的永久效果 $effectId 不得配置 triggers")
                    }
                    val allowedTriggers = triggeredPetEffects[effectId]
                    val triggers = if (allowedTriggers != null) {
                        val configured = stringList(
                            effect["triggers"], "$petId 的 $effectId.triggers"
                        )
                        if (configured.any { it !in allowedTriggers }) {
                            throw GradleException(
                                "$petId 的 $effectId 只允许触发器 $allowedTriggers：$configured"
                            )
                        }
                        configured.toSet()
                    } else {
                        emptySet()
                    }

                    if (effectId == "add_stat") {
                        if (effectKeys != setOf("id", "args")) {
                            throw GradleException("$petId 的 add_stat 只能配置 id 与 args")
                        }
                        val args = effect["args"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 effects[$index] 缺少 args")
                        if (args.keys.map { it.toString() }.toSet() !=
                            setOf("stat", "amount")) {
                            throw GradleException(
                                "$petId 的 add_stat.args 必须严格包含 stat 与 amount"
                            )
                        }
                        val stat = args["stat"]?.toString()
                        if (stat == null || stat !in auraSkillsStats) {
                            throw GradleException("$petId 使用了无效 AuraSkills 属性：$stat")
                        }
                        if (!petStats.add(stat)) {
                            throw GradleException("$petId 重复定义了 AuraSkills 属性：$stat")
                        }
                        val amount = positiveLevelAmount(
                            args["amount"], "$petId 的 effects[$index].args.amount"
                        )
                        val level100Value =
                            levelAmount.matchEntire(amount)!!.groupValues[1].toDouble() * 100.0
                        petStatValues[stat] = level100Value
                        if (placeholderValues["${stat}_bonus"] != amount) {
                            throw GradleException(
                                "$petId 的 ${stat}_bonus 必须与 add_stat amount 完全一致"
                            )
                        }
                        continue
                    }

                    if (effectId in multiplierPetEffects) {
                        if (effectKeys != setOf("id", "args")) {
                            throw GradleException(
                                "$petId 的倍率效果 $effectId 只能配置 id 与 args"
                            )
                        }
                        val args = effect["args"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 $effectId 缺少 args")
                        val argsKeys = args.keys.map { it.toString() }.toSet()
                        val allowedArgs = when (effectId) {
                            "skill_xp_multiplier" -> setOf("multiplier", "skills")
                            "pet_xp_multiplier" -> setOf("multiplier", "pets")
                            else -> setOf("multiplier")
                        }
                        val requiredArgs = if (effectId == "pet_xp_multiplier") {
                            setOf("multiplier")
                        } else {
                            allowedArgs
                        }
                        if (!argsKeys.containsAll(requiredArgs) ||
                            argsKeys.any { it !in allowedArgs }) {
                            throw GradleException(
                                "$petId 的 $effectId.args 参数无效：$argsKeys"
                            )
                        }
                        val requiredSign = if (effectId == "brew_time_multiplier") "-" else "+"
                        levelMultiplierFormula(
                            args["multiplier"],
                            "$petId 的 $effectId.args.multiplier",
                            requiredSign
                        )
                        if (effectId == "skill_xp_multiplier") {
                            val skills = stringList(
                                args["skills"], "$petId 的 skill_xp_multiplier.args.skills"
                            )
                            if (skills.any { it !in allowedSkillXpSkills }) {
                                throw GradleException(
                                    "$petId 的 skill_xp_multiplier 使用了未知技能：$skills"
                                )
                            }
                        }
                        if ("pets" in argsKeys) {
                            val boostedPets = stringList(
                                args["pets"], "$petId 的 pet_xp_multiplier.args.pets"
                            )
                            if (boostedPets.any { it !in petIds }) {
                                throw GradleException(
                                    "$petId 的 pet_xp_multiplier 引用了未知宠物：$boostedPets"
                                )
                            }
                        }
                        continue
                    }

                    if (effectId in flatPetEffects) {
                        if (effectKeys != setOf("id", "args")) {
                            throw GradleException(
                                "$petId 的数值效果 $effectId 只能配置 id 与 args"
                            )
                        }
                        val argument = flatPetEffects.getValue(effectId)
                        val args = effect["args"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 $effectId 缺少 args")
                        if (args.keys.map { it.toString() }.toSet() != setOf(argument)) {
                            throw GradleException(
                                "$petId 的 $effectId.args 必须严格包含 $argument"
                            )
                        }
                        positiveLevelAmount(
                            args[argument], "$petId 的 $effectId.args.$argument"
                        )
                        continue
                    }

                    if (effectId in chancePetEffects) {
                        if (effectKeys != setOf("id", "args")) {
                            throw GradleException(
                                "$petId 的概率效果 $effectId 只能配置 id 与 args"
                            )
                        }
                        val args = effect["args"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 $effectId 缺少 args")
                        if (args.keys.map { it.toString() }.toSet() != setOf("chance")) {
                            throw GradleException(
                                "$petId 的 $effectId.args 必须严格包含 chance"
                            )
                        }
                        val formula = positiveLevelAmount(
                            args["chance"], "$petId 的 $effectId.args.chance"
                        )
                        val level100Chance =
                            levelAmount.matchEntire(formula)!!.groupValues[1].toDouble() * 100.0
                        if (level100Chance > 100.0 + 0.000001) {
                            throw GradleException(
                                "$petId 的 $effectId 满级概率不得超过 100%"
                            )
                        }
                        continue
                    }

                    if (effectId in staticPetEffects) {
                        when (effectId) {
                            "replant_crops" -> {
                                if (effectKeys != setOf("id", "args")) {
                                    throw GradleException(
                                        "$petId 的 replant_crops 只能配置 id 与 args"
                                    )
                                }
                                val args = effect["args"] as? Map<*, *>
                                    ?: throw GradleException(
                                        "$petId 的 replant_crops 缺少 args"
                                    )
                                if (args.keys.map { it.toString() }.toSet() !=
                                    setOf("consume_seeds", "only_fully_grown") ||
                                    args["consume_seeds"] != true ||
                                    args["only_fully_grown"] != true) {
                                    throw GradleException(
                                        "$petId 的 replant_crops 必须消费种子且仅补种成熟作物"
                                    )
                                }
                            }
                            "item_magnet" -> {
                                if (effectKeys != setOf("id", "args")) {
                                    throw GradleException(
                                        "$petId 的 item_magnet 只能配置 id 与 args"
                                    )
                                }
                                val args = effect["args"] as? Map<*, *>
                                    ?: throw GradleException("$petId 的 item_magnet 缺少 args")
                                val argsKeys = args.keys.map { it.toString() }.toSet()
                                val allowedArgs =
                                    setOf("radius", "pull_strength", "items", "exclude_items")
                                if ("radius" !in argsKeys ||
                                    argsKeys.any { it !in allowedArgs }) {
                                    throw GradleException(
                                        "$petId 的 item_magnet.args 参数无效：$argsKeys"
                                    )
                                }
                                for (argName in listOf("radius", "pull_strength")) {
                                    if (argName !in argsKeys) continue
                                    val value = (args[argName] as? Number)?.toDouble()
                                    if (value == null || !value.isFinite() || value <= 0.0) {
                                        throw GradleException(
                                            "$petId 的 item_magnet.args.$argName 必须为有限正数"
                                        )
                                    }
                                }
                                for (argName in listOf("items", "exclude_items")) {
                                    if (argName in argsKeys) {
                                        val items = stringList(
                                            args[argName],
                                            "$petId 的 item_magnet.args.$argName"
                                        )
                                        validateMaterialIdentifiers(
                                            items,
                                            "$petId 的 item_magnet.args.$argName"
                                        )
                                    }
                                }
                            }
                            "feather_step" -> {
                                if (effectKeys != setOf("id")) {
                                    throw GradleException(
                                        "$petId 的 feather_step 不接受额外参数"
                                    )
                                }
                            }
                            "telekinesis" -> {
                                if (effectKeys != setOf("id") &&
                                    effectKeys != setOf("id", "args")) {
                                    throw GradleException(
                                        "$petId 的 telekinesis 只能配置 id 与可选 args"
                                    )
                                }
                                if ("args" in effectKeys) {
                                    val args = effect["args"] as? Map<*, *>
                                        ?: throw GradleException(
                                            "$petId 的 telekinesis.args 必须是映射"
                                        )
                                    if (args.keys.map { it.toString() }.toSet() !=
                                        setOf("on_tamed_mob_kills") ||
                                        args["on_tamed_mob_kills"] !is Boolean) {
                                        throw GradleException(
                                            "$petId 的 telekinesis.args 只能包含布尔 on_tamed_mob_kills"
                                        )
                                    }
                                }
                            }
                        }
                        continue
                    }

                    if (effectId == "multiply_drops") {
                        if (effectKeys != setOf("id", "args", "triggers", "filters")) {
                            throw GradleException(
                                "$petId 的 multiply_drops 必须严格配置 id/args/triggers/filters"
                            )
                        }
                        if (triggers.size != 1) {
                            throw GradleException(
                                "$petId 的 multiply_drops 必须且只能配置一种触发器"
                            )
                        }
                        val args = effect["args"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 multiply_drops 缺少 args")
                        val argsKeys = args.keys.map { it.toString() }.toSet()
                        if (argsKeys != setOf("multiplier", "chance", "on_items")) {
                            throw GradleException(
                                "$petId 的 multiply_drops.args 必须严格包含 multiplier/chance/on_items"
                            )
                        }
                        if ((args["multiplier"] as? Number)?.toDouble() != 2.0) {
                            throw GradleException(
                                "$petId 的 multiply_drops.multiplier 必须恰为 2"
                            )
                        }
                        val chance = positiveLevelAmount(
                            args["chance"], "$petId 的 multiply_drops.args.chance"
                        )
                        val level100Chance =
                            levelAmount.matchEntire(chance)!!.groupValues[1].toDouble() * 100.0
                        if (level100Chance > 15.0 + 0.000001) {
                            throw GradleException(
                                "$petId 的 multiply_drops 满级双倍概率不得超过 15%"
                            )
                        }
                        val onItems = stringList(
                            args["on_items"], "$petId 的 multiply_drops.args.on_items"
                        )
                        validateMaterialIdentifiers(
                            onItems, "$petId 的 multiply_drops.args.on_items"
                        )
                        val filters = effect["filters"] as? Map<*, *>
                            ?: throw GradleException(
                                "$petId 的 multiply_drops 必须配置 filters"
                            )
                        val filterKeys = filters.keys.map { it.toString() }.toSet()
                        val allowedFilterKeys =
                            setOf("blocks", "items", "player_placed", "fully_grown")
                        if (filterKeys.isEmpty() ||
                            filterKeys.any { it !in allowedFilterKeys }) {
                            throw GradleException(
                                "$petId 的 multiply_drops.filters 参数无效：$filterKeys"
                            )
                        }
                        if (triggers == setOf("block_item_drop")) {
                            val blocks = stringList(
                                filters["blocks"],
                                "$petId 的 multiply_drops.filters.blocks"
                            )
                            validateMaterialIdentifiers(
                                blocks, "$petId 的 multiply_drops.filters.blocks"
                            )
                            val blockSet = blocks.toSet()
                            val expectedDrops = naturalBlockDropProfiles[blockSet]
                                ?: throw GradleException(
                                    "$petId 的 multiply_drops 方块必须完整匹配作物、原木或矿石白名单"
                                )
                            if (onItems.toSet() != expectedDrops) {
                                throw GradleException(
                                    "$petId 的 multiply_drops 掉落物必须与自然资源白名单精确对应"
                                )
                            }
                            if (blockSet == cropBlocks) {
                                if (filters["fully_grown"] != true ||
                                    "player_placed" in filterKeys) {
                                    throw GradleException(
                                        "$petId 的作物 multiply_drops 必须严格限定八种成熟作物且不配置 player_placed"
                                    )
                                }
                                if (filterKeys != setOf("blocks", "fully_grown")) {
                                    throw GradleException(
                                        "$petId 的作物 multiply_drops 过滤器不得包含额外参数"
                                    )
                                }
                            } else {
                                if (filterKeys != setOf("blocks", "player_placed") ||
                                    filters["player_placed"] != false) {
                                    throw GradleException(
                                        "$petId 的非作物 multiply_drops 必须配置 player_placed: false"
                                    )
                                }
                            }
                        } else if (triggers == setOf("catch_fish")) {
                            if (filterKeys != setOf("items")) {
                                throw GradleException(
                                    "$petId 的钓鱼 multiply_drops 只能配置 items 过滤器"
                                )
                            }
                            val fishItems = stringList(
                                filters["items"],
                                "$petId 的 multiply_drops.filters.items"
                            )
                            validateMaterialIdentifiers(
                                fishItems, "$petId 的 multiply_drops.filters.items"
                            )
                            if (fishItems.toSet() != fishDrops ||
                                onItems.toSet() != fishDrops) {
                                throw GradleException(
                                    "$petId 的钓鱼 multiply_drops 只能作用于四种原版鱼获"
                                )
                            }
                        }
                        continue
                    }

                    if (effectId == "autosmelt") {
                        if (effectKeys != setOf("id", "args", "triggers", "filters") ||
                            triggers != setOf("block_item_drop")) {
                            throw GradleException(
                                "$petId 的 autosmelt 必须严格使用 block_item_drop 且配置 filters"
                            )
                        }
                        val args = effect["args"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 autosmelt 缺少 args")
                        if (args.keys.map { it.toString() }.toSet() != setOf("drop_xp") ||
                            args["drop_xp"] !is Boolean) {
                            throw GradleException(
                                "$petId 的 autosmelt.args 必须严格包含布尔 drop_xp"
                            )
                        }
                        val filters = effect["filters"] as? Map<*, *>
                            ?: throw GradleException("$petId 的 autosmelt 缺少 filters")
                        if (filters.keys.map { it.toString() }.toSet() !=
                            setOf("blocks", "player_placed") ||
                            filters["player_placed"] != false) {
                            throw GradleException(
                                "$petId 的 autosmelt.filters 必须严格包含 blocks 与 player_placed: false"
                            )
                        }
                        val blocks = stringList(
                            filters["blocks"], "$petId 的 autosmelt.filters.blocks"
                        )
                        if (blocks.any { it !in smeltableOreBlocks }) {
                            throw GradleException(
                                "$petId 的 autosmelt 只允许铁、金、铜的普通/深板岩矿石"
                            )
                        }
                        continue
                    }

                    throw GradleException("$petId 的效果 $effectId 未完成严格校验")
                }

                val expectedPlaceholderIds =
                    petStats.map { "${it}_bonus" }.toSet()
                if (!placeholderValues.keys.containsAll(expectedPlaceholderIds)) {
                    throw GradleException(
                        "$petId 的 level-placeholders 必须完整覆盖 AuraSkills 属性"
                    )
                }
                val perkPlaceholderIds = placeholderValues.keys - expectedPlaceholderIds
                if (perkPlaceholderIds.any { !Regex("perk_[a-z0-9_]+").matches(it) }) {
                    throw GradleException(
                        "$petId 的特殊效果占位符必须使用 perk_* 小写 ID"
                    )
                }
                for (placeholderId in perkPlaceholderIds) {
                    positiveLevelAmount(
                        placeholderValues.getValue(placeholderId),
                        "$petId 的 $placeholderId"
                    )
                }

                val allStrings = mutableListOf<String>()
                collectStrings(pet, allStrings)
                val petRarities = allStrings.filter { it in rarityLines }
                if (petRarities.size != 1) {
                    throw GradleException(
                        "$petId 必须且只能包含一个规定格式的稀有度宠物尾行"
                    )
                }
                val petRarity = petRarities.single()
                rarityCounts[petRarity] = rarityCounts.getValue(petRarity) + 1
                if (pet["xp-formula"]?.toString() != xpFormulaByRarity.getValue(petRarity)) {
                    throw GradleException("$petId 的 xp-formula 必须与 $petRarity 档位严格一致")
                }
                val minimumSpecialEffects =
                    minimumPetSpecialEffectCounts.getValue(petRarity)
                if (uniqueSpecialEffects.size < minimumSpecialEffects) {
                    throw GradleException(
                        "$petId 的特殊效果数 ${uniqueSpecialEffects.size} 低于 $petRarity 最低要求 $minimumSpecialEffects"
                    )
                }
                if (petStats.size != petStatCounts.getValue(petRarity)) {
                    throw GradleException("$petId 的属性数量不符合 $petRarity 档位")
                }
                val statCaps = petStatCaps.getValue(petRarity)
                var normalizedBudget = 0.0
                for ((stat, value) in petStatValues) {
                    val cap = statCaps.getValue(stat)
                    if (value > cap + 0.000001) {
                        throw GradleException(
                            "$petId 的 $stat 满级值 $value 超过 $petRarity 上限 $cap"
                        )
                    }
                    normalizedBudget += value / cap
                }
                val budgetFloor = petBudgetFloors.getValue(petRarity)
                val budgetCap = petBudgetCaps.getValue(petRarity)
                if (normalizedBudget < budgetFloor - 0.000001 ||
                    normalizedBudget > budgetCap + 0.000001) {
                    throw GradleException(
                        "$petId 的标准化属性预算 $normalizedBudget 不在 $petRarity 档位 $budgetFloor..$budgetCap 内"
                    )
                }

                fun descriptionLines(key: String): List<String> {
                    val sections = pet[key] as? Map<*, *>
                        ?: throw GradleException("$petId 缺少 $key")
                    val result = mutableListOf<String>()
                    for ((level, rawLines) in sections) {
                        val lines = rawLines as? List<*>
                            ?: throw GradleException("$petId 的 $key.$level 必须是列表")
                        result += lines.map { it?.toString() ?: "" }
                    }
                    return result
                }
                val effectDescription = descriptionLines("effects-description")
                val rewardsDescription = descriptionLines("rewards-description")
                val levelUpMessages = descriptionLines("level-up-messages")
                val rarityIndexes =
                    effectDescription.indices.filter { effectDescription[it] in rarityLines }
                if (rarityIndexes.size != 1 ||
                    rarityIndexes.single() != effectDescription.lastIndex) {
                    throw GradleException(
                        "$petId 的唯一稀有度宠物尾行必须是 effects-description 的最后一行"
                    )
                }
                if ((rewardsDescription + levelUpMessages).any { it in rarityLines }) {
                    throw GradleException(
                        "$petId 的稀有度宠物尾行不得出现在奖励或升级消息中"
                    )
                }
                val descriptionMarker = Regex("%([a-z0-9_]+)%")
                val referencedDescriptionPlaceholders =
                    (effectDescription + rewardsDescription + levelUpMessages)
                        .flatMap { line ->
                            descriptionMarker.findAll(line).map { it.groupValues[1] }.toList()
                        }
                        .toSet() - setOf("level")
                val unknownDescriptionPlaceholders =
                    referencedDescriptionPlaceholders - placeholderValues.keys
                if (unknownDescriptionPlaceholders.isNotEmpty()) {
                    throw GradleException(
                        "$petId 的 UI 文本引用了未声明占位符：$unknownDescriptionPlaceholders"
                    )
                }
                for (placeholderId in placeholderValues.keys) {
                    val marker = "%$placeholderId%"
                    if (effectDescription.none { marker in it } ||
                        rewardsDescription.none { marker in it } ||
                        levelUpMessages.none { marker in it }) {
                        throw GradleException(
                            "$petId 的占位符 $placeholderId 必须在效果、奖励和升级文本中双向一致地使用"
                        )
                    }
                }
            }
            if (rarityCounts.any { it.value != 12 }) {
                throw GradleException("EcoPets 六档稀有度必须各包含 12 只宠物：$rarityCounts")
            }
        }
    }
}

val ecoPetsPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 YknRpg 的 EcoPets 宠物定义"
    dependsOn(validateEcoPets)
    archiveFileName = "YknRpg-EcoPets-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    includeEmptyDirs = false
    from("packs/ecopets/pets") {
        into("pets")
        include("yknrpg/*.yml")
    }
}

val verifyEcoPetsPack by tasks.registering {
    group = "verification"
    description = "验证发布 ZIP 与 72 个源配置逐文件一致"
    dependsOn(ecoPetsPack)
    inputs.files(ecoPetFiles)
    inputs.file(ecoPetsPack.flatMap { it.archiveFile })

    doLast {
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        }

        val sources = ecoPetFiles.files.associateBy {
            "pets/yknrpg/${it.name}"
        }
        if (sources.size != 72) {
            throw GradleException("发布前必须正好有 72 个宠物源文件")
        }
        ZipFile(ecoPetsPack.get().archiveFile.get().asFile).use { archive ->
            val archiveEntries = mutableListOf<java.util.zip.ZipEntry>()
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) archiveEntries += enumeration.nextElement()
            val allowedDirectories = setOf("pets/", "pets/yknrpg/")
            if (archiveEntries.filter { it.isDirectory }.any { it.name !in allowedDirectories }) {
                throw GradleException("YknRpg EcoPets ZIP 包含未知目录")
            }
            val entries = archiveEntries.filterNot { it.isDirectory }
            if (entries.size != 72) {
                throw GradleException("YknRpg EcoPets ZIP 必须且只能包含 72 个 YAML 文件")
            }
            val names = entries.map { it.name }
            if (names.size != names.toSet().size || names.toSet() != sources.keys) {
                throw GradleException("YknRpg EcoPets ZIP 条目与 pets/yknrpg 源文件不完全一致")
            }
            for (entry in entries) {
                val source = sources.getValue(entry.name)
                val archiveHash = archive.getInputStream(entry).use(::sha256)
                val sourceHash = source.inputStream().use(::sha256)
                if (archiveHash != sourceHash) {
                    throw GradleException("ZIP 内容与源文件不一致：${entry.name}")
                }
            }
        }
    }
}

val validateYaml by tasks.registering {
    group = "verification"
    val validationLibraries = configurations.named("compileClasspath")
    val yamlFiles = files(
        "src/main/resources/config.yml",
        "src/main/resources/equipment.yml",
        "src/main/resources/rpg.yml",
        "src/main/resources/recipes.yml",
        "packs/craftengine/pack.yml",
        "packs/craftengine/configuration/items.yml",
        "packs/mythicmobs/items/YknRpg_Materials.yml",
        "packs/mythicmobs/items/YknRpg_Weapons.yml",
        "packs/mythicmobs/items/YknRpg_Armor.yml"
    )
    inputs.files(yamlFiles)
    doLast {
        val retiredStats = Regex("(?i)\\b(vitality(?:-scaling)?|ferocity)\\b|活力|凶猛")
        for (content in listOf("src/main/resources/rpg.yml",
                "packs/mythicmobs/items/YknRpg_Weapons.yml", "packs/mythicmobs/items/YknRpg_Armor.yml")) {
            if (retiredStats.containsMatchIn(file(content).readText(Charsets.UTF_8))) {
                throw GradleException("$content 不得重新加入已移除的活力或凶猛属性/效果/Lore")
            }
        }
        val urls = (yamlValidator.files + validationLibraries.get().files)
            .map { it.toURI().toURL() }
            .toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val yamlClass = loader.loadClass("org.yaml.snakeyaml.Yaml")
            val loaderOptionsClass = loader.loadClass("org.yaml.snakeyaml.LoaderOptions")
            val loaderOptions = loaderOptionsClass.getConstructor().newInstance()
            loaderOptionsClass.getMethod(
                "setAllowDuplicateKeys", Boolean::class.javaPrimitiveType!!
            ).invoke(loaderOptions, false)
            val yaml = yamlClass.getConstructor(loaderOptionsClass).newInstance(loaderOptions)
            val validMaterials = loader.loadClass("org.bukkit.Material").enumConstants
                .map { (it as Enum<*>).name }
                .toSet()
            val parsedFiles = linkedMapOf<String, Map<*, *>>()
            for (file in yamlFiles) {
                try {
                    file.inputStream().use { input ->
                        val parsed = yamlClass.getMethod("load", InputStream::class.java).invoke(yaml, input)
                        if (parsed !is Map<*, *>) throw GradleException("${file.name} 根节点必须是映射")
                        parsedFiles[file.name] = parsed
                    }
                } catch (exception: InvocationTargetException) {
                    throw GradleException("${file.name} YAML 解析失败", exception.targetException)
                }
            }

            val equipment = parsedFiles.getValue("equipment.yml")
            val config = parsedFiles.getValue("config.yml")
            val rpg = parsedFiles.getValue("rpg.yml")
            val catalog = equipment["catalog-order"] as? List<*>
                ?: throw GradleException("equipment.yml 缺少 catalog-order")
            val defaultRecipes = equipment["default-recipes"] as? Map<*, *>
                ?: throw GradleException("equipment.yml 缺少 default-recipes")
            val catalogMetadata = equipment["catalog-metadata"] as? Map<*, *>
                ?: throw GradleException("equipment.yml 缺少 catalog-metadata")
            val materialIds = parsedFiles.getValue("YknRpg_Materials.yml").keys.map { it.toString() }.toSet()
            val weaponIds = parsedFiles.getValue("YknRpg_Weapons.yml").keys.map { it.toString() }.toSet()
            val armorIds = parsedFiles.getValue("YknRpg_Armor.yml").keys.map { it.toString() }.toSet()
            val mythicIds = materialIds + weaponIds + armorIds
            if (materialIds.size != 90 || weaponIds.size != 105 || armorIds.size != 132) {
                throw GradleException("完整内容必须包含 90 个材料、105 把武器和 33 套共 132 件护甲（总计 327 件）")
            }
            for ((weaponId, rawWeapon) in parsedFiles.getValue("YknRpg_Weapons.yml")) {
                val weapon = rawWeapon as? Map<*, *>
                    ?: throw GradleException("$weaponId 的 MythicMobs 定义必须是映射")
                val options = weapon["Options"] as? Map<*, *>
                    ?: throw GradleException("$weaponId 缺少 MythicMobs Options")
                if (options["Glint"] != true) {
                    throw GradleException("$weaponId 必须用 Options.Glint 显示光效")
                }
                val enchantments = weapon["Enchantments"] as? List<*>
                if (enchantments.orEmpty().any {
                        it.toString().substringBefore(':').equals("DURABILITY", ignoreCase = true)
                    }) {
                    throw GradleException("$weaponId 不得用 DURABILITY 假附魔制造光效，以免触发 AuraSkills 附魔力量")
                }
            }
            if (materialIds.size + weaponIds.size + armorIds.size != mythicIds.size) {
                throw GradleException("材料、武器和护甲文件之间存在重复 MythicMobs 物品 ID")
            }
            val catalogIds = catalog.map { it.toString() }
            if (catalogIds.size != catalogIds.toSet().size || catalogIds.toSet() != mythicIds) {
                throw GradleException("catalog-order 必须无重复且完整包含全部 MythicMobs 物品")
            }
            if (catalogMetadata.keys.map { it.toString() }.toSet() != mythicIds) {
                throw GradleException("catalog-metadata 必须完整定义全部物品")
            }
            val validKinds = setOf("ITEM", "WEAPON", "ARMOR")
            val validRarities = setOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")
            val kindRanks = mapOf("ITEM" to 0, "WEAPON" to 1, "ARMOR" to 2)
            val rarityRanks = validRarities.withIndex().associate { (rank, rarity) -> rarity to rank }
            for ((itemId, rawMetadata) in catalogMetadata) {
                val metadata = rawMetadata as? Map<*, *>
                    ?: throw GradleException("$itemId 的 catalog-metadata 必须是映射")
                val kind = metadata["kind"]?.toString()
                val rarity = metadata["rarity"]?.toString()
                val order = (metadata["order"] as? Number)?.toInt()
                if (kind !in validKinds) throw GradleException("$itemId 的图鉴类型无效：$kind")
                if (rarity !in validRarities) throw GradleException("$itemId 的稀有度无效：$rarity")
                if (order == null || order < 0) throw GradleException("$itemId 的目录顺序必须是非负整数")
                val expectedKind = when (itemId.toString()) {
                    in materialIds -> "ITEM"
                    in weaponIds -> "WEAPON"
                    else -> "ARMOR"
                }
                if (kind != expectedKind) throw GradleException("$itemId 必须归类为 $expectedKind")
            }
            val expectedRarityCounts = mapOf(
                "ITEM" to mapOf(
                    "COMMON" to 12, "UNCOMMON" to 12, "RARE" to 12,
                    "EPIC" to 18, "LEGENDARY" to 18, "MYTHIC" to 18
                ),
                "WEAPON" to mapOf(
                    "COMMON" to 13, "UNCOMMON" to 14, "RARE" to 15,
                    "EPIC" to 22, "LEGENDARY" to 21, "MYTHIC" to 20
                ),
                "ARMOR" to mapOf(
                    "COMMON" to 20, "UNCOMMON" to 20, "RARE" to 20,
                    "EPIC" to 24, "LEGENDARY" to 24, "MYTHIC" to 24
                )
            )
            for ((kind, expectedCounts) in expectedRarityCounts) {
                val rarityCounts = validRarities.associateWith { rarity ->
                    catalogMetadata.values.count { rawMetadata ->
                        val metadata = rawMetadata as Map<*, *>
                        metadata["kind"]?.toString() == kind && metadata["rarity"]?.toString() == rarity
                    }
                }
                if (rarityCounts != expectedCounts) {
                    throw GradleException("$kind 六档稀有度数量必须为 $expectedCounts：$rarityCounts")
                }
            }
            for (kind in validKinds) {
                for (rarity in validRarities) {
                    val orders = catalogMetadata.values.mapNotNull { rawMetadata ->
                        val metadata = rawMetadata as Map<*, *>
                        if (metadata["kind"]?.toString() != kind ||
                            metadata["rarity"]?.toString() != rarity) return@mapNotNull null
                        (metadata["order"] as Number).toInt()
                    }.sorted()
                    if (orders.zipWithNext().any { (left, right) -> right != left + 1 }) {
                        throw GradleException("$kind/$rarity 的目录顺序必须连续且不得重复：$orders")
                    }
                }
            }
            val expectedCatalogOrder = catalogIds.sortedWith(
                compareBy<String>(
                    { itemId ->
                        val metadata = catalogMetadata[itemId] as Map<*, *>
                        kindRanks.getValue(metadata["kind"].toString())
                    },
                    { itemId ->
                        val metadata = catalogMetadata[itemId] as Map<*, *>
                        rarityRanks.getValue(metadata["rarity"].toString())
                    },
                    { itemId ->
                        val metadata = catalogMetadata[itemId] as Map<*, *>
                        (metadata["order"] as Number).toInt()
                    },
                    { it }
                )
            )
            if (catalogIds != expectedCatalogOrder) {
                throw GradleException("catalog-order 必须按图鉴类型、稀有度和 order 正序排列")
            }

            val rarityLabels = mapOf(
                "COMMON" to "&8普通",
                "UNCOMMON" to "&a少见的",
                "RARE" to "&9稀有",
                "EPIC" to "&5史诗",
                "LEGENDARY" to "&6传说",
                "MYTHIC" to "&d神话"
            )
            val weaponCategories = mapOf(
                "YKI_RECRUIT_BLADE" to "剑",
                "YKI_BASTION_GREATSWORD" to "剑",
                "YKI_RIFTWALKER_EDGE" to "剑",
                "YKI_EARTHSHATTER_CLAYMORE" to "剑",
                "YKI_VITAL_OATHSWORD" to "剑",
                "YKI_CELESTIAL_VERDICT" to "剑",
                "YKI_COPPER_FALCHION" to "剑",
                "YKI_INFERNO_CHAINBLADE" to "剑",
                "YKI_PHOENIX_SABER" to "剑",
                "YKI_CHRONOSPLITTER" to "剑",
                "YKI_QUARTZ_RAPIER" to "剑",
                "YKI_PHANTOM_TWINBLADE" to "剑",
                "YKI_SUNFORGED_BLADE" to "剑",
                "YKI_SOVEREIGN_HOLYBLADE" to "剑",
                "YKI_FIELD_MEDIC_WAND" to "法杖",
                "YKI_GLACIER_SCEPTER" to "法杖",
                "YKI_HEARTHWARD_BRANCH" to "法杖",
                "YKI_ARCFLAME_STAFF" to "法杖",
                "YKI_GALE_SHORTBOW" to "弓",
                "YKI_GENESIS_LONGBOW" to "弓",
                "YKI_BRAMBLE_LONGBOW" to "弓",
                "YKI_SKYREND_LONGBOW" to "弓",
                "YKI_DRAGONSTORM_SPEAR" to "长枪",
                "YKI_TRAILBLAZER_SPEAR" to "长枪",
                "YKI_WARPATH_PIKE" to "长枪",
                "YKI_HEAVENPIERCER_LANCE" to "长枪",
                "YKI_ASTRAL_GRIMOIRE" to "法典",
                "YKI_WORLDROOT_CODEX" to "法典",
                "YKI_SOULRECALL_CODEX" to "法典",
                "YKI_LAWWEAVER_CODEX" to "法典",
                "YKI_WARDEN_MAUL" to "战锤",
                "YKI_OATHKEEPER_MAUL" to "战锤",
                "YKI_PRISM_HANDCANNON" to "枪械",
                "YKI_VOIDNEEDLE_RAILGUN" to "枪械",
                "YKI_STARFALL_CANNON" to "枪械",
                "YKI_ECLIPSE_SCYTHE" to "镰刀",
                "YKI_VEILHOOK_SICKLE" to "镰刀",
                "YKI_NAMELESS_REAPER" to "镰刀",
                "YKI_VERDANT_CENSER" to "法器",
                "YKI_REQUIEM_BELL" to "法器",
                "YKI_WARSONG_HORN" to "号角",
                "YKI_LEVIATHAN_TRIDENT" to "三叉戟",
                "YKI_THUNDERHEAD_REPEATER" to "弩",
                "YKI_CITADEL_STANDARD" to "战旗",
                "YKI_OAKEN_HUNTER_BOW" to "弓",
                "YKI_MILITIA_CROSSBOW" to "弩",
                "YKI_STONEWARD_MAUL" to "战锤",
                "YKI_GLOOM_SICKLE" to "镰刀",
                "YKI_SPRINGWATER_CHARM" to "法器",
                "YKI_CINDER_APPRENTICE_STAFF" to "法杖",
                "YKI_WATCHMAN_HALBERD" to "长枪",
                "YKI_TEMPEST_PIKE" to "长枪",
                "YKI_NIGHTSHADE_SICKLE" to "镰刀",
                "YKI_EMBERWEAVE_STAFF" to "法杖",
                "YKI_BLOOMSONG_TOME" to "法典",
                "YKI_FROSTBOLT_CROSSBOW" to "弩",
                "YKI_TIDEBREAKER_TRIDENT" to "三叉戟",
                "YKI_RALLYING_BUGLE" to "号角",
                "YKI_COMET_LONGBOW" to "弓",
                "YKI_SKYLINE_GLAIVE" to "长枪",
                "YKI_SOULTHREAD_SCYTHE" to "镰刀",
                "YKI_RUNEBULWARK_HAMMER" to "战锤",
                "YKI_MOONWELL_CODEX" to "法典",
                "YKI_VOLTAIC_ARBALEST" to "弩",
                "YKI_CORALBOUND_TRIDENT" to "三叉戟",
                "YKI_SOLARWIND_LONGBOW" to "弓",
                "YKI_STORMCORE_REPEATER" to "弩",
                "YKI_COLOSSUS_WARHAMMER" to "战锤",
                "YKI_NEBULA_SCEPTER" to "法杖",
                "YKI_SERAPHIC_CHALICE" to "法器",
                "YKI_DRAGONBANNER_HORN" to "号角",
                "YKI_MIRAGE_DAGGERS" to "短刃",
                "YKI_WINGBREAKER_GREATSWORD" to "剑",
                "YKI_SKULLBARRAGE_LONGBOW" to "弓",
                "YKI_DRAGONSPINE_LANCE" to "长枪",
                "YKI_GRAVETALON_SICKLE" to "镰刀",
                "YKI_MARROW_REQUIEM_CENSER" to "法器",
                "YKI_OSSUARY_BULWARK" to "战锤",
                "YKI_WRAITHFIRE_SCEPTER" to "法杖",
                "YKI_ENDSUN_EDICT_BLADE" to "剑",
                "YKI_SUNFALL_REPEATER" to "弩",
                "YKI_SOLAR_AEGIS_LANCE" to "长枪",
                "YKI_SCYTHE_ECHO_SICKLE" to "镰刀",
                "YKI_SOUL_CROWN_CENSER" to "法器",
                "YKI_HEARTFURNACE_WARHAMMER" to "战锤",
                "YKI_BLACK_SUN_CODEX" to "法典",
                "YKI_FATESEVER_HOLYBLADE" to "剑",
                "YKI_SEVENFOLD_END_BOW" to "弓",
                "YKI_HEAVEN_INVERSION_LANCE" to "长枪",
                "YKI_WITCHBLINK_SCISSORS" to "双刃",
                "YKI_SOULPAWN_REQUIEM" to "法器",
                "YKI_WORLD_END_BULWARK" to "盾牌",
                "YKI_SILENT_CHANT_CODEX" to "法典",
                "YKI_VEINSEEKER_MAUL" to "战锤",
                "YKI_TRAILSCOUT_SHORTBLADE" to "短刃",
                "YKI_CAMPWARD_SABER" to "剑",
                "YKI_TIDESEEKER_LANCE" to "长枪",
                "YKI_RESCUE_BEACON_CROSSBOW" to "弩",
                "YKI_TRUESIGHT_LONGBOW" to "弓",
                "YKI_SKYHOOK_TWINBLADE" to "双刃",
                "YKI_MARCHING_SANCTUARY_MAUL" to "战锤",
                "YKI_RIFTGUIDE_DAGGER" to "短刃",
                "YKI_EXPEDITION_WARHAMMER" to "战锤",
                "YKI_HOMECOMING_CHRONICLE" to "法典",
                "YKI_ASTRAL_WAYFARER_STAFF" to "法杖"
            )
            if (weaponCategories.keys != weaponIds) {
                throw GradleException("武器类别映射必须完整覆盖 105 把武器")
            }
            val armorPieceSuffixes = setOf("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")
            val armorSetPrefixes = setOf(
                "YKI_WAYFARER",
                "YKI_IRON_SENTINEL",
                "YKI_TIDECALLER",
                "YKI_NIGHTSTALKER",
                "YKI_WYRM_GUARD",
                "YKI_COSMIC_SAGE",
                "YKI_COPPER_TINKER",
                "YKI_BRAMBLE_RANGER",
                "YKI_PHANTOM_DUELIST",
                "YKI_MOLTEN_BERSERKER",
                "YKI_ABYSS_WARDEN",
                "YKI_CHRONOWARDEN",
                "YKI_RECRUIT_VANGUARD",
                "YKI_RUNE_ACOLYTE",
                "YKI_GRANITE_BULWARK",
                "YKI_STORM_EYE",
                "YKI_VOID_ORACLE",
                "YKI_SKYHUNTER",
                "YKI_MEADOW_MENDER",
                "YKI_FRONTIER_SCOUT",
                "YKI_COALFIRE_BRAWLER",
                "YKI_DEEPCAVE_PROSPECTOR",
                "YKI_FROSTWOOD_HUNTSMAN",
                "YKI_RUNEGLASS_SAVANT",
                "YKI_DAWN_CHANTER",
                "YKI_RIFT_SCHOLAR",
                "YKI_PHOENIX_HIEROPHANT",
                "YKI_TEMPEST_FALCONER",
                "YKI_SUNCROWN_DUELIST",
                "YKI_REALMSEEKER",
                "YKI_OSSUARY_DRAGOON",
                "YKI_ENDSUN_GUARD",
                "YKI_ENDWITCH_REBEL"
            )
            val expectedArmorIds = armorSetPrefixes.flatMap { prefix ->
                armorPieceSuffixes.map { suffix -> "$prefix$suffix" }
            }.toSet()
            if (armorIds != expectedArmorIds) {
                throw GradleException("护甲必须完整覆盖 33 个规定套装前缀的四个部位（每部位 33 件）")
            }
            val armorCategories = armorIds.associateWith { itemId ->
                when {
                    itemId.endsWith("_HELMET") -> "头盔"
                    itemId.endsWith("_CHESTPLATE") -> "胸甲"
                    itemId.endsWith("_LEGGINGS") -> "护腿"
                    itemId.endsWith("_BOOTS") -> "靴子"
                    else -> throw GradleException("无法根据物品 ID 判断护甲类别：$itemId")
                }
            }
            val typedRarityLine = Regex("^&[8a956d](?:普通|少见的|稀有|史诗|传说|神话)(?: .+)?\\z")

            fun validateCategoryLore(items: Map<*, *>, categories: Map<String, String>, kind: String) {
                for ((rawItemId, rawDefinition) in items) {
                    val itemId = rawItemId.toString()
                    val definition = rawDefinition as? Map<*, *>
                        ?: throw GradleException("$itemId 的 MythicMobs 定义必须是映射")
                    val lore = definition["Lore"] as? List<*>
                        ?: throw GradleException("$itemId 缺少 Lore")
                    val loreLines = lore.map { it?.toString() ?: "" }
                    val metadata = catalogMetadata[itemId] as? Map<*, *>
                        ?: throw GradleException("$itemId 缺少 catalog-metadata")
                    val rarity = metadata["rarity"]?.toString()
                    val rarityLabel = rarityLabels[rarity]
                        ?: throw GradleException("$itemId 使用了未知稀有度：$rarity")
                    val category = categories[itemId]
                        ?: throw GradleException("$itemId 缺少${kind}类别")
                    val expected = "$rarityLabel $category"
                    val rarityLines = loreLines.filter(typedRarityLine::matches)
                    if (rarityLines.size != 1 || loreLines.lastOrNull() != expected) {
                        throw GradleException("$itemId 的 Lore 必须且只能以 '$expected' 作为最后一行")
                    }
                }
            }

            validateCategoryLore(
                parsedFiles.getValue("YknRpg_Weapons.yml"), weaponCategories, "武器")
            validateCategoryLore(
                parsedFiles.getValue("YknRpg_Armor.yml"), armorCategories, "护甲")

            val rpgItems = rpg["items"] as? Map<*, *>
                ?: throw GradleException("rpg.yml 缺少 items")
            val rpgSets = rpg["sets"] as? Map<*, *>
                ?: throw GradleException("rpg.yml 缺少 sets")
            val rpgConfig = config["rpg"] as? Map<*, *>
                ?: throw GradleException("config.yml 缺少 rpg")
            val healthConfig = rpgConfig["health"] as? Map<*, *>
                ?: throw GradleException("config.yml 缺少 rpg.health")
            val equipmentHealthScale = healthConfig["equipment-value-scale"] as? Number
                ?: throw GradleException("config.yml 的 rpg.health.equipment-value-scale 必须是数值")
            if (!equipmentHealthScale.toDouble().isFinite()
                || equipmentHealthScale.toDouble() != 1.0) {
                throw GradleException("config.yml 的 rpg.health.equipment-value-scale 必须为 1.0")
            }
            val effectHealthScale = healthConfig["value-scale"] as? Number
                ?: throw GradleException("config.yml 的 rpg.health.value-scale 必须是数值")
            if (!effectHealthScale.toDouble().isFinite() || effectHealthScale.toDouble() <= 0.0) {
                throw GradleException("config.yml 的 rpg.health.value-scale 必须为有限正数")
            }
            val shieldConfig = rpgConfig["shield"] as? Map<*, *>
                ?: throw GradleException("config.yml 缺少 rpg.shield")
            val defaultShieldDuration = shieldConfig["default-duration"] as? Number
                ?: throw GradleException("config.yml 的 rpg.shield.default-duration 必须是数值")
            if (!defaultShieldDuration.toDouble().isFinite()
                || defaultShieldDuration.toDouble() <= 0.0) {
                throw GradleException("config.yml 的 rpg.shield.default-duration 必须为有限正数")
            }

            fun validateAbsorptionEffects(owner: String, rawDefinition: Any?) {
                val definition = rawDefinition as? Map<*, *>
                    ?: throw GradleException("$owner 必须是映射")
                val rawAbilities = definition["abilities"] ?: return
                val abilities = rawAbilities as? Map<*, *>
                    ?: throw GradleException("$owner.abilities 必须是映射")
                for ((abilityId, rawAbility) in abilities) {
                    val ability = rawAbility as? Map<*, *>
                        ?: throw GradleException("$owner.$abilityId 必须是映射")
                    val effects = ability["effects"] as? List<*>
                        ?: throw GradleException("$owner.$abilityId.effects 必须是列表")
                    for ((index, rawEffect) in effects.withIndex()) {
                        val effect = rawEffect as? Map<*, *>
                            ?: throw GradleException("$owner.$abilityId effects[$index] 必须是映射")
                        if (effect["type"]?.toString()?.uppercase() != "ABSORPTION") continue
                        for (field in listOf("amount", "cap")) {
                            val value = effect[field] as? Number
                                ?: throw GradleException("$owner.$abilityId effects[$index].$field 必须是数值")
                            if (!value.toDouble().isFinite() || value.toDouble() <= 0.0) {
                                throw GradleException("$owner.$abilityId effects[$index].$field 必须为有限正数")
                            }
                        }
                        if (effect.containsKey("duration")) {
                            val duration = effect["duration"] as? Number
                                ?: throw GradleException("$owner.$abilityId effects[$index].duration 必须是数值")
                            if (!duration.toDouble().isFinite() || duration.toDouble() <= 0.0) {
                                throw GradleException("$owner.$abilityId effects[$index].duration 必须为有限正数")
                            }
                        }
                    }
                }
            }
            rpgItems.forEach { (itemId, definition) ->
                validateAbsorptionEffects("items.$itemId", definition)
            }
            rpgSets.forEach { (setId, definition) ->
                validateAbsorptionEffects("sets.$setId", definition)
            }
            val rawRpgItemCount = Regex("(?m)^  YKI_[A-Z0-9_]+:\\s*$")
                .findAll(file("src/main/resources/rpg.yml").readText()).count()
            if (rawRpgItemCount != rpgItems.size) {
                throw GradleException("rpg.yml items 中存在重复物品 ID")
            }
            if (rpgItems.keys.map { it.toString() }.toSet() != weaponIds + armorIds) {
                throw GradleException("rpg.yml 必须定义全部武器与护甲，且不得把材料设为装备")
            }
            if (rpgSets.size != 33) throw GradleException("完整内容必须包含 33 个护甲套装")
            val configuredSetPieceIds = mutableListOf<String>()
            for ((setId, rawSet) in rpgSets) {
                val set = rawSet as? Map<*, *>
                    ?: throw GradleException("$setId 套装定义必须是映射")
                val pieces = set["pieces"] as? List<*>
                    ?: throw GradleException("$setId 套装缺少 pieces")
                val pieceIds = pieces.map { it.toString() }
                if (pieceIds.size != 4 || pieceIds.toSet().size != 4 || !armorIds.containsAll(pieceIds)) {
                    throw GradleException("$setId 套装必须引用 4 件互不重复的已知护甲")
                }
                val piecePrefixes = pieceIds.mapNotNull { pieceId ->
                    armorSetPrefixes.singleOrNull { prefix -> pieceId.startsWith("${prefix}_") }
                }.toSet()
                if (piecePrefixes.size != 1) {
                    throw GradleException("$setId 套装的 4 件护甲必须使用同一个规定套装前缀")
                }
                configuredSetPieceIds += pieceIds
            }
            if (configuredSetPieceIds.size != expectedArmorIds.size ||
                configuredSetPieceIds.toSet() != expectedArmorIds) {
                throw GradleException("33 个护甲套装必须恰好覆盖全部 132 件护甲且不得重复")
            }
            if (defaultRecipes.keys.map { it.toString() }.toSet() != mythicIds) {
                throw GradleException("default-recipes 必须完整定义全部物品")
            }
            val catalogIndexes = catalogIds.withIndex().associate { (index, itemId) -> itemId to index }
            val referencedMaterialIds = mutableSetOf<String>()
            for ((itemId, configured) in defaultRecipes) {
                val recipe = configured as? List<*>
                    ?: throw GradleException("$itemId 的默认配方必须是列表")
                if (recipe.size != 9) throw GradleException("$itemId 的默认配方必须正好包含 9 格")
                for (ingredient in recipe.map { value -> value.toString() }) {
                    if (!ingredient.startsWith("@")) {
                        if (ingredient !in validMaterials) {
                            throw GradleException("$itemId 的默认配方使用了无效 Paper 材料 $ingredient")
                        }
                        continue
                    }
                    val dependency = ingredient.substring(1)
                    if (dependency !in mythicIds) {
                        throw GradleException("$itemId 的默认配方引用了未知物品 $ingredient")
                    }
                    if (catalogIndexes.getValue(dependency) >= catalogIndexes.getValue(itemId.toString())) {
                        throw GradleException("$itemId 的默认配方依赖必须位于产物之前：$ingredient")
                    }
                    val itemMetadata = catalogMetadata[itemId] as Map<*, *>
                    val dependencyMetadata = catalogMetadata[dependency] as Map<*, *>
                    if (rarityRanks.getValue(dependencyMetadata["rarity"].toString()) >
                        rarityRanks.getValue(itemMetadata["rarity"].toString())) {
                        throw GradleException("$itemId 的默认配方不得依赖更高稀有度物品：$ingredient")
                    }
                    if (dependency in materialIds) referencedMaterialIds += dependency
                }
            }
            val unreferencedMaterialIds = (materialIds - referencedMaterialIds).sorted()
            if (unreferencedMaterialIds.isNotEmpty()) {
                throw GradleException(
                    "每个材料都必须被至少一个位于其后的默认配方以 @ 引用；未引用材料：$unreferencedMaterialIds")
            }

        }
    }
}

tasks.processResources {
    dependsOn(validateYaml)
    filteringCharset = "UTF-8"
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val plainJar = tasks.named<Jar>("jar") {
    archiveBaseName = "YknRpg"
    archiveFileName = "YknRpg-${project.version}-plain.jar"
    destinationDirectory = layout.buildDirectory.dir("intermediates/unobfuscated")
}

val obfuscatedJarFile = layout.buildDirectory.file("libs/YknRpg-${project.version}.jar")
val obfuscationMappingFile = layout.buildDirectory.file(
    "obfuscation/YknRpg-${project.version}-mapping.txt")

val obfuscateJar by tasks.registering(JavaExec::class) {
    group = "build"
    description = "使用 ProGuard 混淆 YknRpg 插件 JAR"
    dependsOn(plainJar)
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    inputs.file(plainJar.flatMap { it.archiveFile })
    inputs.files(proguard)
    outputs.file(obfuscatedJarFile)
    outputs.file(obfuscationMappingFile)

    doFirst {
        val inputJar = plainJar.get().archiveFile.get().asFile
        val outputJar = obfuscatedJarFile.get().asFile
        val mappingFile = obfuscationMappingFile.get().asFile
        val javaBase = file("C:/java/25/jmods/java.base.jmod")
        if (!javaBase.isFile) throw GradleException("JDK 25 java.base.jmod 不存在：$javaBase")
        outputJar.parentFile.mkdirs()
        mappingFile.parentFile.mkdirs()
        setArgs(listOf(
            "-injars", inputJar.absolutePath,
            "-outjars", outputJar.absolutePath,
            "-libraryjars", "${javaBase.absolutePath}(!**.jar;!module-info.class)",
            "-dontshrink",
            "-dontoptimize",
            "-dontwarn",
            "-dontnote",
            "-keepattributes",
            "Exceptions,InnerClasses,Signature,*Annotation*,EnclosingMethod,Record,NestHost,NestMembers,PermittedSubclasses",
            "-keep",
            "public class cn.yknrpg.YknRpgPlugin { public <init>(); public void onEnable(); public void onDisable(); }",
            "-keep", "public class cn.yknrpg.api.** { public *; }",
            "-keep", "public interface cn.yknrpg.api.** { *; }",
            "-keepclassmembers", "class cn.yknrpg.YknRpgPlugin { public cn.yknrpg.api.ForgeItemAccess forgeItems(); }",
            "-keepclassmembers",
            "class * { public protected *; }",
            "-keepclassmembers",
            "enum * { public static **[] values(); public static ** valueOf(java.lang.String); public static final *** *; }",
            "-printmapping", mappingFile.absolutePath
        ))
    }
}

val verifyObfuscatedJar by tasks.registering {
    group = "verification"
    description = "验证发布 JAR 已混淆且保留 Paper 入口与运行时资源"
    dependsOn(obfuscateJar)
    inputs.file(obfuscatedJarFile)
    inputs.file(obfuscationMappingFile)

    doLast {
        val entries = mutableSetOf<String>()
        ZipFile(obfuscatedJarFile.get().asFile).use { archive ->
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) entries += enumeration.nextElement().name
        }
        val requiredEntries = setOf(
            "plugin.yml",
            "config.yml",
            "equipment.yml",
            "recipes.yml",
            "rpg.yml",
            "cn/yknrpg/YknRpgPlugin.class"
        )
        val missing = requiredEntries - entries
        if (missing.isNotEmpty()) throw GradleException("混淆 JAR 缺少必要入口或资源：$missing")

        val unobfuscatedClasses = setOf(
            "cn/yknrpg/gui/EquipmentGui.class",
            "cn/yknrpg/item/MaterialUseGuard.class",
            "cn/yknrpg/integration/AuraSkillsMana.class",
            "cn/yknrpg/integration/EcoPetsItems.class",
            "cn/yknrpg/integration/ProtocolLibInputBridge.class",
            "cn/yknrpg/rpg/RpgEngine.class",
            "cn/yknrpg/rpg/RpgRegistry.class"
        )
        val leakedClasses = unobfuscatedClasses.intersect(entries)
        if (leakedClasses.isNotEmpty()) {
            throw GradleException("发布 JAR 仍包含未混淆的内部类：$leakedClasses")
        }
        if (entries.count { it.endsWith(".class") } < 2) {
            throw GradleException("混淆 JAR 未包含完整插件字节码")
        }
        val mapping = obfuscationMappingFile.get().asFile.readText(Charsets.UTF_8)
        if (!mapping.contains("cn.yknrpg.gui.EquipmentGui ->") ||
            !mapping.contains("cn.yknrpg.rpg.RpgEngine ->")) {
            throw GradleException("ProGuard 映射文件缺少关键内部类")
        }
    }
}

tasks.check {
    dependsOn(verifyObfuscatedJar, validateEcoPets, verifyEcoPetsPack)
}

val generateCraftEngineAssets by tasks.registering(Exec::class) {
    group = "distribution"
    description = "生成 YknRpg 锻造材料与 GUI 资源"
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        layout.projectDirectory.file("tools/generate_pack.ps1").asFile.absolutePath
    )
    inputs.file("tools/generate_pack.ps1")
    outputs.dir("packs/craftengine/resourcepack/assets/yknrpg")
}

val craftEnginePack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 CraftEngine 资源包"
    dependsOn(generateCraftEngineAssets, validateYaml)
    archiveFileName = "YknRpg-CraftEngine-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/craftengine") {
        into("yknrpg")
    }
}

val mythicMobsPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 YknRpg 的 MythicMobs 物品定义"
    dependsOn(validateYaml)
    archiveFileName = "YknRpg-MythicMobs-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/mythicmobs")
}

tasks.assemble {
    dependsOn(obfuscateJar, craftEnginePack, mythicMobsPack, ecoPetsPack)
}
