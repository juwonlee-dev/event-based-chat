plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":chat-domain"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.kafka)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
}

tasks.named<Jar>("jar") {
    enabled = false
}
