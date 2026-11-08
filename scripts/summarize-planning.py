#!/usr/bin/env python3
"""Сводка завершённого сравнения без новых обращений к модели."""
import argparse
import csv
import json
import math
from pathlib import Path

NAMES = {"DIRECT": "Текущий DIRECT", "BATCH_PLAN": "JSON-планы", "CODE_MODE": "Code Mode"}
ORDER = tuple(NAMES)

def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]

def summarize(rows, input_price, output_price):
    expected = sum(row["expected"] for row in rows)
    actual = sum(row["actual"] for row in rows)
    matched = sum(row["matched"] for row in rows)
    correct = sum(row["correct"] for row in rows)
    inputs = sum(row["inputTokens"] for row in rows)
    outputs = sum(row["outputTokens"] for row in rows)
    cost = (inputs * input_price + outputs * output_price) / 1_000_000
    return dict(rows=len(rows), correct=correct, fullAccuracy=correct / len(rows),
                legacyAccuracy=sum(row["legacyCorrect"] for row in rows) / len(rows),
                precision=matched / actual if actual else None,
                recall=matched / expected if expected else None,
                inputTokens=inputs, outputTokens=outputs,
                tokensPerRequest=(inputs + outputs) / len(rows),
                medianMs=percentile([row["elapsedMs"] for row in rows], .5),
                p95Ms=percentile([row["elapsedMs"] for row in rows], .95),
                avgMs=sum(row["elapsedMs"] for row in rows) / len(rows),
                apiFailures=sum(row["apiFailure"] for row in rows),
                httpCalls=sum(row["httpCalls"] for row in rows),
                estimatedCostUsd=cost, estimatedCostPer1000Usd=cost * 1000 / len(rows),
                estimatedCostPerCorrectUsd=cost / correct if correct else None)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--csv", type=Path)
    parser.add_argument("--input-price", type=float, default=.15, help="USD за миллион входных токенов")
    parser.add_argument("--output-price", type=float, default=.60, help="USD за миллион выходных токенов")
    args = parser.parse_args()
    data = json.loads(args.input.read_text())
    rows = data["measurements"]
    if data["stoppedByBudget"] or len(rows) != data["plannedMeasurements"]:
        parser.error("прогон неполный; итоговое сравнение не формируется")
    modes = [mode for mode in ORDER if any(row["mode"] == mode for row in rows)]
    identities = [{(row["datasetId"], row["repeat"]) for row in rows if row["mode"] == mode} for mode in modes]
    if any(identity != identities[0] for identity in identities):
        parser.error("режимы получили разные наборы сценариев")
    summaries = {mode: summarize([row for row in rows if row["mode"] == mode],
                                args.input_price, args.output_price) for mode in modes}
    tick = chr(96)
    lines = ["# Сравнение режимов планирования", "",
             f"Модель: {tick}{data['model']}{tick}. Сценариев в наборе: {data['selectedRows']}. "
             f"Измерений: {len(rows)}. HTTP-вызовов: {data['httpAttempts']}.",
             "", f"SHA-256 датасета: {tick}{data['datasetSha256']}{tick}.",
             f"Отпечаток классов планирования: {tick}{data['plannerBytecodeSha256']}{tick}.",
             "", "DIRECT использует исходную конфигурацию. Новые режимы используют сокращённый контекст "
             "и отдельные инструкции; параметры генерации: " + tick +
             json.dumps(data["modelParameters"], ensure_ascii=False) + tick + ".",
             "", "| Показатель | " + " | ".join(NAMES[mode] for mode in modes) + " |",
             "| --- | " + " | ".join("---:" for _ in modes) + " |"]
    metrics = [
        ("Полностью верные запросы", lambda s: f"{s['correct']}/{s['rows']} ({s['fullAccuracy']:.1%})"),
        ("Полностью верные запросы по архивной оценке", lambda s: f"{s['legacyAccuracy']:.1%}"),
        ("Точность типов и целей действий", lambda s: "—" if s["precision"] is None else f"{s['precision']:.1%}"),
        ("Полнота выделенных действий", lambda s: "—" if s["recall"] is None else f"{s['recall']:.1%}"),
        ("Входные токены, всего", lambda s: f"{s['inputTokens']:,}".replace(",", " ")),
        ("Выходные токены, всего", lambda s: f"{s['outputTokens']:,}".replace(",", " ")),
        ("Все токены на запрос, включая исправления", lambda s: f"{s['tokensPerRequest']:.0f}"),
        ("Медиана задержки, мс", lambda s: f"{s['medianMs']}"),
        ("p95 задержки, мс", lambda s: f"{s['p95Ms']}"),
        ("Средняя задержка, мс", lambda s: f"{s['avgMs']:.0f}"),
        ("Отказы API", lambda s: str(s["apiFailures"])),
        ("Оценка USD на 1000 запросов", lambda s: f"{s['estimatedCostPer1000Usd']:.3f}"),
    ]
    for title, formatter in metrics:
        lines.append("| " + title + " | " + " | ".join(formatter(summaries[mode]) for mode in modes) + " |")
    lines += ["", "## Повторы", "", "| Повтор | " + " | ".join(NAMES[mode] for mode in modes) + " |",
              "| --- | " + " | ".join("---:" for _ in modes) + " |"]
    for repeat in sorted({row["repeat"] for row in rows}):
        cells = []
        for mode in modes:
            subset = [row for row in rows if row["mode"] == mode and row["repeat"] == repeat]
            cells.append(f"{sum(row['correct'] for row in subset)}/{len(subset)}")
        lines.append(f"| {repeat} | " + " | ".join(cells) + " |")
    lines += ["", "## Категории исходного набора", "", "| Категория | " + " | ".join(NAMES[mode] for mode in modes) + " |",
              "| --- | " + " | ".join("---:" for _ in modes) + " |"]
    for category in sorted({row["category"] for row in rows}):
        cells = []
        for mode in modes:
            subset = [row for row in rows if row["mode"] == mode and row["category"] == category]
            cells.append(f"{sum(row['correct'] for row in subset)}/{len(subset)}")
        lines.append("| " + category + " | " + " | ".join(cells) + " |")
    if "DIRECT" in summaries:
        base = summaries["DIRECT"]
        lines += ["", "## Сравнение с текущим режимом", ""]
        for mode in modes:
            if mode == "DIRECT":
                continue
            s = summaries[mode]
            quality = s["fullAccuracy"] >= base["fullAccuracy"]
            precision = None if s["precision"] is None or base["precision"] is None else s["precision"] >= base["precision"]
            recall = None if s["recall"] is None or base["recall"] is None else s["recall"] >= base["recall"]
            latency = s["medianMs"] <= base["medianMs"] and s["p95Ms"] <= base["p95Ms"]
            tokens = None if base["tokensPerRequest"] == 0 else 1 - s["tokensPerRequest"] / base["tokensPerRequest"]
            cost = None if base["estimatedCostPer1000Usd"] == 0 else 1 - s["estimatedCostPer1000Usd"] / base["estimatedCostPer1000Usd"]
            precision_text = "не определена" if precision is None else ("да" if precision else "нет")
            recall_text = "не определена" if recall is None else ("да" if recall else "нет")
            token_text = "не определено" if tokens is None else f"{tokens:+.1%} экономии"
            cost_text = "не определено" if cost is None else f"{cost:+.1%} экономии"
            lines += [f"- **{NAMES[mode]}**: полностью правильных запросов не меньше — {'да' if quality else 'нет'}; "
                      f"точность действий не ниже — {precision_text}; полнота не ниже — {recall_text}; "
                      f"медиана и p95 не выше — {'да' if latency else 'нет'}. "
                      f"Изменение общего расхода токенов: {token_text}; тарифной оценки: {cost_text}."]
    lines += ["", "## Границы измерения", "",
              "Ожидания исходного датасета не менялись. Использован прежний ActionMatcher; в основной оценке "
              "эквивалентные моменты времени приводятся к часовому поясу пользователя. Архивный балл без этой нормализации "
              "сохранён в поле legacyCorrect. Неудачные обращения не исключаются из общей доли правильных запросов.",
              "",
              "Задержка измеряется для планирования: чтение контекста, обращения к Groq, исправления и выполнение песочницы. "
              "Распознавание аудио, внешний туннель и браузер сюда не входят; VOICE_TRANSCRIPT — готовый текст.",
              "",
              f"Тарифная оценка использует {args.input_price:.2f} USD за миллион входных и {args.output_price:.2f} USD "
              "за миллион выходных токенов по [публичному тарифу Groq](https://console.groq.com/docs/model/openai/gpt-oss-120b). "
              "Скидки за кеширование и инфраструктура не учтены; это расчёт по usage, а не счёт поставщика. "
              "Для отклонённого API ответа usage может быть неизвестен.",
              "",
              "Это сравнение полных реализаций: на результаты влияют и формат плана, и инструкции, и объём кандидатов, "
              "и интенсивность рассуждений. Экономию нельзя целиком приписывать JSON или Code Mode. "
              "Набор использовался при доработке; выводы о новых неизвестных командах потребуют отдельного отложенного набора."]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n")
    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with args.csv.open("w", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=["mode", *next(iter(summaries.values())).keys()])
            writer.writeheader()
            for mode, summary in summaries.items():
                writer.writerow(dict(mode=mode, **summary))
    print(args.output)

if __name__ == "__main__":
    main()
