package ru.taskflow.shared.engagement;
import java.util.UUID;
public final class CompletionFeedback {
    private static final String[] MESSAGES={"Одним делом меньше!","Отлично, дело закрыто!","Готово, можно выдохнуть!"};
    private CompletionFeedback() {}
    public static String forOperation(UUID id) {return MESSAGES[Character.digit(id.toString().charAt(35),16)%MESSAGES.length];}
}
