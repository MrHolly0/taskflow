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

tasks.register("aggregateJavadoc") {
    description = "Aggregate JavaDoc from all modules into a single documentation"
    group = "documentation"

    dependsOn(subprojects.mapNotNull { it.tasks.findByName("javadoc") })

    doLast {
        val outputDir = file("build/docs/javadoc")
        outputDir.mkdirs()

        val modules = subprojects
            .filter { it.name !in listOf("miniapp", "website") }
            .mapNotNull { project ->
                val docFile = file("${project.buildDir}/docs/javadoc/index.html")
                if (docFile.exists()) {
                    project.name to docFile
                } else null
            }

        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html>")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<title>TaskFlow API Documentation</title>")
            appendLine("<style>")
            appendLine("body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }")
            appendLine("h1 { color: #333; }")
            appendLine(".module { background: white; padding: 15px; margin: 10px 0; border-radius: 5px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }")
            appendLine(".module a { color: #0066cc; text-decoration: none; font-weight: bold; }")
            appendLine(".module a:hover { text-decoration: underline; }")
            appendLine(".description { color: #666; font-size: 14px; margin-top: 5px; }")
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<h1>TaskFlow JavaDoc Documentation</h1>")
            appendLine("<p>Документация API и сервисов проекта TaskFlow</p>")

            appendLine("<h2>Core Services</h2>")
            val coreModules = listOf("task-impl", "user-impl", "integration-telegram-impl", "nlp-gateway-impl", "notify-impl", "audit-impl", "security", "common")
            modules.filter { (name, _) -> coreModules.any { name.contains(it) } }.forEach { (name, docFile) ->
                val displayName = name.replace("-impl", "").replace("-", " ").replaceFirstChar { it.uppercase() }
                val relativePath = file("build/docs/javadoc").toPath().relativize(docFile.toPath()).toString()
                appendLine("<div class=\"module\">")
                appendLine("<a href=\"$relativePath\">$displayName</a>")
                appendLine("<div class=\"description\">JavaDoc для модуля $displayName</div>")
                appendLine("</div>")
            }

            appendLine("<h2>Worker Services</h2>")
            modules.filter { (name, _) -> name.contains("worker") }.forEach { (name, docFile) ->
                val displayName = name.replace("-", " ").replaceFirstChar { it.uppercase() }
                val relativePath = file("build/docs/javadoc").toPath().relativize(docFile.toPath()).toString()
                appendLine("<div class=\"module\">")
                appendLine("<a href=\"$relativePath\">$displayName</a>")
                appendLine("<div class=\"description\">JavaDoc для сервиса $displayName</div>")
                appendLine("</div>")
            }

            appendLine("</body>")
            appendLine("</html>")
        }

        file("$outputDir/index.html").writeText(html)
        println("✅ JavaDoc aggregated to: ${file("$outputDir/index.html").absolutePath}")
    }
}
