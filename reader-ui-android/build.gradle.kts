import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
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
    ?: "1.3.0"
version = resolvedVersion

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.readerengine.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":reader-core"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.webkit)
    testImplementation(kotlin("test-junit"))
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
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "reader-ui-android"
                pom {
                    name.set("reader-ui-android")
                    description.set("Optional Jetpack Compose, WebView and PDF UI components for reader-core")
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
    }

    signing {
        val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
        val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
        if (!signingKey.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications)
        }
    }
}
