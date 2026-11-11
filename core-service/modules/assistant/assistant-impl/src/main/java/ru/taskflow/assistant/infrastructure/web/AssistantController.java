package ru.taskflow.assistant.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.application.QuickAddPolicy;
import ru.taskflow.assistant.infrastructure.web.dto.QuickResult;
import ru.taskflow.assistant.infrastructure.web.dto.SetActionAcceptedRequest;
import ru.taskflow.assistant.infrastructure.web.dto.UpdateActionPlannedDateRequest;
import ru.taskflow.assistant.infrastructure.web.dto.UpdateActionReminderRequest;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.io.IOException;
import java.util.UUID;

/**
 * Точки входа ассистента. NotFoundException ловится здесь же, локальным
 * @ExceptionHandler, а не общим GlobalExceptionHandler из core-service:app —
 * этот модуль не зависит от app (composition root), а тесты контроллера
 * поднимают его отдельным MockMvc без Spring-контекста.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
@Tag(name = "Assistant", description = "Диалоговый ассистент: разбор обращений и применение предложений")
public class AssistantController {

    private final AssistantService assistantService;
    private final ru.taskflow.task.api.TaskOperationService operations;

    @PostMapping(value = "/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Обратиться к ассистенту", description = "Текст или аудио -> предложение")
    public Proposal handleMessage(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey
    ) throws IOException {
        return resolveProposal(user.userId(), text, file, AssistantEntryPoint.CHAT, requestKey);
    }

    @PostMapping(value = "/quick", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Быстрое добавление", description = """
            Текст или аудио -> создание новых задач. Остальные изменения возвращаются на подтверждение.""")
    public QuickResult quick(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey
    ) throws IOException {
        Proposal proposal = resolveProposal(user.userId(), text, file, AssistantEntryPoint.QUICK_ADD, requestKey);
        if (proposal.status() == ru.taskflow.assistant.api.ProposalStatus.PENDING
                && proposal.hasActions() && !proposal.hasClarification() && !proposal.hasRejections()
                && !proposal.exclusive() && (proposal.ambiguityReason() == null || proposal.ambiguityReason().isBlank())
                && proposal.actions().stream().allMatch(action -> action.accepted()
                    && action.type() == ru.taskflow.assistant.api.AssistantActionType.CREATE))
            return new QuickResult(proposal,assistantService.apply(user.userId(),proposal.id()));
        return new QuickResult(proposal,null);
    }

    @PostMapping(value="/capture",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public QuickResult capture(@RequestParam(value="text",required=false) String text,
            @RequestParam(value="file",required=false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value="Idempotency-Key",required=false) String requestKey) throws IOException {
        return resolveAutomatically(user.userId(),resolveProposal(user.userId(),text,file,AssistantEntryPoint.QUICK_ADD,requestKey));
    }

    @PostMapping(value="/turn",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public QuickResult turn(@RequestParam(value="text",required=false) String text,
            @RequestParam(value="file",required=false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value="Idempotency-Key",required=false) String requestKey) throws IOException {
        return resolveAutomatically(user.userId(),resolveProposal(user.userId(),text,file,AssistantEntryPoint.CHAT,requestKey));
    }
    private QuickResult resolveAutomatically(UUID user,Proposal proposal) {
        if(proposal.id()==null) return new QuickResult(proposal,null);
        var result=assistantService.resolveAutomatically(user,proposal.id());
        return new QuickResult(result.proposal(),result.applied());
    }
    @PostMapping("/operations/{id}/undo")
    public ru.taskflow.task.api.dto.TaskUndoResult undo(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser user) {
        return operations.undo(user.userId(),id);
    }

    // Файл в приоритете над текстом, если пришли оба — клиент не должен
    // присылать оба поля одновременно, но если пришлёт, голос не теряем молча.
    private Proposal resolveProposal(UUID userId, String text, MultipartFile file, AssistantEntryPoint entryPoint, String requestKey)
            throws IOException {
        if (file != null && !file.isEmpty()) {
            if(file.getSize()>16_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            return assistantService.handleVoiceUpload(userId, () -> {
                try { return file.getBytes(); }
                catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            }, AssistantChannel.WEB, entryPoint, requestKey);
        }
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "нужен текст или аудио");
        }
        return requestKey == null ? assistantService.handleText(userId, text, AssistantChannel.WEB, entryPoint)
                : assistantService.handleText(userId, text, AssistantChannel.WEB, entryPoint, requestKey);
    }

    @GetMapping("/proposals/{id}")
    @Operation(summary = "Получить предложение")
    public Proposal findById(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return assistantService.findById(user.userId(), id);
    }

    @PatchMapping("/proposals/{id}/actions/{ordinal}")
    @Operation(summary = "Принять или отклонить отдельное действие предложения")
    public Proposal setActionAccepted(
            @PathVariable UUID id,
            @PathVariable int ordinal,
            @RequestBody @Valid SetActionAcceptedRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return assistantService.setActionAccepted(user.userId(), id, ordinal, request.accepted());
    }

    @PatchMapping("/proposals/{id}/actions/{ordinal}/reminder")
    @Operation(summary = "Изменить или снять напоминание у отдельного действия предложения",
            description = "reminderAt=null снимает напоминание — правка прямо в карточке подтверждения, " +
                    "без отдельного захода в карточку задачи")
    public Proposal updateActionReminder(
            @PathVariable UUID id,
            @PathVariable int ordinal,
            @RequestBody UpdateActionReminderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return assistantService.updateActionReminder(user.userId(), id, ordinal, request.reminderAt());
    }

    @PatchMapping("/proposals/{id}/actions/{ordinal}/planned-date")
    @Operation(summary = "Изменить или снять день исполнения у отдельного действия предложения",
            description = "plannedDate=null снимает предложенный день — правка прямо в карточке подтверждения, " +
                    "без отдельного захода в карточку задачи")
    public Proposal updateActionPlannedDate(
            @PathVariable UUID id,
            @PathVariable int ordinal,
            @RequestBody UpdateActionPlannedDateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return assistantService.updateActionPlannedDate(user.userId(), id, ordinal, request.plannedDate());
    }

    @PostMapping("/proposals/{id}/actions/{ordinal}/select")
    @Operation(summary = "Выбрать один вариант среди взаимоисключающих альтернатив",
            description = "Принимает указанное действие, отклоняет остальные действия предложения")
    public Proposal selectAlternative(
            @PathVariable UUID id,
            @PathVariable int ordinal,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return assistantService.selectAlternative(user.userId(), id, ordinal);
    }

    @PostMapping("/proposals/{id}/apply")
    @Operation(summary = "Применить предложение")
    public ApplyResult apply(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return assistantService.apply(user.userId(), id);
    }

    @PostMapping("/proposals/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отклонить предложение целиком")
    public void reject(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        assistantService.reject(user.userId(), id);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
