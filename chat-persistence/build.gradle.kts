plugins {
    `java-library`
}

dependencies {
    api(project(":chat-domain"))
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.data.jpa)
    implementation(libs.querydsl.jpa)

    compileOnly(libs.lombok)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.lombok)
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor(libs.jakarta.persistence.api)
}
