#!/usr/bin/env python3
"""Сводка сравнения планировщиков на аккаунтах со 100 и 200 задачами."""
import argparse
import collections
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path

MODES = ("DIRECT", "BATCH_PLAN", "CODE_MODE")
NAMES = {"DIRECT": "DIRECT", "BATCH_PLAN": "JSON", "CODE_MODE": "Code Mode"}


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def fraction(numerator, denominator):
    return f"{numerator}/{denominator} ({numerator / denominator:.1%})" if denominator else "—"


def summarize(rows):
    totals = {field: sum(row[field] for row in rows) for field in
              ("correct", "expected", "actual", "matched", "dateChecks", "datesCorrect", "reminderChecks",
               "remindersCorrect", "inputTokens", "outputTokens", "httpCalls", "apiFailure")}
    totals.update(count=len(rows), medianMs=percentile([r["elapsedMs"] for r in rows], .5),
                  p95Ms=percentile([r["elapsedMs"] for r in rows], .95),
                  tokensPerRequest=(totals["inputTokens"] + totals["outputTokens"]) / len(rows),
                  noWrites=all(r["noWrites"] for r in rows))
    return totals


def validate(data):
    if data.get("evaluationKind") != "large-context":
        raise ValueError("ожидается набор large-context")
    rows = data["measurements"]
    if data["stoppedByBudget"] or len(rows) != data["plannedMeasurements"] or not rows:
        raise ValueError("неполный прогон")
    modes = [mode for mode in MODES if any(row["mode"] == mode for row in rows)]
    scenario_ids = {request["id"] for request in data["requests"]}
    if len(scenario_ids) != data["scenarios"]:
        raise ValueError("повторяющиеся сценарии")
    expected = set(itertools.product(scenario_ids, data["taskCounts"], range(1, data["repeats"] + 1), modes))
    actual = [(r["scenario"], r["taskCount"], r["repeat"], r["mode"]) for r in rows]
    if len(set(actual)) != len(actual) or set(actual) != expected:
        raise ValueError("режимы получили разные наборы либо есть повторы измерений")
    if sum(r["httpCalls"] for r in rows) != data["httpAttempts"]:
        raise ValueError("счётчик вызовов не совпадает с измерениями")
    return rows, modes


def render(data, raw_sha):
    rows, modes = validate(data)
    columns = " | ".join(NAMES[m] for m in modes)
    divider = "| --- | " + " | ".join("---:" for _ in modes) + " |"
    lines = ["# Планирование при 100 и 200 существующих задачах", "",
             f"Дата привязки относительных сроков: {data['anchorDate']}, Europe/Moscow. Модель: `{data['model']}`.",
             f"{data['scenarios']} сценариев × {len(data['taskCounts'])} размера аккаунта × {len(modes)} режима × "
             f"{data['repeats']} повтора = {len(rows)} измерений. HTTP-вызовов: {data['httpAttempts']}.", "",
             f"Набор: `{data['datasetSha256']}`. Классы планировщиков: `{data['plannerBytecodeSha256']}`.",
             f"SHA-256 исходного отчёта: `{raw_sha}`.", "",
             "Параметры генерации: `" + json.dumps(data["modelParameters"], ensure_ascii=False, sort_keys=True) + "`.", ""]
    for size in sorted(data["taskCounts"]):
        totals = {m: summarize([r for r in rows if r["mode"] == m and r["taskCount"] == size]) for m in modes}
        lines += [f"## Аккаунт с {size} задачами", "", f"| Показатель | {columns} |", divider]
        metrics = [
            ("Полностью правильные запросы", lambda s: fraction(s["correct"], s["count"])),
            ("Точность типов и целей действий", lambda s: fraction(s["matched"], s["actual"])),
            ("Полнота типов и целей действий", lambda s: fraction(s["matched"], s["expected"])),
            ("Дедлайны до минуты", lambda s: fraction(s["datesCorrect"], s["dateChecks"])),
            ("Напоминания до минуты", lambda s: fraction(s["remindersCorrect"], s["reminderChecks"])),
            ("Входных токенов, всего", lambda s: str(s["inputTokens"])),
            ("Выходных токенов, всего", lambda s: str(s["outputTokens"])),
            ("Всех токенов на запрос", lambda s: f"{s['tokensPerRequest']:.0f}"),
            ("Медиана задержки, мс", lambda s: str(s["medianMs"])),
            ("p95 задержки, мс", lambda s: str(s["p95Ms"])),
            ("HTTP-вызовы", lambda s: str(s["httpCalls"])),
            ("Запросы с отказом API", lambda s: str(s["apiFailure"])),
            ("База задач и напоминаний не изменена", lambda s: "да" if s["noWrites"] else "НЕТ"),
        ]
        for label, formatter in metrics:
            lines.append("| " + label + " | " + " | ".join(formatter(totals[m]) for m in modes) + " |")
        lines.append("")
    lines += ["", "## По сценариям", "", f"| Задач / сценарий | {columns} |", divider]
    for size in sorted(data["taskCounts"]):
        for request in data["requests"]:
            cells = []
            for mode in modes:
                selected = [r for r in rows if r["taskCount"] == size and r["scenario"] == request["id"] and r["mode"] == mode]
                cells.append(f"{sum(r['correct'] for r in selected)}/{len(selected)}")
            lines.append(f"| {size} / {request['id']} | " + " | ".join(cells) + " |")
    lines += ["", "## Ошибки", "", "Счётчики ниже считают запросы с каждой категорией; один запрос может иметь несколько ошибок.", "",
              f"| Категория | {columns} |", divider]
    counters = {m: collections.Counter() for m in modes}
    for row in rows:
        counters[row["mode"]].update(set(error.split(":", 1)[0] for error in row["errors"]))
    for error in sorted(set().union(*(set(counters[m]) for m in modes))):
        lines.append(f"| {error} | " + " | ".join(str(counters[m][error]) for m in modes) + " |")
    lines += ["", "## Методика и ограничения", "",
              "В каждом измерении создан отдельный синтетический аккаунт ровно со 100 или 200 активными задачами. "
              "Все исходные задачи находятся в TODO; это проверка длинного активного списка, а не воспроизведение распределения статусов реальных пользователей. "
              "Для каждого режима данные логически одинаковы; UUID различаются. Порядок режимов циклически меняется между сценариями.", "",
              "DIRECT получает исходное окно до 80 задач и свой поиск. Экспериментальные режимы видят до 12 кандидатов "
              "и используют серверный поиск. Проверено, что цели hidden находятся дальше 80-й позиции, а visible — в первых 12. "
              "Есть похожие названия, две неоднозначные задачи, просроченные и будущие задачи одной группы, а также совпадающая задача чужого пользователя.", "",
              "Тип действия и UUID цели проверяются взаимно однозначно. В метриках точности и полноты действий учитываются "
              "только тип и цель; правильные поля проверяются отдельно и обязательны для полного зачёта запроса. "
              "Дедлайн и reminder_at сравниваются по моменту времени с точностью до минуты. Пропуск требуемого действия "
              "считается ошибкой его времени. Лишние действия и непрошенные содержательные изменения полей не допускаются. "
              "Сценарий создания проверяет ключевые слова названий, а не полную семантическую эквивалентность текста.", "",
              "Два контрольных сценария требуют уточнения без действий: неоднозначное название и выборка 21 задачи при лимите 20. "
              "Они входят в полную точность запросов; ожидаемых действий и временных полей в них нет. Технический сбой "
              "не засчитывается как успешное уточнение. Список действий свыше лимита нельзя принимать частично.", "",
              "Проверяется фаза формирования предложений. Применение, доставка уведомлений и распознавание аудио не выполняются. "
              "Снимки строк задач и scheduled_notifications основного и чужого аккаунтов сверены до и после планирования. "
              "Совпадение снимков подтверждает отсутствие записей при планировании, но не проверяет последующее применение предложений.", "",
              "Задержка включает чтение контекста, обращения к модели, исправления и песочницу; подготовка аккаунта "
              "и паузы между измерениями исключены. Отказы API остаются в знаменателях. В токены включён возвращённый usage "
              "всех попыток; расход отклонённых поставщиком обращений может быть неизвестен.", "",
              "Это последовательное функциональное сравнение, а не нагрузочный тест ста пользователей. "
              "Три повтора одной формулировки не равны трём независимым пользовательским запросам. "
              "Набор небольшой и намеренно содержит трудные случаи. Проценты нельзя напрямую сопоставлять с прежними "
              "150 репликами: различаются набор, состояние аккаунтов и более строгая проверка полей. "
              "Сравниваются реализации целиком, включая инструкции и настройки рассуждений; влияние формата отдельно не выделено.", "",
              "## Формулировки сценариев", ""]
    for request in data["requests"]:
        lines.append(f"- **{request['id']}** ({request['control']}): {request['request']}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--csv", type=Path)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    data = json.loads(raw)
    try:
        text = render(data, hashlib.sha256(raw).hexdigest())
    except ValueError as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text)
    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        fields = [field for field in data["measurements"][0] if field != "actions"]
        with args.csv.open("w", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            for row in data["measurements"]:
                writer.writerow({field: json.dumps(row[field], ensure_ascii=False, sort_keys=True)
                                 if isinstance(row[field], (list, dict)) else row[field] for field in fields})
    print(args.output)


if __name__ == "__main__":
    main()
