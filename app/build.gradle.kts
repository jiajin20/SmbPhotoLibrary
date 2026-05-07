plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.smbphoto"
    compileSdk = 34   // 依赖库要求 >= 34，不能与 targetSdk 对齐

    defaultConfig {
        applicationId = "com.example.smbphoto"
        minSdk = 21
        targetSdk = 30   // 对齐 Android 11，避免 JUUI 对高 targetSdk 的额外权限审查
        versionCode = 1
        versionName = "1.0.0"
        // 声明支持的 CPU 架构（部分 TV 安装器会检查）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // 使用 JKS 格式签名（PKCS12 在部分国产 TV 兼容性差）
        create("release") {
            storeFile = file("${rootDir}/release.jks")
            storePassword = "smbphoto123"
            keyAlias = "smbphoto"
            keyPassword = "smbphoto123"
        }
    }

    buildTypes {
        debug {
            isDebuggable = false       // 去掉 debuggable 标记，JUUI 不拦截
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false   // 禁用混淆（R8 可能导致资源名混淆影响 TV 解析）
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"   // 不上传 Play Store，无需此检查
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
    }

    packagingOptions {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.swipe.refresh)

    // Lifecycle & ViewModel
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)

    // Coroutines
    implementation(libs.coroutines.android)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Glide image loading
    implementation(libs.glide)
    ksp(libs.glide.ksp)

    // SMBJ - SMB protocol client
    implementation(libs.smbj)

    // Security
    implementation(libs.security.crypto)

    // TV Leanback
    implementation(libs.leanback)

    // Window size class (Pad responsive)
    implementation(libs.window)

    // PhotoView - 手势缩放/双击放大
    implementation(libs.photoview)

    // Media3 ExoPlayer - 流式边下边播
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
}
