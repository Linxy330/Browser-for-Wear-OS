import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kenny.browserforwearos"
    compileSdk {
        // GeckoView 158 AAR 元数据要求 minCompileSdk=37.2（DECISIONS D-008）
        version = release(37) {
            minorApiLevel = 2
        }
    }

    // 构建输出与中间产物外置到 Browser_Agent/build/app，Browser 仓库内不落产物
    layout.buildDirectory.set(layout.projectDirectory.dir("../../Browser_Agent/build/app"))

    defaultConfig {
        applicationId = "com.kenny.browserforwearos"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"

    }

    // GeckoView 按 ABI 分发：单个 Mozilla AAR 只含一份 ABI，
    // 用 flavor 选择依赖与 abiFilters，避免同变体双引擎冲突（DECISIONS D-002）
    flavorDimensions += "abi"
    productFlavors {
        create("arm") {
            dimension = "abi"
            ndk {
                abiFilters += listOf("armeabi-v7a")
            }
        }
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // GeckoView 类库为 Java 17 字节码；源码兼容级别随之固定为 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

configurations.configureEach {
    resolutionStrategy {
        // GeckoView 158 POM 声明 kotlin-stdlib 2.4.20，但其 classes.jar 不含任何 Kotlin 类；
        // 统一钉回 AGP 9.4.1 内置 Kotlin 配套的 2.2.10，避免元数据版本冲突（DECISIONS D-007）
        force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.play.services.wearable)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    // 引擎依赖跟随 ABI flavor；其余依赖对所有 ABI 共用
    "armImplementation"(libs.geckoview.armeabi.v7a)
    "arm64Implementation"(libs.geckoview.arm64.v8a)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}
