import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- 签名配置（P0-2 加固）
// 优先级：环境变量/用户目录密钥文件 > 项目内 keystore.properties（已 gitignore，仅作兼容回退）。
// 推荐做法：把 keystore 与口令放在项目之外，通过环境变量注入，避免密码明文留在工作区。
val keystorePropertiesFile = rootProject.file("keystore.properties")
// 回退位置：用户目录下的私密配置（项目外，避免明文长期留在工作区）
val userKeystorePropertiesFile = File(System.getProperty("user.home"), ".lanlink/keystore.properties")
val keystoreProperties = Properties().apply {
    when {
        keystorePropertiesFile.exists() -> keystorePropertiesFile.inputStream().use { load(it) }
        userKeystorePropertiesFile.exists() -> userKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun envOrProperty(envName: String, property: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val resolvedStoreFile: String? = System.getenv("LANT_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val resolvedStorePassword: String? = envOrProperty("LANT_KEYSTORE_PASSWORD", "storePassword")
val resolvedKeyAlias: String? = System.getenv("LANT_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val resolvedKeyPassword: String? = envOrProperty("LANT_KEY_PASSWORD", "keyPassword")
val hasSigningMaterial: Boolean = resolvedStoreFile != null && resolvedStorePassword != null &&
    resolvedKeyAlias != null && resolvedKeyPassword != null &&
    rootProject.file(resolvedStoreFile).exists()

android {
    namespace = "com.manst.lantransfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manst.lantransfer"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "2.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 资源精简：只保留中英文（UI 文案本身只有中文）；减少 AndroidX/依赖库内置的大量翻译资源
        resourceConfigurations += listOf("zh", "en")
    }

    // ---------------------------------------------------------------- 分发渠道（flavor）
    // github：官网 / GitHub Release / 蓝奏云分发，保留应用内更新与 ABI 拆分（体积更小）；
    // fdroid：F-Droid / IzzyOnDroid 分发，关闭应用内更新（更新由商店客户端负责）、
    //         移除 REQUEST_INSTALL_PACKAGES、输出单个 universal APK。
    // 两个 flavor 的 applicationId / versionCode / versionName 完全一致，且使用同一签名配置，
    // 因此包名相同、可相互覆盖安装（前提是签名由同一 keystore 签出；F-Droid 主库会用它自己的密钥重签）。
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "IN_APP_UPDATES", "true")
        }
        create("fdroid") {
            dimension = "dist"
            buildConfigField("boolean", "IN_APP_UPDATES", "false")
        }
    }

    signingConfigs {
        if (hasSigningMaterial) {
            create("release") {
                storeFile = rootProject.file(resolvedStoreFile!!)
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningMaterial) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Gradle 已对齐到 AGP 8.7 官方基线（8.9），lint worker 恢复正常（此前 Gradle 9.6 组合下必崩）。
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
        // 已知且已评估的告警：
        // - TrustAllX509TrustManager / CustomX509TrustManager：FTPS 证书固定与「用户显式信任任意证书」分支，
        //   已由 CertificatePinning + 默认严格校验约束（见 docs/security.html）。
        disable += setOf("TrustAllX509TrustManager", "CustomX509TrustManager")
    }

    // ABI 拆分：仅 github 渠道做拆分（含 universal 兜底），减小下载体积；
    // fdroid 渠道输出单个 universal APK（F-Droid 不支持按 ABI 分发矩阵）。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // 发布 Google Play 时使用 AAB，并按语言/密度/ABI 拆分（不影响本地 APK 构建）
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                // JSch / BouncyCastle 多版本 jar 里的重复元数据（与运行时无关）
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/module-info.class",
                "META-INF/versions/**/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                // BouncyCastle 未使用的 PQC 参数资源（>1.2MB，压缩率很低）与多语言错误文案
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/**/CertPathReviewerMessages_*.properties"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 协议实现：FTP/FTPS 用 Apache Commons Net；SFTP/SCP 用 JSch(mwiede 维护版)
    implementation(libs.commons.net)
    implementation(libs.jsch)

    // 本机服务器：SFTP/SCP 服务端用 Apache MINA SSHD；FTPS 自签证书用 BouncyCastle
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    implementation(libs.sshd.scp)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.slf4j.nop)

    // 文件夹传输（SAF 目录树遍历）
    implementation(libs.androidx.documentfile)

    // 扫码快速传输：二维码生成（ZXing core）+ 相机扫码界面（zxing-android-embedded）
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockftpserver)
    testImplementation(libs.org.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}