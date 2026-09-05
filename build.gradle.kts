import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.zip.ZipFile

plugins {
    java
}

group = "cn.yknrpg"
version = "1.2"

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

val validateYaml by tasks.registering {
    group = "verification"
    val yamlFiles = files(
        "src/main/resources/config.yml",
        "src/main/resources/equipment.yml",
        "src/main/resources/rpg.yml",
        "src/main/resources/recipes.yml",
        "packs/craftengine/pack.yml",
        "packs/craftengine/configuration/items.yml",
        "packs/mythicmobs/items/YknRpg_Materials.yml",
        "packs/mythicmobs/items/YknRpg_Weapons.yml",
        "packs/mythicmobs/items/YknRpg_Armor.yml",
        ecoPetFiles
    )
    inputs.files(yamlFiles)
    doLast {
        val urls = yamlValidator.files.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val yamlClass = loader.loadClass("org.yaml.snakeyaml.Yaml")
            val yaml = yamlClass.getConstructor().newInstance()
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
            if (materialIds.size != 31 || weaponIds.size != 44 || armorIds.size != 48) {
                throw GradleException("完整内容必须包含 31 个材料、44 把武器和 48 件护甲")
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
            for ((itemId, rawMetadata) in catalogMetadata) {
                val metadata = rawMetadata as? Map<*, *>
                    ?: throw GradleException("$itemId 的 catalog-metadata 必须是映射")
                val kind = metadata["kind"]?.toString()
                val rarity = metadata["rarity"]?.toString()
                if (kind !in validKinds) throw GradleException("$itemId 的图鉴类型无效：$kind")
                if (rarity !in validRarities) throw GradleException("$itemId 的稀有度无效：$rarity")
                val expectedKind = when (itemId.toString()) {
                    in materialIds -> "ITEM"
                    in weaponIds -> "WEAPON"
                    else -> "ARMOR"
                }
                if (kind != expectedKind) throw GradleException("$itemId 必须归类为 $expectedKind")
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
                "YKI_CITADEL_STANDARD" to "战旗"
            )
            if (weaponCategories.keys != weaponIds) {
                throw GradleException("武器类别映射必须完整覆盖 44 把武器")
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
            if (rpgSets.size != 12) throw GradleException("完整内容必须包含 12 个护甲套装")
            for ((setId, rawSet) in rpgSets) {
                val set = rawSet as? Map<*, *>
                    ?: throw GradleException("$setId 套装定义必须是映射")
                val pieces = set["pieces"] as? List<*>
                    ?: throw GradleException("$setId 套装缺少 pieces")
                val pieceIds = pieces.map { it.toString() }
                if (pieceIds.size != 4 || pieceIds.toSet().size != 4 || !armorIds.containsAll(pieceIds)) {
                    throw GradleException("$setId 套装必须引用 4 件互不重复的已知护甲")
                }
            }
            if (defaultRecipes.keys.map { it.toString() }.toSet() != mythicIds) {
                throw GradleException("default-recipes 必须完整定义全部物品")
            }
            for ((itemId, configured) in defaultRecipes) {
                val recipe = configured as? List<*>
                    ?: throw GradleException("$itemId 的默认配方必须是列表")
                if (recipe.size != 9) throw GradleException("$itemId 的默认配方必须正好包含 9 格")
                for (ingredient in recipe.map { value -> value.toString() }.filter { it.startsWith("@") }) {
                    if (ingredient.substring(1) !in mythicIds) {
                        throw GradleException("$itemId 的默认配方引用了未知物品 $ingredient")
                    }
                }
            }

            val petFiles = ecoPetFiles.files.sortedBy { it.name }
            if (petFiles.size != 36) {
                throw GradleException("EcoPets 内容必须正好包含 36 个 pets/yknrpg/*.yml 文件")
            }
            val petIds = petFiles.map { it.nameWithoutExtension }
            if (petIds.any { !Regex("[a-z0-9_]+").matches(it) }) {
                throw GradleException("EcoPets 宠物 ID 必须使用小写字母、数字和下划线")
            }
            if (petIds.size != petIds.toSet().size) {
                throw GradleException("EcoPets 宠物 ID 不得重复")
            }

            val auraSkillsStats = setOf(
                "strength", "health", "regeneration", "luck", "wisdom",
                "toughness", "crit_chance", "crit_damage", "speed"
            )
            val petXpTriggers = setOf(
                "mine_block", "catch_fish", "kill", "move", "brew", "enchant_item",
                "bow_attack", "melee_attack", "shield_block", "breed", "tame_animal"
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
            // Lv.100 单项上限按 YknRpg 同阶段武器的真实结算收益制定。
            // AuraSkills health 1 点等价 20 点 YknRpg health，不能按面板原值直接比较。
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

            fun collectStrings(value: Any?, output: MutableList<String>) {
                when (value) {
                    is String -> output += value
                    is Map<*, *> -> value.values.forEach { collectStrings(it, output) }
                    is Iterable<*> -> value.forEach { collectStrings(it, output) }
                }
            }

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
                val xpGainMethods = pet["xp-gain-methods"] as? List<*>
                    ?: throw GradleException("$petId 缺少 xp-gain-methods 列表")
                if (xpGainMethods.isEmpty()) throw GradleException("$petId 必须至少定义一种经验来源")
                for ((index, rawMethod) in xpGainMethods.withIndex()) {
                    val method = rawMethod as? Map<*, *>
                        ?: throw GradleException("$petId 的 xp-gain-methods[$index] 必须是映射")
                    val trigger = method["id"]?.toString()
                    if (trigger == null || trigger !in petXpTriggers) {
                        throw GradleException("$petId 使用了未经验证的经验触发器：$trigger")
                    }
                    val hasValue = method["value"] is Number
                    val hasMultiplier = method["multiplier"] is Number
                    if (hasValue == hasMultiplier) {
                        throw GradleException("$petId 的 xp-gain-methods[$index] 必须且只能配置数值 value 或 multiplier")
                    }
                }
                val spawnEgg = pet["spawn-egg"] as? Map<*, *>
                    ?: throw GradleException("$petId 缺少 spawn-egg")
                if (spawnEgg["enabled"] != true || (spawnEgg["item"] as? String).isNullOrBlank()) {
                    throw GradleException("$petId 必须启用并配置 spawn-egg")
                }
                val placeholders = pet["level-placeholders"] as? List<*>
                    ?: throw GradleException("$petId 缺少 level-placeholders")
                val placeholderValues = placeholders.associate { rawPlaceholder ->
                    val placeholder = rawPlaceholder as? Map<*, *>
                        ?: throw GradleException("$petId 的 level-placeholders 必须是映射列表")
                    val id = placeholder["id"]?.toString()
                        ?: throw GradleException("$petId 的 level-placeholders 缺少 id")
                    val value = placeholder["value"]?.toString()
                        ?: throw GradleException("$petId 的 $id 缺少 value")
                    id to value
                }
                if (placeholderValues.size != placeholders.size) {
                    throw GradleException("$petId 的 level-placeholders 不得包含重复 id")
                }

                val effects = pet["effects"] as? List<*>
                    ?: throw GradleException("$petId 缺少 effects 列表")
                if (effects.isEmpty()) throw GradleException("$petId 必须至少定义一个 add_stat 效果")
                val petStats = mutableSetOf<String>()
                val petStatValues = mutableMapOf<String, Double>()
                for ((index, rawEffect) in effects.withIndex()) {
                    val effect = rawEffect as? Map<*, *>
                        ?: throw GradleException("$petId 的 effects[$index] 必须是映射")
                    if (effect["id"]?.toString() != "add_stat") {
                        throw GradleException("$petId 的 effects 只能使用 AuraSkills add_stat")
                    }
                    val args = effect["args"] as? Map<*, *>
                        ?: throw GradleException("$petId 的 effects[$index] 缺少 args")
                    val stat = args["stat"]?.toString()
                    if (stat == null || stat !in auraSkillsStats) {
                        throw GradleException("$petId 使用了无效 AuraSkills 属性：$stat")
                    }
                    if (!petStats.add(stat)) {
                        throw GradleException("$petId 重复定义了 AuraSkills 属性：$stat")
                    }
                    val amount = args["amount"]?.toString()
                    val amountMatch = amount?.let(levelAmount::matchEntire)
                    if (amountMatch == null) {
                        throw GradleException("$petId 的 effects[$index] amount 必须是 %level% 的正数线性公式")
                    }
                    val level100Value = amountMatch.groupValues[1].toDouble() * 100.0
                    if (!level100Value.isFinite() || level100Value <= 0.0) {
                        throw GradleException("$petId 的 effects[$index] 满级数值必须为有限正数")
                    }
                    petStatValues[stat] = level100Value
                    if (placeholderValues["${stat}_bonus"] != amount) {
                        throw GradleException("$petId 的 ${stat}_bonus 必须与 add_stat amount 完全一致")
                    }
                    if (effect.containsKey("triggers")) {
                        throw GradleException("$petId 的永久 add_stat 效果不得配置 triggers")
                    }
                }
                val expectedPlaceholderIds = petStats.map { "${it}_bonus" }.toSet()
                if (placeholderValues.keys != expectedPlaceholderIds) {
                    throw GradleException("$petId 的 level-placeholders 必须与 AuraSkills 属性一一对应")
                }

                val allStrings = mutableListOf<String>()
                collectStrings(pet, allStrings)
                val petRarities = allStrings.filter { it in rarityLines }
                if (petRarities.size != 1) {
                    throw GradleException("$petId 必须且只能包含一个规定格式的稀有度宠物尾行")
                }
                val petRarity = petRarities.single()
                rarityCounts[petRarity] = rarityCounts.getValue(petRarity) + 1
                if (petStats.size != petStatCounts.getValue(petRarity)) {
                    throw GradleException("$petId 的属性数量不符合 $petRarity 档位")
                }
                val statCaps = petStatCaps.getValue(petRarity)
                var normalizedBudget = 0.0
                for ((stat, value) in petStatValues) {
                    val cap = statCaps.getValue(stat)
                    if (value > cap + 0.000001) {
                        throw GradleException("$petId 的 $stat 满级值 $value 超过 $petRarity 上限 $cap")
                    }
                    normalizedBudget += value / cap
                }
                val budgetFloor = petBudgetFloors.getValue(petRarity)
                val budgetCap = petBudgetCaps.getValue(petRarity)
                if (normalizedBudget < budgetFloor - 0.000001
                    || normalizedBudget > budgetCap + 0.000001) {
                    throw GradleException(
                        "$petId 的标准化属性预算 $normalizedBudget 不在 $petRarity 档位 $budgetFloor..$budgetCap 内")
                }
                val descriptions = pet["effects-description"] as? Map<*, *>
                    ?: throw GradleException("$petId 缺少 effects-description")
                var descriptionRarityCount = 0
                for ((level, rawLines) in descriptions) {
                    val lines = rawLines as? List<*>
                        ?: throw GradleException("$petId 的 effects-description.$level 必须是列表")
                    val textLines = lines.map { it?.toString() ?: "" }
                    val rarityIndexes = textLines.indices.filter { textLines[it] in rarityLines }
                    descriptionRarityCount += rarityIndexes.size
                    if (rarityIndexes.any { it != textLines.lastIndex }) {
                        throw GradleException("$petId 的稀有度宠物文本必须是 effects-description 等级段的最后一行")
                    }
                }
                if (descriptionRarityCount != 1) {
                    throw GradleException("$petId 的唯一稀有度宠物尾行必须位于 effects-description")
                }
            }
            if (rarityCounts.any { it.value != 6 }) {
                throw GradleException("EcoPets 六档稀有度必须各包含 6 只宠物：$rarityCounts")
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
    dependsOn(verifyObfuscatedJar)
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

val ecoPetsPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 YknRpg 的 EcoPets 宠物定义"
    dependsOn(validateYaml)
    archiveFileName = "YknRpg-EcoPets-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/ecopets")
}

tasks.assemble {
    dependsOn(obfuscateJar, craftEnginePack, mythicMobsPack, ecoPetsPack)
}
