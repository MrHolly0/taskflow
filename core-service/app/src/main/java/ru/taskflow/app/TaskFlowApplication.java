package ru.taskflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "ru.taskflow")
@EntityScan(basePackages = "ru.taskflow")
@EnableJpaRepositories(basePackages = "ru.taskflow")
public class TaskFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskFlowApplication.class, args);
    }
}
