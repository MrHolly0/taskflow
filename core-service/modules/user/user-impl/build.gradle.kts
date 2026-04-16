dependencies {
    implementation(project(":core-service:modules:user:user-api"))
    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:persistence"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
