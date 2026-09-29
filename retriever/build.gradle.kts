import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.metalava)
    `maven-publish`
}

// Kotlin 支持来自 AGP 9 的内置集成（`android.builtInKotlin`），与 revenue-dog 一致。
// 依赖铁律（ADR 0003 决定 13）：本模块**只**依赖 kotlin-stdlib —— 无 AndroidX、无 coroutines、无 okhttp。

android {
    namespace = "org.revdog.retriever"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 引擎核心是纯 JVM 可测的；android.* 只在 Platform 实现里。stub 返回默认值防误触。
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Maven 发布：只发到本地 staging 目录（照 revenue-dog；上传与门禁在 `scripts/sdk-android-maven-publish.sh`）。
//   ./gradlew :retriever:publishReleasePublicationToStagingRepository
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("GROUP") as String
                artifactId = project.property("POM_ARTIFACT_ID") as String
                version = project.property("VERSION_NAME") as String
                pom {
                    name.set(project.property("POM_NAME") as String)
                    description.set(project.property("POM_DESCRIPTION") as String)
                    url.set(project.property("POM_URL") as String)
                    licenses {
                        license {
                            name.set(project.property("POM_LICENCE_NAME") as String)
                            url.set(project.property("POM_LICENCE_URL") as String)
                            distribution.set(project.property("POM_LICENCE_DIST") as String)
                        }
                    }
                    developers {
                        developer {
                            id.set(project.property("POM_DEVELOPER_ID") as String)
                            name.set(project.property("POM_DEVELOPER_NAME") as String)
                        }
                    }
                    scm {
                        url.set(project.property("POM_SCM_URL") as String)
                        connection.set(project.property("POM_SCM_CONNECTION") as String)
                        developerConnection.set(project.property("POM_SCM_DEV_CONNECTION") as String)
                    }
                }
            }
        }
        repositories {
            maven {
                name = "staging"
                // 两个模块发到**同一个** staging 根（`sdk/android/build/maven-staging`），
                // 这样 `scripts/sdk-android-maven-publish.sh` 只有一个目录要拉 metadata、验产物、上传。
                url = uri(rootProject.layout.buildDirectory.dir("maven-staging"))
            }
        }
    }
}

// 宿主兼容：编译器 2.4.x，产物按 kotlinLanguage（2.0）出 metadata；POM 里的 stdlib = kotlinStdlib。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.fromVersion(libs.versions.kotlinLanguage.get()))
        apiVersion.set(KotlinVersion.fromVersion(libs.versions.kotlinLanguage.get()))
        allWarningsAsErrors.set(true)
        // 2.4 编译器对 languageVersion 2.0 报「deprecated」—— 这是有意的宿主下限，不是代码警告。
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
    coreLibrariesVersion = libs.versions.kotlinStdlib.get()
}

// 公开面只进不出：显式 API 模式只加在生产 source set 上。
tasks.withType<KotlinCompile>().configureEach {
    if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// 真杀进程测试要起子 JVM：把测试运行时 classpath 与版本号交给单测。
tasks.withType<Test>().configureEach {
    systemProperty("rtv.versionName", project.property("VERSION_NAME") as String)
    doFirst {
        systemProperty("rtv.testClasspath", classpath.asPath)
    }
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "1g"
}

dependencies {
    testImplementation(libs.junit)
}

// metalava：公开 API 基线（照 revenue-dog；`scripts/api-dump.sh` 生成、`scripts/api-check.sh` 门禁）。
// 公开面靠 `-Xexplicit-api=strict` + `internal` 划定，没有 hidden 注解；BuildConfig 已关，不进基线。
metalava {
    filename.set("api/retriever.api")
    arguments.addAll(listOf("--hide", "ReferencesHidden"))
}
