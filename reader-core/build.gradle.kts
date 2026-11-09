import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
    signing
}

group = "com.readerengine"
version = "0.3.1"

kotlin {
    explicitApi()

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcf = XCFramework("ReaderCore")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ReaderCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.okio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

android {
    namespace = "com.readerengine.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val publicMavenDir = rootProject.layout.projectDirectory.dir("public-maven")

publishing {
    repositories {
        maven {
            name = "PublicMaven"
            url = publicMavenDir.asFile.toURI()
        }
        maven {
            name = "SonatypeCentral"
            val releasesRepoUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
            val snapshotsRepoUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = providers.environmentVariable("SONATYPE_TOKEN_USER")
                    .orElse(providers.gradleProperty("sonatypeTokenUser"))
                    .orNull
                password = providers.environmentVariable("SONATYPE_TOKEN_PASSWORD")
                    .orElse(providers.gradleProperty("sonatypeTokenPassword"))
                    .orNull
            }
        }
    }
    publications.withType<MavenPublication> {
        pom {
            name.set("reader-core")
            description.set("Universal headless reader engine for EPUB and HPUB in Kotlin Multiplatform")
            url.set("https://github.com/andreAlvesCarvalhojr/reader-engine")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("andreAlvesCarvalhojr")
                    name.set("André Alves Carvalho Jr.")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/andreAlvesCarvalhojr/reader-engine.git")
                developerConnection.set("scm:git:ssh://github.com:andreAlvesCarvalhojr/reader-engine.git")
                url.set("https://github.com/andreAlvesCarvalhojr/reader-engine")
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
