import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Release identity comes from CI: the tag gives the version name, the run
// number the version code. A local build keeps fixed values.
val releaseVersion: String =
    System.getenv("RELEASE_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.1"
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
val appVersionCode: Int = if (buildNumber > 0) 100 + buildNumber else 100

// The keystore travels as a base64 secret and is decoded next to the module,
// outside build/ so `clean` cannot remove it mid-build. *.keystore is ignored.
val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }

android {
    namespace  = "com.anpaiss.notifybridge.karoo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpaiss.notifybridge.karoo"
        minSdk        = 31
        targetSdk     = 34
        versionCode   = appVersionCode
        versionName   = releaseVersion
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.VERSION_NAME for the KarooExtension version
    }

    if (keystoreBase64 != null) {
        signingConfigs {
            create("release") {
                val ksFile = layout.projectDirectory.file(".release.keystore").asFile
                ksFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile     = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

// Karoo extension manifest: the JSON the Karoo fetches from MANIFEST_URL (see
// AndroidManifest.xml). Published next to the APK with every release; the APK
// is uploaded under the fixed name NotifyBridge.apk so releases/latest/download
// works. Schema: io.hammerhead.karooext.models.KarooAppManifest.
val generateManifest by tasks.registering {
    val out  = layout.projectDirectory.file("manifest.json")
    val repo = "https://github.com/anpaiss/NotifyBridge"
    val version = releaseVersion
    val code = appVersionCode
    val notes = System.getenv("RELEASE_NOTES").orEmpty().ifBlank { "See the GitHub release." }
    inputs.property("version", version)
    inputs.property("code", code)
    inputs.property("notes", notes)
    outputs.file(out)
    doLast {
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        out.asFile.writeText(
            """
            {
              "label": "NotifyBridge",
              "packageName": "com.anpaiss.notifybridge.karoo",
              "iconUrl": ${q("https://raw.githubusercontent.com/anpaiss/NotifyBridge/master/docs/icon.png")},
              "latestApkUrl": ${q("$repo/releases/latest/download/NotifyBridge.apk")},
              "latestVersion": ${q(version)},
              "latestVersionCode": $code,
              "developer": "Andrea Paissan",
              "description": ${q("Phone messages and calls in large type at the bottom of the ride screen, with a sound of their own. You choose app by app, and on WhatsApp person by person, what gets through. Needs the NotifyBridge phone app.")},
              "releaseNotes": ${q(notes)},
              "screenshotUrls": [
                ${q("https://raw.githubusercontent.com/anpaiss/NotifyBridge/master/docs/text-sizes.png")},
                ${q("https://raw.githubusercontent.com/anpaiss/NotifyBridge/master/docs/karoo-app.png")}
              ],
              "tags": ["utility"]
            }
            """.trimIndent()
        )
    }
}
tasks.named("preBuild") { dependsOn(generateManifest) }

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
