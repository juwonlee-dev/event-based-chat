plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.data.jpa)

    compileOnly(libs.lombok)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.lombok)
}
