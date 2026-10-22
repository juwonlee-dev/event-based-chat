plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":chat-persistence"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.datasource.micrometer.spring.boot)
    implementation(libs.springdoc.openapi.webmvc.ui)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    compileOnly(libs.lombok)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.lombok)
}

tasks.named<Jar>("jar") {
    enabled = false
}

dependencies {
    testImplementation(libs.spring.boot.starter.test)
}

// 통합 테스트(Testcontainers: PostgreSQL·Redis·Kafka)와 클러스터 테스트(이미 띄운 compose 클러스터 대상)는
// Docker가 필요하므로 build에 넣지 않고 따로 실행한다. 실행 방법은 docs/testing.md.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            sources {
                java { srcDir("src/testSupport/java") }
                // 앱을 그대로 띄우므로 main 클래스가 필요하다(plain jar는 꺼 두어서 project() 의존으로는 안 된다).
                compileClasspath += sourceSets.main.get().output
                runtimeClasspath += sourceSets.main.get().output
            }
            dependencies {
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.testcontainers.junit.jupiter)
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.kafka)
                implementation(libs.awaitility)
                implementation(libs.okhttp)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    // 저장소 루트(docker/ 설정 파일을 컨테이너에 그대로 쓴다)
                    systemProperty("repo.root", rootProject.projectDir.absolutePath)
                }
            }
        }
        register<JvmTestSuite>("clusterTest") {
            useJUnitJupiter()
            sources { java { srcDir("src/testSupport/java") } }
            dependencies {
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.awaitility)
                implementation(libs.okhttp)
            }
            targets.all {
                testTask.configure {
                    // 대상과 고를 시험은 -P로 넘긴다(scripts/test/cluster.sh). 기본은 태그 없는 시험(노드 간 전달)만 돈다.
                    // fault: 앱 강제 종료·일시 정지, obs: Tempo 트레이스(관측 스택 필요)
                    fun prop(name: String, default: String) = providers.gradleProperty(name).getOrElse(default)
                    useJUnitPlatform { includeTags(prop("cluster.tags", "none()")) }
                    systemProperty("cluster.baseUrl", prop("cluster.baseUrl", "http://127.0.0.1:8000"))
                    systemProperty("cluster.project", prop("cluster.project", "event-based-chat"))
                    systemProperty("cluster.grafanaUrl", prop("cluster.grafanaUrl", "http://127.0.0.1:3000"))
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}

// 통합·클러스터 테스트도 앱의 의존성(Spring MVC, WebSocket, JPA 등)을 그대로 쓴다.
listOf("integrationTest", "clusterTest").forEach { suite ->
    configurations.named("${suite}Implementation") { extendsFrom(configurations.implementation.get()) }
    configurations.named("${suite}RuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }
}
