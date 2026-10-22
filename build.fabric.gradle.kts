plugins {
    // Applies the correct loom variant based on the Minecraft version.
    id("dev.kikugie.loom-back-compat")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/") { name = "Fuzs Mod Resources" }
    maven("https://maven.daqem.com/releases") { name = "DAQEM" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
}


dependencies {
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi(
        "fabric-lifecycle-events-v1",
        "fabric-resource-loader-v0",
        "fabric-command-api-v2",
        "fabric-networking-api-v1"
    )

    // Configuration library shared with the Forge/NeoForge nodes.
    modImplementation("fuzs.forgeconfigapiport:forgeconfigapiport-fabric:${sc.properties.get<String>("deps.fcap")}")

    // Pure Java storage engine dependency: on the classpath in dev, jar-in-jar in production.
    implementation("com.github.luben:zstd-jni:${property("deps.zstd")}")
    include("com.github.luben:zstd-jni:${property("deps.zstd")}")

    // Optional client UI. Loom remaps both jars from intermediary for compile and dev runtime;
    // production users install them separately, so nothing is embedded.
    val uilibVersion = sc.properties.get<String>("deps.uilib")
    val architecturyVersion = sc.properties.get<String>("deps.architectury")
    modCompileOnly("maven.modrinth:ui-lib:$uilibVersion")
    modCompileOnly("dev.architectury:architectury-fabric:$architecturyVersion")
    modRuntimeOnly("maven.modrinth:ui-lib:$uilibVersion")
    modRuntimeOnly("dev.architectury:architectury-fabric:$architecturyVersion")
}

// run/mods is shared by every node, so drop jars left behind by another loader/version.
val cleanUiMods = tasks.register("cleanUiMods") {
    doFirst {
        rootProject.layout.projectDirectory.dir("run/mods").asFile
            .listFiles { file ->
                file.name.startsWith("uilib-") || file.name.startsWith("ui-lib-")
                    || file.name.startsWith("architectury-")
            }
            ?.forEach { it.delete() }
    }
}

tasks.matching { it.name.startsWith("run") || it.name.startsWith("prepare") }.configureEach {
    dependsOn(cleanUiMods)
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
    accessWidenerPath = sc.process(
        rootProject.file("src/main/resources/minimalbackup.ct"),
        "build/processed.ct"
    )

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1")
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run")
        jvmArguments.add("-Dmixin.debug.export=true")
        // Loom picks a random dev name (Player123), which cannot be pre-opped. Forge and NeoForge
        // both default to "Dev"; use the same name so every node's dev client is an operator.
        // Only the client takes --username; the dedicated server rejects it.
        if (name == "client") {
            programArgs("--username", "Dev")
        }
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, value: String) {
            inputs.property(key, value)
            set(key, value)
        }

        filteringCharset = "UTF-8"
        val props = buildMap {
            register("id", sc.properties["mod.id"])
            register("name", sc.properties["mod.name"])
            register("version", sc.properties["mod.version"])
            register("minecraft", sc.properties["mod.mc_compat"])
            register("loader", Regex("\\d+\\.\\d+").find(sc.properties.get<String>("deps.fabric_loader"))!!.value)
            register("license", sc.properties["mod.license"])
            register("authors", sc.properties["mod.authors"])
            register("description", sc.properties["mod.description"])
            register("fcap", sc.properties["deps.fcap"])
            register("mixin_config", sc.properties["mixin_config"])
            register("mixin_config_extra", sc.properties["mixin_config_extra"])
            register("uilib_range", sc.properties["mod.uilib_range"])
            register("architectury_range", sc.properties["mod.architectury_range"])
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
        // The accessor mixin targets a class that only exists since 1.20.2; older nodes point at
        // the empty config instead and must not ship a config that lists a missing mixin.
        if (sc.current.parsed < "1.20.2") {
            exclude("minimalbackup.fabric.mixins.json")
        }
    }

    withType<Jar> {
        val name = project.property("mod.id")
        inputs.property("mod_id", name)
        from("../../LICENSE") { rename { "$it-$name" } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to build/libs/{mod version}/"
        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

// The engine and hook suites run outside Gradle (build.sh / tests/test-hooks.sh). Stonecutter shares the
// root test tree with every node, which has no JUnit tests, so the default Test task would fail.
tasks.matching { it.name == "test" }.configureEach { enabled = false }
