plugins {
    id("net.neoforged.moddev") version "2.0.147"
    id("neoforge-mutex")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-neoforge"

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    maven("https://maven.daqem.com/releases") { name = "DAQEM" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
    mavenCentral()
}

val uiRuntime = configurations.create("uiRuntime") {
    // Only the two mod jars: their POM dependencies (Fabric API, loader, ...) are already on the
    // dev classpath or installed by the user in production.
    isTransitive = false
}

dependencies {
    // Pure Java storage engine dependency. compile + dev classpath + FML legacy classpath
    // (NeoForge <= 1.21.8 does not see plain classpath entries from mod code) + jar-in-jar.
    implementation("com.github.luben:zstd-jni:${property("deps.zstd")}")
    "jarJar"("com.github.luben:zstd-jni:${property("deps.zstd")}")

    // Optional client UI (not embedded): compile against the 1.21.1 UILib build.
    val uilibVersion = sc.properties.get<String>("deps.uilib")
    val architecturyVersion = sc.properties.get<String>("deps.architectury")
    compileOnly("com.daqem.uilib:uilib-neoforge:$uilibVersion:neoforge")
    compileOnly("dev.architectury:architectury-neoforge:$architecturyVersion")
    uiRuntime("com.daqem.uilib:uilib-neoforge:$uilibVersion:neoforge")
    uiRuntime("dev.architectury:architectury-neoforge:$architecturyVersion")
}

// Dev runs need UILib and Architectury in run/mods; production users install them separately.
// Every node shares run/mods, so drop the previous node's UILib/Architectury jars before copying.
val uiMods = tasks.register<Copy>("uiMods") {
    from(uiRuntime)
    into(rootProject.layout.projectDirectory.dir("run/mods"))
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
    dependsOn(uiMods)
}

neoForge {
    version = property("deps.neo_loader") as String

    mods {
        register("minimalbackup") {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            gameDirectory = file("../../run/")
            client()
        }
        register("server") {
            gameDirectory = file("../../run/")
            server()
        }
    }
}

// FML only shows mod code the "legacy classpath" on NeoForge <= 1.21.8; the configuration
// exists once neoForge.version is set, so the dependency has to be added after the block above.
dependencies {
    "additionalRuntimeClasspath"("com.github.luben:zstd-jni:${property("deps.zstd")}")
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
            register("license", sc.properties["mod.license"])
            register("authors", sc.properties["mod.authors"])
            register("description", sc.properties["mod.description"])
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        exclude("fabric.mod.json", "*.ct", "*.classtweaker", "META-INF/mods.toml")
        exclude("minimalbackup.fabric.mixins.json", "minimalbackup.empty.mixins.json")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
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
        from(jar.flatMap { it.archiveFile }, named<Jar>("sourcesJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

// The engine and hook suites run outside Gradle (build.sh / tests/test-hooks.sh). Stonecutter shares the
// root test tree with every node, which has no JUnit tests, so the default Test task would fail.
tasks.matching { it.name == "test" }.configureEach { enabled = false }
