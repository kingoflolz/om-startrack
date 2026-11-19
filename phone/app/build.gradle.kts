import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

val tetra3NdkVersion = providers.gradleProperty("tetra3NdkVersion")
    .getOrElse("27.2.12479018")
val tetra3CargoNdkVersion = "4.1.2"
val tetra3AndroidPlatform = 26
val tetra3SupportedAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)
val tetra3ElfMachines = mapOf(
    "arm64-v8a" to "AArch64",
    "x86_64" to "Advanced Micro Devices X86-64",
)
val tetra3JniExports = setOf(
    "Java_dev_omshutter_plate_NativeTetra3Bridge_nativeCreate",
    "Java_dev_omshutter_plate_NativeTetra3Bridge_nativeClose",
    "Java_dev_omshutter_plate_NativeTetra3Bridge_nativeSolve",
)
val tetra3Abis = providers.gradleProperty("tetra3Abis")
    .getOrElse("arm64-v8a")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

require(tetra3Abis.isNotEmpty()) {
    "tetra3Abis must contain at least one Android ABI"
}
val unsupportedTetra3Abis = tetra3Abis.filterNot(tetra3SupportedAbis::containsKey)
require(unsupportedTetra3Abis.isEmpty()) {
    "Unsupported tetra3Abis ${unsupportedTetra3Abis.joinToString()}; " +
        "supported ABIs are ${tetra3SupportedAbis.keys.joinToString()}"
}

// tetra3 is phone-owned and intentionally remains one workspace beside the app.
val tetra3Workspace = rootProject.layout.projectDirectory.dir("tetra3").asFile
val tetra3AndroidManifest = File(tetra3Workspace, "crates/tetra3-android/Cargo.toml")
val tetra3RustOutput = layout.buildDirectory.dir("tetra3/rust-jniLibs")
val tetra3PackagedJni = layout.buildDirectory.dir("generated/tetra3/jniLibs")
val tetra3PackagedAssets = layout.buildDirectory.dir("generated/tetra3/assets")
val tetra3Index = File(tetra3Workspace, "build/index/om1-300f4-to-45.t3ix")
val tetra3LensProfiles = File(tetra3Workspace, "build/profiles/om1-all-mft.t3lf")
val tetra3Assets = listOf(tetra3Index, tetra3LensProfiles)

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}
val tetra3SdkDirectory = providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: localProperties.getProperty("sdk.dir")
val tetra3NdkDirectory = providers.gradleProperty("tetra3NdkDir").orNull
    ?: providers.environmentVariable("ANDROID_NDK_HOME").orNull
    ?: providers.environmentVariable("ANDROID_NDK_ROOT").orNull
    ?: tetra3SdkDirectory?.let { File(it, "ndk/$tetra3NdkVersion").absolutePath }
val tetra3Cargo = providers.gradleProperty("tetra3Cargo").getOrElse("cargo")

fun commandOutput(workingDirectory: File, vararg command: String): Pair<Int, String> =
    try {
        val process = ProcessBuilder(*command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() to output.trim()
    } catch (error: Exception) {
        -1 to (error.message ?: error.javaClass.simpleName)
    }

android {
    namespace = "dev.omshutter"
    compileSdk = 36
    ndkVersion = tetra3NdkVersion

    defaultConfig {
        applicationId = "dev.omshutter"
        minSdk = tetra3AndroidPlatform
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += tetra3Abis
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(tetra3PackagedAssets)
            jniLibs.srcDir(tetra3PackagedJni)
        }
        getByName("test") {
            // Exercise the Java codec against the same tracked vectors as the
            // camera and host implementations.
            resources.srcDir(
                rootProject.layout.projectDirectory.dir("../shared/contracts")
            )
        }
    }

    androidResources {
        // AssetManager.openFd() only works for stored assets. The JNI layer
        // maps the index from the APK fd without copying 108 MiB into heap.
        noCompress += listOf("t3ix", "t3lf")
    }

    packaging {
        jniLibs {
            // Store and page-align native libraries so Android can load them
            // directly from the APK on API 26+.
            useLegacyPackaging = false
            // Cargo's release profile already strips this library, so AGP
            // does not need to mutate the verified Cargo output again.
            keepDebugSymbols += "**/libtetra3_android.so"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("junit:junit:4.13.2")
}

val buildTetra3Rust by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libtetra3_android.so for the configured Android ABIs"
    workingDir(tetra3Workspace)

    inputs.files(
        fileTree(tetra3Workspace) {
            include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml")
            include("crates/tetra3-android/**")
            include("crates/tetra3-core/**")
            include("crates/tetra3-index/**")
            include("crates/tetra3-lens/**")
            include("crates/tetra3/**")
        }
    )
    inputs.property("abis", tetra3Abis.joinToString(","))
    inputs.property("ndkVersion", tetra3NdkVersion)
    inputs.property("cargoNdkVersion", tetra3CargoNdkVersion)
    outputs.files(tetra3Abis.map { abi ->
        tetra3RustOutput.map { it.file("$abi/libtetra3_android.so") }
    })

    val command = mutableListOf(tetra3Cargo, "ndk")
    command += listOf(
        "--platform",
        tetra3AndroidPlatform.toString(),
    )
    tetra3Abis.forEach { abi ->
        command += listOf("--target", abi)
    }
    command += listOf(
        "--output-dir",
        tetra3RustOutput.get().asFile.absolutePath,
        "build",
        "--locked",
        "--release",
        "--package",
        "tetra3-android",
    )
    commandLine(command)

    if (tetra3NdkDirectory != null) {
        environment("ANDROID_NDK_HOME", tetra3NdkDirectory)
    }
    // NDK r27 needs explicit ELF segment alignment for Android's 16 KiB page
    // mode. Unit separator is Cargo's CARGO_ENCODED_RUSTFLAGS delimiter.
    environment(
        "CARGO_ENCODED_RUSTFLAGS",
        listOf(
            "-Clink-arg=-Wl,-z,max-page-size=16384",
            "-Clink-arg=-Wl,-z,common-page-size=16384",
        ).joinToString("\u001f")
    )

    doFirst {
        if (!tetra3AndroidManifest.isFile) {
            throw GradleException(
                "Missing ${tetra3AndroidManifest.relativeTo(rootProject.projectDir)}; " +
                    "the tetra3 Android JNI crate has not been checked out."
            )
        }

        val ndk = tetra3NdkDirectory?.let(::File)
        val ndkProperties = ndk?.resolve("source.properties")
        if (ndk == null || !ndk.isDirectory || ndkProperties?.isFile != true) {
            throw GradleException(
                "Tetra3 requires Android NDK $tetra3NdkVersion. Install it with " +
                    "sdkmanager \"ndk;$tetra3NdkVersion\", or pass " +
                    "-Ptetra3NdkDir=/absolute/path/to/android-ndk."
            )
        }
        val installedNdkVersion = ndkProperties.readLines()
            .firstOrNull { it.startsWith("Pkg.Revision") }
            ?.substringAfter('=')
            ?.trim()
        if (installedNdkVersion != tetra3NdkVersion) {
            throw GradleException(
                "Expected Android NDK $tetra3NdkVersion at $ndk, found " +
                    "${installedNdkVersion ?: "an unreadable version"}. " +
                    "Use -Ptetra3NdkDir to select the pinned NDK."
            )
        }

        val cargoNdkVersionCommand = arrayOf(tetra3Cargo, "ndk", "--version")
        val (cargoNdkExit, cargoNdkOutput) =
            commandOutput(tetra3Workspace, *cargoNdkVersionCommand)
        val detectedCargoNdkVersion = Regex(
            """(?m)^cargo-ndk\s+(\d+\.\d+\.\d+)\s*$"""
        ).find(cargoNdkOutput)?.groupValues?.get(1)
        if (cargoNdkExit != 0 || detectedCargoNdkVersion != tetra3CargoNdkVersion) {
            throw GradleException(
                "Tetra3 requires cargo-ndk $tetra3CargoNdkVersion. Install it with " +
                    "cargo install cargo-ndk --version $tetra3CargoNdkVersion --locked. " +
                    "Detected: ${cargoNdkOutput.ifEmpty { "nothing" }}"
            )
        }

        val (targetsExit, targetsOutput) = commandOutput(
            tetra3Workspace,
            "rustup",
            "target",
            "list",
            "--installed",
        )
        val installedTargets = targetsOutput.lineSequence().map(String::trim).toSet()
        val requiredTargets = tetra3Abis.map(tetra3SupportedAbis::getValue)
        val missingTargets = requiredTargets.filterNot(installedTargets::contains)
        if (targetsExit != 0 || missingTargets.isNotEmpty()) {
            throw GradleException(
                "Missing Rust Android target(s): ${missingTargets.joinToString()}. " +
                    "Install them with rustup target add --toolchain 1.90.0 " +
                    "${requiredTargets.joinToString(" ")}. " +
                    "Rust output: ${targetsOutput.ifEmpty { "nothing" }}"
            )
        }
    }

    doLast {
        val ndk = File(requireNotNull(tetra3NdkDirectory))
        val prebuiltRoot = ndk.resolve("toolchains/llvm/prebuilt")
            .listFiles()
            ?.singleOrNull(File::isDirectory)
            ?: throw GradleException("Cannot locate the pinned NDK LLVM host tools")
        val readElf = listOf(
            prebuiltRoot.resolve("bin/llvm-readelf"),
            prebuiltRoot.resolve("bin/llvm-readelf.exe"),
        ).firstOrNull(File::isFile)
            ?: throw GradleException("Cannot locate llvm-readelf in $prebuiltRoot")

        tetra3Abis.forEach { abi ->
            val library = tetra3RustOutput.get().file("$abi/libtetra3_android.so").asFile
            if (!library.isFile) {
                throw GradleException("cargo-ndk did not produce the expected $library")
            }
            val (readElfExit, elfReport) = commandOutput(
                tetra3Workspace,
                readElf.absolutePath,
                "--file-header",
                "--program-headers",
                "--dyn-syms",
                "--wide",
                library.absolutePath,
            )
            val loadAlignments = elfReport.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("LOAD ") }
                .mapNotNull { line ->
                    line.substringAfterLast(' ')
                        .removePrefix("0x")
                        .toLongOrNull(16)
                }
                .toList()
            val expectedMachine = tetra3ElfMachines.getValue(abi)
            val hasExpectedMachine = Regex(
                """(?m)^\s*Machine:\s+${Regex.escape(expectedMachine)}\s*$"""
            ).containsMatchIn(elfReport)
            val missingExports = tetra3JniExports.filter { symbol ->
                !Regex("""(?m)\s${Regex.escape(symbol)}$""").containsMatchIn(elfReport)
            }
            if (readElfExit != 0 || loadAlignments.isEmpty() ||
                loadAlignments.any { it < 16 * 1024 } || !hasExpectedMachine ||
                missingExports.isNotEmpty()) {
                throw GradleException(
                    "$library failed Android ELF verification for $abi " +
                        "(machine=$hasExpectedMachine, alignments=$loadAlignments, " +
                        "missingExports=$missingExports). llvm-readelf output:\n$elfReport"
                )
            }
        }
    }
}

val syncTetra3JniLibs by tasks.registering(Sync::class) {
    group = "build"
    description = "Stage ABI-filtered tetra3 JNI libraries for Android packaging"
    val source = tetra3RustOutput.get().asFile
    dependsOn(buildTetra3Rust)
    from(source) {
        include(tetra3Abis.map { "$it/libtetra3_android.so" })
    }
    into(tetra3PackagedJni)
    inputs.property("abis", tetra3Abis.joinToString(","))

    doFirst {
        tetra3Abis.forEach { abi ->
            val library = File(source, "$abi/libtetra3_android.so")
            if (!library.isFile) {
                throw GradleException(
                    "Missing $library. Run :app:buildTetra3Rust and inspect its " +
                        "toolchain diagnostics."
                )
            }
        }
    }
}

val syncTetra3Assets by tasks.registering(Sync::class) {
    group = "build"
    description = "Verify and stage the mmap tetra3 index and MFT lens profiles"
    from(tetra3Assets) {
        into("tetra3")
    }
    into(tetra3PackagedAssets)

    doFirst {
        tetra3Assets.forEach { artifact ->
            if (!artifact.isFile) {
                throw GradleException(
                    "Missing production tetra3 artifact $artifact. " +
                        "Rebuild it using tetra3/README.md before assembling the app."
                )
            }

        }
    }
}

// JVM unit tests do not need the NDK or the 108 MiB index. Packaging tasks do,
// and acquire generated inputs only when an APK/AAB actually needs them.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(syncTetra3Assets) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(syncTetra3JniLibs) }
// AGP lint reads source-set assets directly instead of going through
// merge<Variant>Assets, so declare that generated-input edge explicitly.
tasks.matching { it.name.contains("Lint", ignoreCase = true) }
    .configureEach { dependsOn(syncTetra3Assets) }
