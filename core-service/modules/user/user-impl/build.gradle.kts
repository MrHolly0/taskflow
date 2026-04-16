dependencies {
    implementation(project(":core-service:modules:user:user-api"))
    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:persistence"))
    implementation(project(":core-service:shared:security"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}