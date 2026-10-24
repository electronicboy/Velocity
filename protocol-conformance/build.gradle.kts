plugins {
    `java-library`
}

// The main source set is the live harness: a scripted client, a tap relay, a vanilla server runner
// and a differ. It deliberately has no dependency on the proxy, so that a Velocity bug can never
// validate itself, and so that the same harness can be pointed at any proxy jar (Waterfall
// included). Its unit tests are offline and run as part of `check`.
dependencies {
    implementation(rootProject.libs.gson)
    implementation(rootProject.libs.nightconfig)
}

// Conformance checks live in their own source set so that they are never pulled into `check` or
// `build`. They pull vanilla server jars from Mojang and run the data generator over each, so they
// are driven from a scheduled workflow rather than the build.
val conformance: SourceSet by sourceSets.creating

configurations[conformance.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())

dependencies {
    conformance.implementationConfigurationName(project(":velocity-api"))
    conformance.implementationConfigurationName(project(":velocity-proxy"))
    conformance.implementationConfigurationName(rootProject.libs.junit)
    conformance.implementationConfigurationName(rootProject.libs.gson)
    conformance.runtimeOnlyConfigurationName("org.junit.platform:junit-platform-launcher")
}

// Nothing extracted from Mojang is committed; it all lands under build/.
val manifestIndex = layout.buildDirectory.file("vanilla-manifest.json")
val extractedData = layout.buildDirectory.dir("vanilla-data")

// Server jars are keyed by the sha1 Mojang publishes, so this survives an index change without
// re-downloading everything. Roughly a gigabyte when full.
val jarCache = layout.buildDirectory.dir("vanilla-jars")

// The upstream probe: cheap, and deliberately never up-to-date, since noticing that Mojang has
// published something new is the entire point of the scheduled run.
val resolveVanillaManifest = tasks.register<JavaExec>("resolveVanillaManifest") {
    description = "Records the server jar Mojang currently publishes for each supported version."
    group = "verification"
    classpath = conformance.runtimeClasspath
    mainClass = "com.velocitypowered.proxy.conformance.VanillaDataGenerator"
    args("resolve", manifestIndex.get().asFile.absolutePath)
    outputs.file(manifestIndex)
    outputs.upToDateWhen { false }
}

// The expensive half. Given the resolved sha1s and this code, the output is fully determined, so
// it is safe to cache: an unchanged upstream turns a four minute run into a cache hit.
val extractVanillaData = tasks.register<JavaExec>("extractVanillaData") {
    description = "Runs Mojang's data generator and extracts the registries Velocity validates against."
    group = "verification"
    classpath = conformance.runtimeClasspath
    mainClass = "com.velocitypowered.proxy.conformance.VanillaDataGenerator"
    args("extract", manifestIndex.get().asFile.absolutePath,
        jarCache.get().asFile.absolutePath, extractedData.get().asFile.absolutePath)

    inputs.file(resolveVanillaManifest.map { manifestIndex })
        .withPropertyName("manifestIndex")
        .withPathSensitivity(PathSensitivity.NONE)
    // Only the extractor's own classes, not the whole runtime classpath: extraction reads the
    // resolved index and nothing else, so a change elsewhere in the proxy must not invalidate it.
    inputs.files(conformance.output.classesDirs)
        .withPropertyName("extractor")
        .withNormalizer(ClasspathNormalizer::class)
    outputs.dir(extractedData).withPropertyName("extractedData")
    // The jar cache is an accelerator, not an output; leaving it undeclared keeps it out of the
    // cache entry, which would otherwise be a gigabyte of server jars.
    outputs.cacheIf { true }
}

tasks.register<Test>("conformanceTest") {
    description = "Validates Velocity's protocol registries against data extracted from Mojang's data generator."
    group = "verification"
    inputs.dir(extractVanillaData.map { extractedData })
        .withPropertyName("extractedData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testClassesDirs = conformance.output.classesDirs
    classpath = conformance.runtimeClasspath
    useJUnitPlatform()
    reports.junitXml.required = true
    systemProperty("velocity.conformance.data", extractedData.get().asFile.absolutePath)
}

// The live suite. Vanilla servers run on the smallest JDK at least as new as the version asks for,
// so every JDK a supported version might need is resolved through toolchains and handed over.
val harnessJdks = listOf(17, 21, 25).map { major ->
    javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(major) }
}

// One representative Minecraft version per protocol Velocity supports, computed from
// ProtocolVersion so a new constant is picked up without touching the build.
val listSupportedVersions = tasks.register<JavaExec>("listSupportedVersions") {
    description = "Writes the Minecraft versions the vanilla conformance suite covers by default."
    group = "verification"
    classpath = conformance.runtimeClasspath
    mainClass = "com.velocitypowered.proxy.conformance.ListSupportedVersions"
    val output = layout.buildDirectory.file("supported-versions.txt")
    args(output.get().asFile.absolutePath)
    outputs.file(output)
}

tasks.register<JavaExec>("vanillaConformance") {
    description = "Runs the vanilla transparency suite against the proxy built from this tree."
    group = "verification"
    val proxyJar = project(":velocity-proxy").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    dependsOn(listSupportedVersions)
    inputs.file(proxyJar)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.velocitypowered.proxy.conformance.harness.Main"
    javaLauncher = harnessJdks.last()
    // Never up to date: the whole point is to notice Mojang, or the proxy, moving.
    outputs.upToDateWhen { false }

    val work = layout.buildDirectory.dir("conformance-work")
    val report = layout.buildDirectory.dir("conformance-report")
    val versions = providers.gradleProperty("conformance.versions")
        .orElse(listSupportedVersions.map { "@" + it.outputs.files.singleFile.absolutePath })
    argumentProviders.add(CommandLineArgumentProvider {
        val args = mutableListOf(
            "--proxy-jar", proxyJar.get().asFile.absolutePath,
            "--proxy-kind", "velocity",
            "--versions", versions.get(),
            "--work", work.get().asFile.absolutePath,
            "--report", report.get().asFile.absolutePath,
            "--ledger", file("ledger.toml").absolutePath,
            "--cache", jarCache.get().asFile.absolutePath,
            "--jdks", harnessJdks.joinToString(File.pathSeparator) {
                it.get().metadata.installationPath.asFile.absolutePath
            },
        )
        if (providers.gradleProperty("conformance.gate").isPresent) {
            args += "--gate"
        }
        args
    })
}

// A self-contained harness jar, for running the suite against a proxy built elsewhere (Waterfall).
tasks.register<Jar>("harnessJar") {
    description = "Assembles a standalone, runnable jar of the vanilla conformance harness."
    group = "build"
    archiveClassifier = "harness"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "com.velocitypowered.proxy.conformance.harness.Main"
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
