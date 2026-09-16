import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
}

val buildTime: String = ZonedDateTime
    .now(ZoneId.of("Asia/Shanghai"))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

android {
    namespace = "com.zuicun.liptalkdemo"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.zuicun.liptalkdemo"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "2.8.7"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    testImplementation("junit:junit:4.13.2")
}
