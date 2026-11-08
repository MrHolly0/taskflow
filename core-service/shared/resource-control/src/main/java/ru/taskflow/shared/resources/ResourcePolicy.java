package ru.taskflow.shared.resources;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.resources")
public class ResourcePolicy {
    private long operationTimeoutMs = 35_000;
    private long externalTimeoutMs = 12_000;
    private long leaseMs = 60_000;
    private int globalConcurrent = 4;
    private int userConcurrent = 1;
    private int workerConcurrent = 4;
    private int maxTextChars = 8_000;
    private int maxInputBytes = 64_000;
    private int maxOutputTokens = 3_000;
    private int maxAudioSeconds = 120;
    private int minBillableAudioSeconds = 10;
    private long userDailyTokens = 250_000;
    private long globalDailyTokens = 5_000_000;
    private long userDailyAudioSeconds = 600;
    private long globalDailyAudioSeconds = 7_200;
    private String internalSecret = "";
    @jakarta.annotation.PostConstruct
    public void validate() {
        if(operationTimeoutMs<1000 || operationTimeoutMs>90_000 || externalTimeoutMs<1 || externalTimeoutMs>30_000
                || leaseMs<=operationTimeoutMs+externalTimeoutMs || globalConcurrent<1 || userConcurrent<1
                || workerConcurrent<1 || maxTextChars<1 || maxInputBytes<1 || maxOutputTokens<1
                || maxAudioSeconds<1 || maxAudioSeconds>600 || minBillableAudioSeconds<10 || userDailyTokens<1 || globalDailyTokens<1
                || userDailyAudioSeconds<1 || globalDailyAudioSeconds<1)
            throw new IllegalArgumentException("Некорректные ограничения ресурсов");
    }
}
