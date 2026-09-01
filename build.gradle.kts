import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

plugins {
    java
}

group = "cn.yknitems"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("dev.aurelium:auraskills-api:2.3.12")
}

val yamlValidator by configurations.creating

dependencies {
    yamlValidator("org.yaml:snakeyaml:2.2")
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

val validateYaml by tasks.registering {
    group = "verification"
    val yamlFiles = files(
        "src/main/resources/config.yml",
        "src/main/resources/equipment.yml",
        "src/main/resources/recipes.yml",
        "packs/craftengine/pack.yml",
        "packs/craftengine/configuration/items.yml"
    )
    inputs.files(yamlFiles)
    doLast {
        val urls = yamlValidator.files.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val yamlClass = loader.loadClass("org.yaml.snakeyaml.Yaml")
            val yaml = yamlClass.getConstructor().newInstance()
            for (file in yamlFiles) {
                try {
                    file.inputStream().use { input ->
                        val parsed = yamlClass.getMethod("load", InputStream::class.java).invoke(yaml, input)
                        if (parsed !is Map<*, *>) throw GradleException("${file.name} 根节点必须是映射")
                    }
                } catch (exception: InvocationTargetException) {
                    throw GradleException("${file.name} YAML 解析失败", exception.targetException)
                }
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

tasks.jar {
    archiveBaseName = "YknItems"
}

val generateCraftEngineAssets by tasks.registering(Exec::class) {
    group = "distribution"
    description = "生成 YknItems 像素武器与 GUI 资源"
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        layout.projectDirectory.file("tools/generate_pack.ps1").asFile.absolutePath
    )
    inputs.file("tools/generate_pack.ps1")
    outputs.dir("packs/craftengine/resourcepack/assets/yknitems")
}

val craftEnginePack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 CraftEngine 资源包"
    dependsOn(generateCraftEngineAssets, validateYaml)
    archiveFileName = "YknItems-CraftEngine-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/craftengine") {
        into("yknitems")
    }
}

tasks.assemble {
    dependsOn(craftEnginePack)
}
