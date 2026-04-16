plugins {
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

subprojects {
    apply(plugin = "java")

    group = "ru.taskflow"
    version = "0.0.1"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    val springBom = "org.springframework.boot:spring-boot-dependencies:3.3.5"
    dependencies {
        "implementation"(platform(springBom))
        "annotationProcessor"(platform(springBom))
        "testImplementation"(platform(springBom))
        "testAnnotationProcessor"(platform(springBom))
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
