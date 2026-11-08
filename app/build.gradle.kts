import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.blockparty.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blockparty.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The shared Supabase project. The publishable key is public by design (it ships in every
        // client); row-level security protects the data. Override both in local.properties
        // (supabase.url / supabase.key) to point a build at another project.
        val props = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "SUPABASE_URL", "\"${props.getProperty("supabase.url", "https://dpflpwoivvpfvainzwgd.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${props.getProperty("supabase.key", "sb_publishable_m6fV3H-wsiL4ckmSJqQoIQ_JopCEQMT")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    // Websocket for live street updates (Supabase Realtime).
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric normally downloads its Android runtime jar itself, outside Gradle. Fetch it
// through Gradle instead (cached, retried, works behind proxies) and run Robolectric offline.
// The version must match the Robolectric release and the SDK in @Config (4.16 / SDK 35).
val robolectricSdk: Configuration by configurations.creating { isTransitive = false }
dependencies {
    robolectricSdk("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val prepareRobolectric by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectric)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}
