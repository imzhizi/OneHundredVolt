import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            // expect/actual classes 已稳定（Kotlin 2.1+），关闭 Beta 警告
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        iosTarget.compilerOptions {
            // expect/actual classes 已稳定（Kotlin 2.1+），关闭 Beta 警告
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            // Serialization
            implementation(libs.kotlinx.serialization.json)
            // DateTime
            implementation(libs.kotlinx.datetime)
            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.security.crypto)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.ohv.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
