plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.mongodb:mongodb-driver-sync:5.5.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

kotlin { jvmToolchain(21) }
sourceSets {
    main { kotlin.setSrcDirs(listOf("src")) }
    test { kotlin.setSrcDirs(listOf("tests")) }
}
application { mainClass.set("agent.MainKt") }
tasks.test { useJUnitPlatform() }
