package ru.taskflow.assistant.application.planning;

public class PlanningException extends RuntimeException {
    private final String question;
    public PlanningException(String code) { this(code, "Не удалось подготовить изменения. Попробуй ещё раз или добавь задачу вручную."); }
    public PlanningException(String code, String question) { super(code); this.question = question; }
    public String question() { return question; }
}
