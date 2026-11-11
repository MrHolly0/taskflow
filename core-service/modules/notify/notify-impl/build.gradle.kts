dependencies {
    implementation(project(":core-service:modules:notify:notify-api"))
    implementation(project(":core-service:modules:user:user-api"))
    implementation(project(":core-service:modules:task:task-api"))
    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:persistence"))
    implementation(project(":core-service:shared:security"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
