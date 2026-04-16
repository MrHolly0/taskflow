plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")

    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:security"))
    implementation(project(":core-service:shared:persistence"))

    implementation(project(":core-service:modules:user:user-impl"))
    implementation(project(":core-service:modules:task:task-impl"))
    implementation(project(":core-service:modules:nlp-gateway:nlp-gateway-impl"))
    implementation(project(":core-service:modules:notify:notify-impl"))
    implementation(project(":core-service:modules:integration-telegram:integration-telegram-impl"))
    implementation(project(":core-service:modules:audit:audit-impl"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
