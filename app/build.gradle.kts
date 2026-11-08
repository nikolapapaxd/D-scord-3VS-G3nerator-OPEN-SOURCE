plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.discord.evs.generator"
    compileSdk = 34
    // Pin build tools to the same major version as compileSdk so multiple installed SDKs don't collide.
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.discord.evs.generator"
        minSdk = 26
        targetSdk = 34
        versionCode = 65
        versionName = "6.5.0"
        ndk {
            // Ship only modern ABIs for smaller APK + fewer RE targets
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }


    // ── Signing ──────────────────────────────────────────────────────────
    // Android will NOT install an unsigned APK ("package appears to be invalid").
    //
    // Option A (easiest for local installs): use the Android debug keystore.
    // Option B (proper release): create release.keystore and set paths below.
    //
    // Create a release key once:
    //   keytool -genkeypair -v -keystore release.keystore -alias evs \
    //     -keyalg RSA -keysize 2048 -validity 10000
    //
    signingConfigs {
        create("release") {
            // Prefer app/release.keystore (pinned in NativeGuard). If missing, fall back to
            // Android debug keystore so assembleRelease still works for local installs.
            val releaseStore = file("release.keystore")
            val debugStore = file(System.getProperty("user.home") + "/.android/debug.keystore")
            when {
                releaseStore.exists() -> {
                    storeFile = releaseStore
                    storePassword = (System.getenv("EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android").toString()
                    keyAlias = "evs"
                    keyPassword = (System.getenv("EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android").toString()
                }
                debugStore.exists() -> {
                    println("WARNING: app/release.keystore missing — signing release with debug keystore")
                    storeFile = debugStore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
                else -> {
                    // Last resort: still point at release path so Gradle error is clear
                    storeFile = releaseStore
                    storePassword = (System.getenv("EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android").toString()
                    keyAlias = "evs"
                    keyPassword = (System.getenv("EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android").toString()
                }
            }
        }
        create("debugLocal") {
            val debugStore = file(
                System.getProperty("user.home") + "/.android/debug.keystore"
            )
            if (debugStore.exists()) {
                storeFile = debugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Required — unsigned APKs cannot be installed on device
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// R8 full mode is enabled by default with AGP 8+; the proguard rules below make it
// even more aggressive. All of this is free and does not require a commercial license.

dependencies {

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Background update poll (GitHub Releases → system notification)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // On-device ML Kit image labeling — no API key, fully offline after first model download
    implementation("com.google.mlkit:image-labeling:17.0.7")
}


// ─── Post-release protection (dpt-shell) ───────────────────────────────────
// Must run after AGP creates assembleRelease. Use afterEvaluate + matching.


// ── Auto SECV2 secrets wrap (no keytool for maintainer) ─────────────────────────
// Runs before assembleRelease: reads app/release.keystore SHA-256 and re-encrypts
// secrets.txt → res/raw/secrets.dat with SECV2 outer layer.
tasks.register("encryptSecretsSecv2") {
    group = "build"
    description = "Encrypt secrets.dat with SECV2 (signing-cert bound) using release.keystore"
    doLast {
        val ks = file("release.keystore")
        val secretsTxt = rootProject.file("secrets/secrets.txt")
        val outDat = file("src/main/res/raw/secrets.dat")
        val encScript = rootProject.file("scripts/secrets/encrypt_secrets.py")
        if (!secretsTxt.exists()) {
            logger.warn("secrets/secrets.txt missing — skip encryptSecretsSecv2")
            return@doLast
        }
        if (!encScript.exists()) {
            logger.warn("encrypt_secrets.py missing — skip")
            return@doLast
        }
        var certHex = ""
        if (ks.exists()) {
            val proc = ProcessBuilder(
                "keytool", "-list", "-v",
                "-keystore", ks.absolutePath,
                "-storepass", (System.getenv("EVS_KEYSTORE_PASSWORD") ?: "android").toString()EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android"}",
                "-alias", "evs"
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val re = Regex("""SHA256:\s*([0-9A-Fa-f:]+)""")
            val m = re.find(out)
            if (m != null) {
                certHex = m.groupValues[1].replace(":", "").lowercase()
                logger.lifecycle("SECV2 cert SHA-256: ${certHex.take(16)}…")
            } else {
                logger.warn("Could not parse SHA256 from keytool — writing legacy secrets.dat")
            }
        } else {
            logger.warn("release.keystore missing — legacy secrets.dat only")
        }
        val pb = ProcessBuilder("python3", encScript.absolutePath)
        pb.directory(rootProject.projectDir)
        pb.environment()["EVS_SIGNING_CERT_SHA256"] = certHex
        pb.redirectErrorStream(true)
        val p = pb.start()
        logger.lifecycle(p.inputStream.bufferedReader().readText())
        val code = p.waitFor()
        if (code != 0) throw GradleException("encrypt_secrets.py failed exit=$code")
        if (!outDat.exists()) throw GradleException("secrets.dat not written")
        logger.lifecycle("secrets.dat ready (${outDat.length()} bytes)")
    }
}

afterEvaluate {
    listOf("preBuild", "mergeReleaseResources", "processReleaseResources", "assembleRelease").forEach { name ->
        tasks.findByName(name)?.dependsOn("encryptSecretsSecv2")
    }
}

// original afterEvaluate
afterEvaluate {
    val assembleRel = tasks.findByName("assembleRelease")
        ?: tasks.findByName("assemble") // fallback
    if (assembleRel == null) {
        logger.warn("assembleRelease not found — protectRelease not wired")
        return@afterEvaluate
    }
    val protect = tasks.register("protectRelease") {
        group = "build"
        description = "Full protect pipeline: (R8+SECV2 already in APK) → DPT → re-sign with release.keystore"
        doLast {
            logger.lifecycle("═══ protectRelease pipeline ═══")
            logger.lifecycle("1) Already applied during assembleRelease: R8 minify/shrink + SECV2 secrets.dat")
            logger.lifecycle("2) DPT shell pack on release APK")
            logger.lifecycle("3) Re-sign with app/release.keystore (not DPT default debug key)")

            val dptJar = rootProject.file("tools/dpt/executable/dpt.jar")
            if (!dptJar.exists()) {
                logger.warn("dpt.jar not found at ${dptJar.absolutePath} — skip protectRelease")
                return@doLast
            }
            val candidates = listOf(
                file("${layout.buildDirectory.get()}/outputs/apk/release/app-release.apk"),
                file("${layout.buildDirectory.get()}/outputs/apk/release/app-release-unsigned.apk"),
                file("build/outputs/apk/release/app-release.apk")
            )
            val inputApk = candidates.firstOrNull { it.exists() }
                ?: throw GradleException("Release APK missing. Looked in: ${candidates.map { it.absolutePath }}")
            val outDir = file("${layout.buildDirectory.get()}/outputs/apk/protected")
            outDir.mkdirs()

            // Step 2: DPT (input = already R8+SECV2 release APK)
            logger.lifecycle("DPT input: ${inputApk.absolutePath}")
            exec {
                commandLine(
                    "java", "-jar", dptJar.absolutePath,
                    "-f", inputApk.absolutePath,
                    "-o", outDir.absolutePath,
                    "-e", "x86,x86_64"
                )
                workingDir(rootProject.projectDir)
            }

            // Prefer DPT unsigned output, re-sign with our release keystore so SECV2 cert matches
            val dptUnsigned = file("${outDir.absolutePath}/app-release_unsign.apk")
            val dptSignedDefault = file("${outDir.absolutePath}/app-release_signed.apk")
            val finalApk = file("${outDir.absolutePath}/app-release_protected.apk")
            val toSign = when {
                dptUnsigned.exists() -> dptUnsigned
                dptSignedDefault.exists() -> dptSignedDefault
                else -> outDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
            }
            if (toSign == null) {
                logger.warn("DPT produced no APK under ${outDir.absolutePath}")
                return@doLast
            }

            val ks = file("release.keystore")
            if (ks.exists()) {
                // Strip any existing signature then apksigner with release key
                val unsignedTmp = file("${outDir.absolutePath}/_to_sign.apk")
                toSign.copyTo(unsignedTmp, overwrite = true)
                // zip -d META-INF if jarsigner residue — best-effort via apksigner --overwrite
                val sdkDir = System.getenv("ANDROID_HOME")
                    ?: System.getenv("ANDROID_SDK_ROOT")
                    ?: (System.getProperty("user.home") + "/AppData/Local/Android/Sdk")
                val buildTools = file("$sdkDir/build-tools").listFiles()
                    ?.filter { it.isDirectory }
                    ?.maxByOrNull { it.name }
                val apksigner = buildTools?.resolve("apksigner.bat")
                    ?: buildTools?.resolve("apksigner")
                if (apksigner != null && apksigner.exists()) {
                    logger.lifecycle("Re-signing with release.keystore via ${apksigner.name}")
                    exec {
                        commandLine(
                            apksigner.absolutePath, "sign",
                            "--ks", ks.absolutePath,
                            "--ks-key-alias", "evs",
                            "--ks-pass", "pass:${System.getenv("EVS_KEYSTORE_PASSWORD") ?: "android"}"EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android"}",
                            "--key-pass", "pass:${System.getenv("EVS_KEYSTORE_PASSWORD") ?: project.findProperty("EVS_KEYSTORE_PASSWORD") ?: "android"}",
                            "--out", finalApk.absolutePath,
                            unsignedTmp.absolutePath
                        )
                    }
                    logger.lifecycle("FINAL protected+release-signed: ${finalApk.absolutePath}")
                } else {
                    logger.warn("apksigner not found under $sdkDir/build-tools — keeping DPT output as-is")
                    logger.lifecycle("Protected APK(s) under ${outDir.absolutePath}")
                }
            } else {
                logger.warn("release.keystore missing — DPT default signature kept")
                logger.lifecycle("Protected APK(s) under ${outDir.absolutePath}")
            }
            logger.lifecycle("═══ protectRelease done ═══")
        }
    }
    assembleRel.finalizedBy(protect)
}
