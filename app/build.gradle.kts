import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val baseVersionName = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION"))
    .asText.get().removeSuffix("\n").removeSuffix("\r")
val localVersionCode = run {
    val format = Regex("""(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})""")
    val (major, minor, patch) = format.matchEntire(baseVersionName)?.destructured
        ?: error("VERSION must be MAJOR.MINOR.PATCH without leading zeros; minor and patch must be 0..999")
    val code = major.toLong() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
    require(code in 1L..2_100_000_000L) { "VERSION must map to a versionCode in 1..2100000000" }
    code.toInt()
}
val releaseVersionName = providers.environmentVariable("RELEASE_VERSION_NAME").orNull
val releaseVersionCode = providers.environmentVariable("RELEASE_VERSION_CODE").orNull
require((releaseVersionName == null) == (releaseVersionCode == null)) {
    "Set RELEASE_VERSION_NAME and RELEASE_VERSION_CODE together"
}
val appVersionName = releaseVersionName?.also { name ->
    require(localVersionCode > 2_005_003) { "New release tags must be above 2.5.3" }
    require(name == baseVersionName || Regex("""${Regex.escape(baseVersionName)}-(preview|edge)\.[1-9][0-9]{0,8}""").matches(name)) {
        "RELEASE_VERSION_NAME must match VERSION, optionally followed by -preview.N or -edge.N"
    }
} ?: baseVersionName
val appVersionCode = releaseVersionCode?.let { raw ->
    val code = raw.toIntOrNull()
    require(code != null && code in 3_000_001..2_100_000_000 && raw == code.toString()) {
        "RELEASE_VERSION_CODE must be a canonical decimal in 3000001..2100000000"
    }
    code
} ?: localVersionCode

val noticeAssets = layout.buildDirectory.dir("generated/notice-assets")
val copyNotices by tasks.registering(Sync::class) {
    into(noticeAssets.map { it.dir("notices") })
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    from(rootProject.file("licenses")) { into("third-party") }
}
tasks.named("preBuild") { dependsOn(copyNotices) }

android {
    namespace = "io.github.yhzion.velo"
    // Compose 1.13 (BOM 2026.11.01) needs android-37.1 to compile against; targetSdk stays 37.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "io.github.yhzion.velo"
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "io.github.yhzion.velo.ui.TerminalEditorInstrumentation"
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += "arm64-v8a" }
    }
    val storePath = providers.environmentVariable("RELEASE_STORE_FILE").orNull
    val storePass = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
    val alias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
    val keyPass = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
    val credentials = listOf(storePath, storePass, alias, keyPass)
    val releaseSigning = if (credentials.all { it == null }) null else {
        require(credentials.none { it.isNullOrBlank() }) {
            "Release signing requires RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD together"
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file(requireNotNull(storePath))
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
        }
    }
    buildTypes {
        // x86_64 lets debug builds run on an x86_64 emulator.
        debug { ndk { abiFilters += "x86_64" } }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    // Robolectric reads the merged resources; the fidelity capture tests (docs/fidelity.md) need them.
    testOptions { unitTests { isIncludeAndroidResources = true } }
    sourceSets["main"].assets.directories.add(noticeAssets.get().asFile.path)
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("zig/jniLibs").get().asFile.path)
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("tailscale/jniLibs").get().asFile.path)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Applies the Baseline Profiles (app/src/main/baseline-prof.txt and the libraries' own) to an
    // APK installed outside Play, which is how Velo is installed. Other libraries bring it in only
    // by the way.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    // Fidelity measurement (docs/fidelity.md): test classpath only, never in a release build.
    testImplementation(platform("androidx.compose:compose-bom:2026.11.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
}

// The fidelity capture (FidelityCaptureTest) draws every app scene at each redesign board's size,
// which takes minutes, so it runs only when asked: -Pvelo.fidelity.out=<dir> (tools/fidelity/fidelity.py).
val fidelityOut = providers.gradleProperty("velo.fidelity.out").orNull
val changelogScript = rootProject.layout.projectDirectory.file(".github/scripts/changelog.sh")
tasks.withType<Test>().configureEach {
    // ChangeNotesTest reads max_item_length from changelog.sh, so editing the script alone must rerun tests.
    inputs.file(changelogScript)
        .withPropertyName("changelogScript")
        .withPathSensitivity(PathSensitivity.NONE)
    if (fidelityOut == null) {
        exclude("**/fidelity/FidelityCaptureTest*")
    } else {
        systemProperty("velo.fidelity.out", fidelityOut)
        systemProperty("roborazzi.test.record", "true")
        for (name in listOf("velo.fidelity.plan", "velo.fidelity.screens")) {
            providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
        }
        inputs.property("velo.fidelity.out", fidelityOut)
        outputs.upToDateWhen { false }
    }
}

// The Zig core (native/) builds libvelocore.so, linking libghostty-vt statically.
// Baseline CPUs on purpose (#337): arm64 crypto extensions are optional, and a core built with
// them dies with SIGILL on a device without them. TLS then asks for ChaCha20 first; see
// "TLS cipher" in docs/native-core.md.
val zigCoreAbis = mapOf("arm64-v8a" to "aarch64-linux-android.30", "x86_64" to "x86_64-linux-android.30")
val zigCoreTasks = zigCoreAbis.map { (abi, target) ->
    tasks.register<Exec>("zigCore_${abi.replace('-', '_')}") {
        val root = rootProject.projectDir
        val out = layout.buildDirectory.dir("zig/jniLibs/$abi").get().asFile
        inputs.dir(File(root, "native/src"))
        inputs.file(File(root, "native/build.zig"))
        inputs.file(File(root, "native/build.zig.zon"))
        outputs.dir(out)
        workingDir = File(root, "native")
        environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.path)
        commandLine("zig", "build", "-Doptimize=ReleaseFast", "-Dtarget=$target",
            "--prefix", out.path, "--prefix-lib-dir", ".")
    }
}
val zigCore by tasks.registering { dependsOn(zigCoreTasks) }
tasks.named("preBuild") { dependsOn(zigCore) }

// The app's Tailscale node: libtailscale (tsnet as a C library) at the commit pinned in
// native/tailscale/go.mod, built with Go and the NDK (native/tailscale/build.sh).
val tailscaleTasks = zigCoreAbis.keys.map { abi ->
    tasks.register<Exec>("tailscale_${abi.replace('-', '_')}") {
        val dir = File(rootProject.projectDir, "native/tailscale")
        val out = layout.buildDirectory.dir("tailscale/jniLibs/$abi").get().asFile
        inputs.files(fileTree(dir) { include("go.mod", "go.sum", "build.sh", "**/*.go"); exclude("**/*_test.go") })
        outputs.dir(out)
        environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.path)
        commandLine(File(dir, "build.sh").path, abi, out.path)
    }
}
val tailscale by tasks.registering { dependsOn(tailscaleTasks) }
tasks.named("preBuild") { dependsOn(tailscale) }
