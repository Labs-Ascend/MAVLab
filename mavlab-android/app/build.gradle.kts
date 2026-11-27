plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ascend.mavlab"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ascend.mavlab"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.6.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.sceneview)

    testImplementation(kotlin("test-junit", libs.versions.kotlin.get()))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
