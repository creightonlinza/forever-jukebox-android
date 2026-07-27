import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")

    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val madmomBeatsPortFfiAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val madmomBeatsPortFfiVersion = "4.1.1"
val madmomBeatsPortFfiZipUrlProperty: Provider<String> = providers.gradleProperty("madmomBeatsPortFfiZipUrl")
val madmomBeatsPortFfiZipPathProperty: Provider<String> = providers.gradleProperty("madmomBeatsPortFfiZipPath")
val madmomBeatsPortFfiZipUrl: Provider<String> = madmomBeatsPortFfiZipUrlProperty.orElse(
    "https://github.com/creightonlinza/madmom-beats-port/releases/download/v$madmomBeatsPortFfiVersion/madmom-beats-port-v$madmomBeatsPortFfiVersion-android.zip"
)
val madmomBeatsPortFfiZipCache: Provider<RegularFile> =
    layout.buildDirectory.file("downloads/madmom-beats-port-v$madmomBeatsPortFfiVersion-android.zip")
val madmomBeatsPortFfiGeneratedJniLibs: Provider<Directory> =
    layout.buildDirectory.dir("generated/madmom_beats_port_ffi/jniLibs")
val essentiaSharedJniLibsDir = file("third_party/essentia/android")
val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

fun resolveReleaseSigningValue(propertyName: String, envName: String): String? {
    val propertyValue = keystoreProperties.getProperty(propertyName)?.trim().orEmpty()
    if (propertyValue.isNotEmpty()) {
        return propertyValue
    }
    val envValue = System.getenv(envName)?.trim().orEmpty()
    return envValue.ifEmpty { null }
}

val releaseStoreFilePath = resolveReleaseSigningValue("storeFile", "ANDROID_STORE_FILE")
val releaseStorePassword = resolveReleaseSigningValue("storePassword", "ANDROID_STORE_PASSWORD")
val releaseKeyAlias = resolveReleaseSigningValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = resolveReleaseSigningValue("keyPassword", "ANDROID_KEY_PASSWORD")
val releaseStoreFile = releaseStoreFilePath?.let(rootProject::file)
val hasReleaseSigningCredentials = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }
val hasReleaseSigningConfig = hasReleaseSigningCredentials && releaseStoreFile?.exists() == true
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val versionCodeBase = System.getenv("APP_VERSION_CODE_BASE")?.toIntOrNull() ?: 0
val ciVersionCode = runNumber + versionCodeBase
val versionTag = System.getenv("APP_VERSION_TAG")?.trim().orEmpty()
val versionStamp: String = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy.MM"))
val ciVersionName = versionTag.ifEmpty { "$versionStamp.$runNumber" }

val prepareMadmomBeatsPortFfiJniLibs by tasks.registering {
    description = "Fetches madmom_beats_port_ffi Android binaries and stages ABI jniLibs for packaging."
    outputs.dir(madmomBeatsPortFfiGeneratedJniLibs)

    doLast {
        val outputRoot = madmomBeatsPortFfiGeneratedJniLibs.get().asFile
        if (outputRoot.exists()) {
            outputRoot.deleteRecursively()
        }
        outputRoot.mkdirs()

        val localZipPath = madmomBeatsPortFfiZipPathProperty.orNull?.trim().orEmpty()
        val zipFile = if (localZipPath.isNotEmpty()) {
            file(localZipPath)
        } else {
            val cached = madmomBeatsPortFfiZipCache.get().asFile
            if (!cached.exists()) {
                cached.parentFile.mkdirs()
                logger.lifecycle("Downloading madmom_beats_port_ffi release: ${madmomBeatsPortFfiZipUrl.get()}")
                URI.create(madmomBeatsPortFfiZipUrl.get()).toURL().openStream().use { input ->
                    Files.copy(input, cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            cached
        }

        if (!zipFile.exists()) {
            throw GradleException(
                "madmom_beats_port_ffi release zip was not found: ${zipFile.absolutePath}"
            )
        }

        val copiedAbis = mutableSetOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.endsWith("/libmadmom_beats_port_ffi.so")) {
                    val abi = madmomBeatsPortFfiAbis.firstOrNull { name.contains("/$it/") }
                    if (abi != null) {
                        val abiDir = File(outputRoot, abi)
                        abiDir.mkdirs()
                        val outputSo = File(abiDir, "libmadmom_beats_port_ffi.so")
                        outputSo.outputStream().use { out ->
                            zis.copyTo(out)
                        }
                        copiedAbis += abi
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val missingAbis = madmomBeatsPortFfiAbis.filterNot { copiedAbis.contains(it) }
        if (missingAbis.isNotEmpty()) {
            throw GradleException(
                "madmom_beats_port_ffi zip missing required ABIs: ${missingAbis.joinToString(", ")}"
            )
        }
    }
}

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.foreverjukebox.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.foreverjukebox.app"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName

        // Fixed base URL of the Local-mode Cast relay (fj-android-cast). Local/play casting always
        // uploads to this deployed relay; there is no per-build override.
        buildConfigField("String", "RELAY_CAST_BASE_URL", "\"https://fj-android-cast.fly.dev\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // Distinct id so the local-only Play app and the bare-id GitHub `full`
            // build (com.foreverjukebox.app) can coexist and never signature-conflict.
            applicationIdSuffix = ".play"
            buildConfigField("boolean", "SERVER_MODE_AVAILABLE", "false")
            // Relay Cast receiver registered against this flavor's sender package
            // (com.foreverjukebox.app.play). Same receiver URL as the full flavor's id below.
            buildConfigField("String", "RELAY_CAST_APP_ID", "\"7DB3725D\"")
        }
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "SERVER_MODE_AVAILABLE", "true")
            // Relay Cast receiver registered against this flavor's sender package
            // (com.foreverjukebox.app) — a second console app id pointing at the same relay URL,
            // because the Cast console allows only one sender package per receiver app id.
            buildConfigField("String", "RELAY_CAST_APP_ID", "\"4CF1235E\"")
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Full-flavor releases override this to "true" in androidComponents below.
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Crashlytics mapping upload is automatic for minified builds; native
            // symbol upload only runs via the explicit
            // uploadCrashlyticsSymbolFile<Variant> task (wired in CI release jobs).
            configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets.named("main") {
        jniLibs.directories.add(madmomBeatsPortFfiGeneratedJniLibs.get().asFile.absolutePath)
        jniLibs.directories.add(essentiaSharedJniLibsDir.absolutePath)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Android Studio can fail local non-debuggable APK deploys while installing
    // generated baseline profiles. Keep profiles packaged for release artifacts,
    // but do not make local APK install depend on the deploy-time profile step.
    installation {
        enableBaselineProfile = false
    }

    splits {
        abi {
            isEnable = false
        }
    }

}

extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set("$ciVersionName-dev")
        }
    }
    // Full-flavor releases permit cleartext at the platform layer so self-hosted http:// servers
    // work in Server mode; CleartextGuardInterceptor restricts http to private/local addresses.
    // The play flavor has no Server mode and stays https-only.
    onVariants(selector().withFlavor("distribution", "full").withBuildType("release")) { variant ->
        variant.manifestPlaceholders.put("usesCleartextTraffic", "true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

detekt {
    toolVersion = "1.23.8"
    config.setFrom(rootProject.file("detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
    ignoreFailures = false
}

// Attach the native-lib download to the tasks that actually consume the staged
// jniLibs (merge<Variant>JniLibFolders / merge<Variant>NativeLibs) rather than the
// broad preBuild anchor, so lint/detekt-only runs don't trigger the download.
tasks.matching { task ->
    task.name.startsWith("merge") &&
        (task.name.endsWith("JniLibFolders") || task.name.endsWith("NativeLibs"))
}.configureEach {
    dependsOn(prepareMadmomBeatsPortFfiJniLibs)
}

tasks.named("check").configure {
    dependsOn("detekt")
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.compose.runtime:runtime:1.10.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.mediarouter:mediarouter:1.8.1")

    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Coil only loads remote cover art in server-mode search, which is full-flavor
    // only. The play flavor is local-only and never fetches remote images.
    "fullImplementation"("io.coil-kt.coil3:coil-compose:3.4.0")
    "fullImplementation"("io.coil-kt.coil3:coil-network-okhttp:3.4.0")
    // Firebase in both flavors: Crashlytics for crash/non-fatal reporting (-ndk
    // covers native crashes in the Essentia/madmom/Oboe JNI code) and Analytics
    // for the GA4 web-parity events (AnalyticsGateway) plus lifecycle
    // diagnostics events (DiagnosticsGateway).
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("com.google.oboe:oboe:1.10.0")
    implementation("com.google.android.gms:play-services-cast-framework:22.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.10.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
