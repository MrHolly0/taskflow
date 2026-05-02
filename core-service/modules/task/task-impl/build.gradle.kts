dependencies {
    implementation(project(":core-service:modules:task:task-api"))
    implementation(project(":core-service:modules:notify:notify-api"))
    implementation(project(":core-service:modules:nlp-gateway:nlp-gateway-api"))
    implementation(project(":core-service:modules:audit:audit-api"))
    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:persistence"))
    implementation(project(":core-service:shared:security"))
    implementation("org.springframework.boot:spring-boot-starter-security")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}
