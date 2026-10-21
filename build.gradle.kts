plugins {
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    apply(plugin = "java")

    group = "com.eventchat"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}
