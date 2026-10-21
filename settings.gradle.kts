rootProject.name = "event-based-chat"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("chat-domain", "chat-api")
