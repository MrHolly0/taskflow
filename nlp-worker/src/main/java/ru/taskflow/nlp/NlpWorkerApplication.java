package ru.taskflow.nlp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "ru.taskflow")
public class NlpWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NlpWorkerApplication.class, args);
    }
}
