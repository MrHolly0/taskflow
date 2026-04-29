package ru.taskflow.nlp.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.taskflow.nlp.application.NlpService;
import ru.taskflow.nlp.domain.ParsedTasks;
import ru.taskflow.nlp.infrastructure.web.dto.ParseTextRequest;
import ru.taskflow.nlp.infrastructure.web.dto.ParseTextResponse;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/nlp")
@RequiredArgsConstructor
public class NlpController {

    private final NlpService nlpService;

    @PostMapping("/parse-text")
    public ParseTextResponse parseText(@RequestBody ParseTextRequest request) {
        ParsedTasks result = nlpService.parseText(
            request.text(),
            request.userTimezone(),
            request.userLanguage(),
            request.existingGroups()
        );
        return new ParseTextResponse(result.tasks());
    }

    @PostMapping(value = "/parse-voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ParseTextResponse parseVoice(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "userTimezone", defaultValue = "Europe/Moscow") String userTimezone,
        @RequestParam(value = "userLanguage", defaultValue = "ru") String userLanguage,
        @RequestParam(value = "existingGroups", required = false) List<String> existingGroups
    ) throws IOException {
        ParsedTasks result = nlpService.parseVoice(
            file.getBytes(),
            userTimezone,
            userLanguage,
            existingGroups
        );
        return new ParseTextResponse(result.tasks());
    }
}
