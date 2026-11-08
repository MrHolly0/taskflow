import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("large_summary", Path(__file__).with_name("summarize-large-planning.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class LargePlanningReportTest(unittest.TestCase):
    def data(self):
        row = dict(scenario="one", category="REMINDER", taskCount=100, repeat=1, mode="DIRECT", correct=False,
                   expected=1, actual=1, matched=1, dateChecks=0, datesCorrect=0,
                   reminderChecks=1, remindersCorrect=0, inputTokens=100, outputTokens=10,
                   httpCalls=1, passes=1, elapsedMs=25, apiFailure=False, noWrites=True,
                   errors=["FIELD_reminder_at:x:wrong"], targetRanks={"x":90}, actions=[])
        rows = [dict(row, mode=mode) for mode in summary.MODES]
        return dict(evaluationKind="large-context", measurements=rows, stoppedByBudget=False,
                    plannedMeasurements=3, scenarios=1, requests=[dict(id="one", request="Напомни", control="ACTIONS")],
                    taskCounts=[100], repeats=1, httpAttempts=3, anchorDate="2030-11-15", model="synthetic",
                    datasetSha256="dataset", plannerBytecodeSha256="planner", modelParameters={})

    def test_rejects_duplicate_measurement_instead_of_counting_it_as_another_repeat(self):
        data = self.data()
        data["measurements"][1] = copy.deepcopy(data["measurements"][0])
        with self.assertRaisesRegex(ValueError, "разные наборы"):
            summary.validate(data)

    def test_rejects_missing_pair_budget_stop_and_bad_call_counter(self):
        for mutation in (lambda d: d["measurements"].pop(),
                         lambda d: d.update(stoppedByBudget=True),
                         lambda d: d.update(httpAttempts=4)):
            data = self.data()
            mutation(data)
            with self.assertRaises(ValueError):
                summary.validate(data)

    def test_keeps_wrong_time_separate_from_right_action_target(self):
        data = self.data()
        totals = summary.summarize(data["measurements"])
        self.assertEqual(3, totals["matched"])
        self.assertEqual(0, totals["correct"])
        self.assertEqual(0, totals["remindersCorrect"])
        self.assertEqual(3, totals["reminderChecks"])
        self.assertIn("0/3", summary.fraction(totals["correct"], totals["count"]))
        self.assertIn("FIELD_reminder_at", summary.render(data, "raw"))

    def test_keeps_failed_requests_in_quality_tokens_and_latency(self):
        rows = self.data()["measurements"]
        rows[0].update(apiFailure=True, elapsedMs=45000, inputTokens=0, outputTokens=0)
        totals = summary.summarize(rows)
        self.assertEqual(3, totals["count"])
        self.assertEqual(1, totals["apiFailure"])
        self.assertEqual(45000, totals["p95Ms"])
        self.assertAlmostEqual(220 / 3, totals["tokensPerRequest"])


if __name__ == "__main__":
    unittest.main()
