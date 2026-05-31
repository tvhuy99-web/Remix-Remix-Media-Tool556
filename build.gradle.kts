tasks.register("checkAar") {
    doLast {
        val zipFile = java.util.zip.ZipFile(file("app/libs/ffmpeg-kit-full-6.0-2.LTS.aar"))
        val entries = zipFile.entries()
        while(entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if(entry.name.startsWith("jni/")) {
                println("Found architecture: " + entry.name)
            }
        }
    }
}
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
}
