dependencies {
    implementation(project(":core-service:modules:notify:notify-api"))
    implementation(project(":core-service:modules:user:user-impl"))
    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:persistence"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
