package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.regex.Pattern;

final class CreateDetailsPolicy {
    private static final Pattern ROOM=Pattern.compile("(?U)\\b(?:аудитори[ияюе]|ауд\\.?|кабинет[ае]?|офис[ае]?|корпус[ае]?|зал[ае]?)\\s*№?\\s*(\\d{1,5}[а-яa-z]?)\\b");
    private static final Pattern ADDRESS=Pattern.compile("(?U)\\b(?:на|по)\\s+([а-яё-]{3,}(?:ской|цкой|ной|овой|евой))\\s*,?\\s*№?\\s*(\\d{1,4}[а-яa-z]?)\\b(?![:.])");
    private static final Pattern EVENT=Pattern.compile("(?U)\\b(?:встреч\\p{L}*|консультаци\\p{L}*|лекци\\p{L}*|семинар\\p{L}*|экзамен\\p{L}*|собеседовани\\p{L}*|совещани\\p{L}*|заседани\\p{L}*)\\b");
    private CreateDetailsPolicy() {}

    static void validate(JsonNode action,String source) {
        if(!"create".equals(action.path("type").asText())) return;
        String details=(EVENT.matcher(source).find()?action.path("description").asText():
                action.path("title").asText()+" "+action.path("description").asText()).toLowerCase(Locale.ROOT);
        var rooms=ROOM.matcher(source);
        while(rooms.find()) {
            String number=rooms.group(1);
            if(!containsNumber(details,number))
                throw new PlanningException("INVALID_ACTION","Не теряй место встречи: сохрани в description явно названные адрес и «"+rooms.group()+"». Не добавляй отсутствующие подробности.");
        }
        var addresses=ADDRESS.matcher(source);
        while(addresses.find()) {
            String name=addresses.group(1);String stem=name.substring(0,name.length()-2);
            if(!details.contains(stem)||!containsNumber(details,addresses.group(2)))
                throw new PlanningException("INVALID_ACTION","В description пропущен явно названный адрес «"+addresses.group()+"». Сохрани адрес вместе с остальными деталями, не выдумывая новых.");
        }
    }
    private static boolean containsNumber(String details,String number) {
        return Pattern.compile("(?U)(?<![\\p{L}\\p{N}])"+Pattern.quote(number)+"(?![\\p{L}\\p{N}])").matcher(details).find();
    }
}
