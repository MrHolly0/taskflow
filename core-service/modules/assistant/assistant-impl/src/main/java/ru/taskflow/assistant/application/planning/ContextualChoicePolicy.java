package ru.taskflow.assistant.application.planning;

import java.util.*;
import java.util.regex.Pattern;

final class ContextualChoicePolicy {
    private static final Set<String> EXPLICIT = Set.of("создай","добавь","запиши","нужно","надо","хочу","новая","новое",
            "отмени","удали","перенеси","перенести","сдвинь","закрой","заверши","отметь","убери","измени","изменить","поменяй");
    private static final Set<String> IGNORE = Set.of("задача","задачу","задачи","про","в","на","к","по","с","со","мне","пожалуйста","позвони","сделай");
    private static final Pattern FACT = Pattern.compile("(?U)^(готов[аоы]?|сделал[аи]?|выполнил[аи]?|завершил[аи]?|купил[аи]?|позвонил[аи]?|забрал[аи]?|отн[её]с(ла|ли)?|отв[её]з(ла|ли)?|проверил[аи]?|сходил[аи]?|полил[аи]?|отменил[аи]?|помыл[аи]?|оплатил[аи]?|отправил[аи]?|приготовил[аи]?|написал[аи]?|сдал[аи]?|подписал[аи]?)$");
    private ContextualChoicePolicy() {}

    static String topic(String text) {
        List<String> words = words(text);
        if (words.isEmpty() || words.size() > 4 || EXPLICIT.contains(words.getFirst()) || text.matches("(?s).*\\d.*")) return null;
        for (String word : words)
            if (Set.of("я","мы","не","нет","никогда","сегодня","завтра","завтро","послезавтра","утром","вечером","через","приоритет","группу","группа","название","описание").contains(word)
                    || FACT.matcher(word).matches()) return null;
        var object = words.stream().filter(w -> !IGNORE.contains(w) && !w.endsWith("ть") && !w.endsWith("ти") && !w.endsWith("чь")).toList();
        return object.size() == 1 && object.getFirst().length() >= 3 ? stem(object.getFirst()) : null;
    }

    static boolean matches(String topic, String title) {
        String normalized = topic.replace('ё','е');
        return words(title).stream().map(ContextualChoicePolicy::stem).map(w -> w.replace('ё','е')).anyMatch(normalized::equals);
    }

    static boolean sameTitle(String source, String title) { return words(source).equals(words(title)); }
    static boolean cancel(String source) { return words(source).getFirst().equals("убрать"); }
    private static String stem(String word) { return word.length() >= 5 && "аяуюоыеи".indexOf(word.charAt(word.length()-1)) >= 0 ? word.substring(0,word.length()-1) : word; }
    private static List<String> words(String text) { return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+" )).filter(w -> !w.isBlank()).toList(); }
}
