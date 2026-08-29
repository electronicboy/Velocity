plugins {
    `java-library`
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
