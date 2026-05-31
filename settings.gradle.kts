import java.io.File
import java.io.FileOutputStream
import java.net.URL

val targetDir = File(rootDir, "app/libs")
targetDir.mkdirs()
val targetFile = File(targetDir, "ffmpeg-kit-full-6.0-2.LTS.aar")
if (!targetFile.exists()) {
    println("Downloading FFMPEG Kit AAR from Gitlab...")
    URL("https://gitlab.futo.org/videostreaming/grayjay/-/raw/simplify-quality-language-options/app/aar/ffmpeg-kit-full-6.0-2.LTS.aar").openStream().use { input ->
        FileOutputStream(targetFile).use { output ->
            input.copyTo(output)
        }
    }
}

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "My Application"

include(":app")


