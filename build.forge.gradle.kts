plugins {
    id("java-library")
    // Must match the MDG version used by build.neoforge.gradle.kts so IDEA sync
    // loads a single IdeaExtPlugin class.
    id("net.neoforged.moddev.legacyforge") version "2.0.147"
}

fun prop(key: String): String = sc.properties[key]

version = "${prop("mod.version")}+${sc.current.version}"
base.archivesName = "${prop("mod.id") as String}-forge"

java {
    withSourcesJar()
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

legacyForge {
    version = "${prop("deps.forge_mc")}-${prop("deps.forge")}"
    validateAccessTransformers = true

    runs {
        register("client") {
            client()
            gameDirectory = file("../../run/")
        }
        register("server") {
            server()
            programArgument("--nogui")
            gameDirectory = file("../../run/")
        }
    }

    mods {
        register(prop("mod.id") as String) {
            sourceSet(sourceSets["main"])
        }
    }
}

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    maven("https://maven.architectury.dev/") { name = "Architectury" }
    mavenCentral()
}

dependencies {
    // Optional client UI. MDG remaps the SRG jars for compile and dev runtime; production users
    // install them separately, so nothing is embedded.
    modCompileOnly("maven.modrinth:ui-lib:${prop("deps.uilib")}")
    modCompileOnly("dev.architectury:architectury-forge:${prop("deps.architectury")}")
    modRuntimeOnly("maven.modrinth:ui-lib:${prop("deps.uilib")}")
    modRuntimeOnly("dev.architectury:architectury-forge:${prop("deps.architectury")}")

    compileOnly("com.github.luben:zstd-jni:${prop("deps.zstd")}")
    // The per-run additional runtime classpath is merged into the dev legacy classpath, which is
    // the only place mod code can see plain library jars.
    "serverAdditionalRuntimeClasspath"("com.github.luben:zstd-jni:${prop("deps.zstd")}")
    "clientAdditionalRuntimeClasspath"("com.github.luben:zstd-jni:${prop("deps.zstd")}")
    add("jarJar", "com.github.luben:zstd-jni:${prop("deps.zstd")}")

    annotationProcessor("org.spongepowered:mixin:${prop("deps.mixin")}")
    annotationProcessor("com.google.code.gson:gson:2.10.1")
    annotationProcessor("com.google.guava:guava:31.1-jre")
    annotationProcessor("org.ow2.asm:asm:9.7")
    annotationProcessor("org.ow2.asm:asm-tree:9.7")
    annotationProcessor("org.ow2.asm:asm-commons:9.7")
    annotationProcessor("org.ow2.asm:asm-util:9.7")
    annotationProcessor("org.ow2.asm:asm-analysis:9.7")
}

mixin {
    config("minimalbackup.mixins.json")
    add(sourceSets["main"], "minimalbackup.refmap.json")
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

tasks {
    processResources {
        filteringCharset = "UTF-8"
        val replaceProperties = mapOf(
            "minecraft_version" to sc.current.version,
            "forge_version" to prop("deps.forge"),
            "forge_version_range" to prop("deps.forge_version_range"),
            "loader_version_range" to prop("deps.forge_loader_range"),
            "mod_id" to prop("mod.id"),
            "mod_name" to prop("mod.name"),
            "mod_license" to prop("mod.license"),
            "mod_version" to prop("mod.version"),
            "mod_authors" to prop("mod.authors"),
            "mod_description" to prop("mod.description"),
            "uilib_range" to prop("mod.uilib_range"),
            "architectury_range" to prop("mod.architectury_range")
        )
        inputs.properties(replaceProperties)
        filesMatching("META-INF/mods.toml") { expand(replaceProperties) }
        val mixinJava = "JAVA_17"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
        // Forge runs with SRG method/field names, so the mixin processor's refmap has to be
        // named in the config or Mixin falls back to literal names and every @Inject misses.
        doLast {
            val config = layout.buildDirectory
                .file("resources/main/minimalbackup.mixins.json").get().asFile
            if (config.isFile) {
                config.writeText(config.readText().replaceFirst(
                    "\"required\": true,",
                    "\"required\": true,\n  \"refmap\": \"minimalbackup.refmap.json\","
                ))
            }
        }
        exclude("fabric.mod.json", "META-INF/neoforge.mods.toml", "*.ct")
        exclude("minimalbackup.fabric.mixins.json", "minimalbackup.empty.mixins.json")
    }

    named<Jar>("jar") {
        manifest {
            attributes("MixinConfigs" to "minimalbackup.mixins.json")
        }
        val name = prop("mod.id")
        inputs.property("mod_id", name)
        from("../../LICENSE") { rename { "$it-$name" } }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to build/libs/{mod version}/"
        inputs.property("version", prop("mod.version"))
        from(named("reobfJar").flatMap { (it as Jar).archiveFile }, named("sourcesJar").flatMap { (it as Jar).archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${prop("mod.version")}"))
    }
}

// The engine and hook suites run outside Gradle (build.sh / tests/test-hooks.sh). Stonecutter shares the
// root test tree with every node, which has no JUnit tests, so the default Test task would fail.
tasks.matching { it.name == "test" }.configureEach { enabled = false }
