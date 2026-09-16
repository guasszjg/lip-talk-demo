plugins { id("com.android.library") }

android {
    namespace = "com.zuicun.agegender"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        ndk { abiFilters += "armeabi-v7a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_7
        targetCompatibility = JavaVersion.VERSION_1_7
    }
    packaging { jniLibs.excludes += "**/librknnrt.so" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
}

val java8Compiler = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(8)) }
tasks.withType<JavaCompile>().configureEach { javaCompiler.set(java8Compiler) }
