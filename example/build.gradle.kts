import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

// 示例 app：真机验收场景（与 iOS 示例 ScenarioRunner 逐字对应）+ 手测按钮。
// key 绝不入库：从 `sdk/android/retriever.local.properties`（gitignored；`example/gen-local-properties.sh` 从仓库根 .env 生成）
// 读进 BuildConfig；缺失时 key 为空 → 只写本地不上传。baseURL 默认 staging（staging 只收 lk_test_ key）。

val localProperties = Properties().apply {
    val f = rootProject.file("retriever.local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProperty(name: String, fallback: String = ""): String =
    (project.findProperty(name) as String?) ?: localProperties.getProperty(name, fallback)

android {
    namespace = "org.revdog.retriever.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.revdog.retriever.example"
        minSdk = libs.versions.android.exampleMinSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = project.property("VERSION_NAME") as String

        buildConfigField("String", "RETRIEVER_KEY", "\"${localProperty("RETRIEVER_KEY")}\"")
        buildConfigField("String", "RETRIEVER_BASE_URL", "\"${localProperty("RETRIEVER_BASE_URL", "https://logs-staging.revdog.org")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            // release 走 R8：SDK 的 consumer 规则必须自足（本 app 的 proguard-rules.pro 里没有任何 keep）。
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":retriever"))
    implementation(project(":retriever-timber"))
}
