dependencies {
    implementation(project(":core-service:modules:nlp-gateway:nlp-gateway-api"))
    implementation(project(":core-service:shared:common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.retry:spring-retry")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
