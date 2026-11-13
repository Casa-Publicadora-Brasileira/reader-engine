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
val resolvedVersion = providers.gradleProperty("version")
    .orElse(providers.environmentVariable("VERSION"))
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("GITHUB_REF_NAME")
        .orNull
        ?.takeIf { it.startsWith("v") && it.length > 1 }
        ?.removePrefix("v")
    ?: "0.3.2"
version = resolvedVersion

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

val serverUrl = providers.environmentVariable("GITHUB_SERVER_URL")
    .orElse(providers.gradleProperty("githubServerUrl"))
    .getOrElse("https://github.com")
    .trimEnd('/')

val repoSlug = providers.environmentVariable("GITHUB_REPOSITORY")
    .orElse(providers.gradleProperty("githubRepository"))
    .getOrElse("Casa-Publicadora-Brasileira/reader-engine")
    .trim('/')

val serverUri = uri(serverUrl)
val serverHost = serverUri.host ?: "github.com"
val repoHttpUrl = "$serverUrl/$repoSlug"

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
        maven {
            name = "GitHubPackages"
            val customUrl = providers.environmentVariable("GITHUB_PACKAGES_URL")
                .orElse(providers.environmentVariable("GPR_URL"))
                .orElse(providers.gradleProperty("githubPackagesUrl"))
                .orElse(providers.gradleProperty("gpr.url"))
                .orNull

            url = if (!customUrl.isNullOrBlank()) {
                uri(customUrl)
            } else if (serverHost.equals("github.com", ignoreCase = true)) {
                uri("https://maven.pkg.github.com/$repoSlug")
            } else {
                // Suporte ao GitHub Enterprise Server com isolamento de subdomínio:
                // ex: https://maven.ghe.empresa.com/org/repo
                uri("https://maven.$serverHost/$repoSlug")
            }

            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR")
                    .orElse(providers.environmentVariable("GITHUB_USERNAME"))
                    .orElse(providers.gradleProperty("gpr.user"))
                    .orElse(providers.gradleProperty("githubActor"))
                    .orNull
                password = providers.environmentVariable("GITHUB_TOKEN")
                    .orElse(providers.environmentVariable("GH_TOKEN"))
                    .orElse(providers.gradleProperty("gpr.key"))
                    .orElse(providers.gradleProperty("githubToken"))
                    .orNull
            }
        }
    }
    publications.withType<MavenPublication> {
        pom {
            name.set("reader-core")
            description.set("Universal headless reader engine for EPUB and HPUB in Kotlin Multiplatform")
            url.set(repoHttpUrl)
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("Casa-Publicadora-Brasileira")
                    name.set("Casa Publicadora Brasileira")
                }
            }
            scm {
                connection.set("scm:git:git://$serverHost/$repoSlug.git")
                developerConnection.set("scm:git:ssh://git@$serverHost:$repoSlug.git")
                url.set(repoHttpUrl)
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
