plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.liquibase:liquibase-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
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
    implementation(project(":core-service:modules:assistant:assistant-impl"))

    // AccountTransferService/IdentityController живут в app (единственное
    // место, которое видит все модули сразу — перенос между учётками задевает
    // их все) и обращаются к сервисным интерфейсам напрямую, а не только
    // через impl — implementation-зависимости impl-модулей на свои api не
    // протекают транзитивно, нужны собственные ссылки.
    implementation(project(":core-service:modules:user:user-api"))
    implementation(project(":core-service:modules:task:task-api"))
    implementation(project(":core-service:modules:notify:notify-api"))
    implementation(project(":core-service:modules:audit:audit-api"))
    implementation(project(":core-service:modules:assistant:assistant-api"))
    // Только для сквозных тестов (Б5/Б6 "прокрутка повторов, сквозные сценарии") —
    // подмена NlpGatewayService через @MockBean, не для основного кода app.
    testImplementation(project(":core-service:modules:nlp-gateway:nlp-gateway-api"))

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live", "load")
    }
}

// стоит денег и требует настоящего GROQ_API_KEY + работающего nlp-worker —
// не часть обычной сборки, запускается вручную: ./gradlew :core-service:app:liveTest
tasks.register<Test>("liveTest") {
    description = "Регрессия на живой модели (тег live)"
    outputs.upToDateWhen { false }
    group = "verification"
    useJUnitPlatform {
        includeTags("live")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging.showStandardStreams = true
}

tasks.register<Test>("loadTest") {
    description = "Изолированный HTTP-прогон с сотней пользователей"
    group = "verification"
    useJUnitPlatform { includeTags("load") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging.showStandardStreams = true
}
