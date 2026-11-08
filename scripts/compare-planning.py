#!/usr/bin/env python3
"""Живое сравнение планировщиков на локальном синтетическом наборе."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-live", action="store_true", help="разрешить передачу синтетических реплик в Groq и расход квоты")
    parser.add_argument("--suite", choices=("dataset", "large-context"), default="dataset")
    parser.add_argument("--large-set", choices=("regression", "control"), default="regression")
    parser.add_argument("--task-counts", default="100,200", help="размеры аккаунтов для large-context")
    parser.add_argument("--dataset", default="docs/vkr/dataset/nlp-dataset.json")
    parser.add_argument("--repeats", type=int, choices=(1, 2, 3), default=1)
    parser.add_argument("--run-name", default="run-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--budget-file", default=".gradle/planning-api-budget.json")
    parser.add_argument("--budget-limit", type=int, default=1000)
    parser.add_argument("--call-limit", type=int, default=1000)
    parser.add_argument("--pacing-ms", type=int, default=500)
    parser.add_argument("--per-category", type=int, default=0)
    parser.add_argument("--case-ids", default="")
    parser.add_argument("--modes", default="DIRECT,BATCH_PLAN,CODE_MODE")
    parser.add_argument("--effort", choices=("low", "medium", "high", "default"), default="low")
    args = parser.parse_args()
    if not args.allow_live:
        parser.error("для живого сравнения нужен --allow-live; обычные тесты Groq не вызывают")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.run_name):
        parser.error("run-name допускает только латинские буквы, цифры, дефис и подчёркивание")
    if not 1 <= args.budget_limit <= 3000 or not 1 <= args.call_limit <= 3000:
        parser.error("лимиты должны быть от 1 до 3000")
    if args.pacing_ms < 0 or args.per_category < 0:
        parser.error("интервал и размер поднабора не могут быть отрицательными")
    modes = args.modes.split(",")
    if len(set(modes)) != len(modes) or not set(modes) <= {"DIRECT", "BATCH_PLAN", "CODE_MODE"}:
        parser.error("неверный или повторяющийся режим")
    root = Path(__file__).resolve().parents[1]
    dataset = (root / args.dataset).resolve()
    if args.suite == "large-context" and (not re.fullmatch(r"(?:100|200)(?:,(?:100|200))*", args.task_counts)
                                          or len(set(args.task_counts.split(","))) != len(args.task_counts.split(","))):
        parser.error("task-counts допускает 100, 200 или 100,200 без повторов")
    if args.suite == "dataset" and not dataset.is_file():
        parser.error("файл датасета не найден")
    env = os.environ.copy()
    if not env.get("GROQ_API_KEY"):
        settings = root / "infra/.env"
        if settings.exists():
            for line in settings.read_text().splitlines():
                if line.strip().startswith("GROQ_API_KEY="):
                    words = shlex.split(line.strip().split("=", 1)[1], comments=True)
                    if len(words) == 1:
                        env["GROQ_API_KEY"] = words[0]
    if not env.get("GROQ_API_KEY"):
        parser.error("GROQ_API_KEY не задан в окружении или infra/.env")
    budget = (root / args.budget_file).resolve()
    budget.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(budget, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        state = json.loads(budget.read_text())
        if not 1 <= state["limit"] <= 3000 or state["used"] < 0:
            parser.error("повреждён файл бюджета")
    else:
        state = {"limit": args.budget_limit, "used": 0}
        with os.fdopen(descriptor, "w") as file:
            json.dump(state, file)
    print(f"Общий бюджет: {state['used']} из {state['limit']} уже использовано. Счётчик не сбрасывается.", flush=True)
    env.update(
        PLANNING_LIVE_APPROVED="true",
        PLANNING_LIVE_BUDGET_FILE=str(budget),
        PLANNING_LIVE_CALL_LIMIT=str(args.call_limit),
        PLANNING_DATASET=str(dataset),
        PLANNING_TASK_COUNTS=args.task_counts,
        PLANNING_LARGE_SET=args.large_set,
        PLANNING_REPEATS=str(args.repeats),
        PLANNING_RUN_NAME=args.run_name,
        PLANNING_MODES=args.modes,
        PLANNING_PER_CATEGORY=str(args.per_category),
        PLANNING_CASE_IDS=args.case_ids,
        PLANNING_ALL_NEGATIVE="false",
        PLANNING_PACING_MS=str(args.pacing_ms),
        ASSISTANT_EXPERIMENTAL_REASONING_EFFORT=args.effort,
        TESTCONTAINERS_RYUK_DISABLED="true",
    )
    if not env.get("JAVA_HOME"):
        homebrew_java = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
        if homebrew_java.exists():
            env["JAVA_HOME"] = str(homebrew_java)
    reports = root / "core-service/app/build/reports"
    reports.mkdir(parents=True, exist_ok=True)
    log = reports / f"planning-{args.run_name}.log"
    report = reports / f"planning-{args.run_name}.json"
    if report.exists():
        parser.error("отчёт с таким run-name уже существует; выбери другое имя")
    print(f"Лог: {log}\nОтчёт: {report}", flush=True)
    with log.open("w") as output:
        status = subprocess.run(
            ["./gradlew", ":core-service:app:liveTest", "--tests",
             "*PlanningLargeContextLiveTest" if args.suite == "large-context" else "*PlanningDatasetLiveTest",
             "--no-daemon", "--max-workers=1",
             "-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2"],
            cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT).returncode
    print(f"Код завершения Gradle: {status}", flush=True)
    return status

if __name__ == "__main__":
    raise SystemExit(main())
