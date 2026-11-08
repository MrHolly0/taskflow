package ru.taskflow.assistant.application.planning;

import java.util.*;
import java.util.regex.Pattern;

final class RelativeDayPolicy {
    private static final Pattern DAYS = Pattern.compile("(?U)\\b(послезавтра|позавчера|сегодня|завтра|завтро|вчера)\\b|\\bчерез\\s+([0-9]{1,4}|ноль|один|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать|тридцать)\\s*(день|дня|дней|неделю|недели|недель)\\b");
    private static final Pattern THROUGH = Pattern.compile("(?U)\\bчерез\\b");
    private static final Pattern OTHER_DATE = Pattern.compile("(?U)\\b(?:не\\s+(?:на\\s+)?(?:сегодня|завтра|послезавтра|вчера|через)|понедель\\p{L}*|вторник\\p{L}*|сред[ауеы]|четверг\\p{L}*|пятниц\\p{L}*|суббот\\p{L}*|воскресен\\p{L}*|январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|ма[йяе]|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*|числа|число)\\b|[0-9][./-][0-9]");
    private static final Map<String,Integer> NUMBERS;
    static {
        String[] words={"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять","десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать","семнадцать","восемнадцать","девятнадцать","двадцать"};
        var values=new HashMap<String,Integer>();for(int i=0;i<words.length;i++)values.put(words[i],i);
        values.put("одну",1);values.put("две",2);values.put("тридцать",30);NUMBERS=Map.copyOf(values);
    }
    private RelativeDayPolicy() {}
    static OptionalInt uniqueOffset(String text) {
        if (OTHER_DATE.matcher(text).find()) return OptionalInt.empty();
        var offsets=new HashSet<Integer>();int through=0;var match=DAYS.matcher(text);
        while(match.find()) {
            if(match.group(1)!=null) offsets.add(switch(match.group(1)) {
                case "позавчера" -> -2;case "вчера" -> -1;case "сегодня" -> 0;
                case "послезавтра" -> 2;default -> 1;
            });
            else {
                int value=NUMBERS.containsKey(match.group(2)) ? NUMBERS.get(match.group(2)) : Integer.parseInt(match.group(2));
                if(match.group(3).startsWith("недел"))value*=7;
                if(value>3650)return OptionalInt.empty();offsets.add(value);through++;
            }
        }
        if(THROUGH.matcher(text).results().count()!=through || offsets.size()!=1)return OptionalInt.empty();
        return OptionalInt.of(offsets.iterator().next());
    }
}
