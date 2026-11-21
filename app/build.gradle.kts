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
        versionCode = 10
        versionName = "2.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 资源精简：只保留中英文（UI 文案本身只有中文）；减少 AndroidX/依赖库内置的大量翻译资源
        resourceConfigurations += listOf("zh", "en")
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
        // 本机（Windows + JDK 21）实测 lintAnalyzeDebugAndroidTest 会崩（Gradle lint worker 异常），
        // 因此不在本地 release 构建中强制跑 lintVital；静态检查交由 CI（Linux/JDK17）执行：
        //   ./gradlew lintDebug lintRelease
        // 在 CI 恢复前，本地提交前以单元测试 + 设备测试为主。
        checkReleaseBuilds = false
        abortOnError = false
    }

    // ABI 拆分：为每种 ABI 生成独立 APK（含 universal 兜底），减小下载体积；
    // 说明：当前 App 原生库很小（~40KB），实际收益有限，但可为后续引入原生库做好准备。
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