#!/usr/bin/env python3
"""AI ištraukimo (`POST /api/v1/goals/parse`) eval'o scoring skriptas.

SVARBU (žr. docs/specs/taupymo-tikslai/spec.md 7 sk. ir PLAN.md K2/F4): šis skriptas kviečia TIKRĄ
Claude API ir kainuoja pinigus. Jis NIEKADA nepaleidžiamas automatiškai per `pytest` ar kiekvieno PR
CI - tik RANKINIU būdu arba per atskirą `workflow_dispatch`/`run-evals` CI workflow (F6 fazės darbas,
šis PR tik rekomenduoja). Leidžiama paleisti TIK keičiant promptą, tool schemą, modelį ar
normalizavimo kodą (assistant/app/goals_extractor.py).

Naudojimas:
    cd assistant
    export ANTHROPIC_API_KEY=sk-...
    python evals/goals_parse/run_eval.py [--model claude-haiku-5-5]

Rezultatas (tikslumas per lauką + bendras) įrašomas į evals/results/<YYYY-MM-DD>_<modelis>.json.
"""
import argparse
import json
import os
import sys
from datetime import date
from pathlib import Path

ASSISTANT_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ASSISTANT_DIR))  # kad veiktų "from app...." importai
sys.path.insert(0, str(Path(__file__).resolve().parent))  # šio katalogo cases.py

from app.goals_extractor import ClaudeGoalsExtractor, GoalsExtractionError, missing_critical_fields  # noqa: E402
from cases import CASES, REFERENCE_DATE  # noqa: E402

# API lauko vardas -> GoalExtraction atributo vardas (žr. app/goals_extractor.py).
FIELD_ATTR = {
    "goalType": "goal_type", "mode": "mode", "amount": "amount", "years": "years",
    "targetYear": "target_year", "targetMonth": "target_month",
    "childCurrentAge": "child_current_age", "childTargetAge": "child_target_age",
    "monthlyContribution": "monthly_contribution", "members": "members", "workers": "workers",
    "income": "income", "expenses": "expenses",
}
FIELDS = list(FIELD_ATTR)


def _matches(expected, actual) -> bool:
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        return actual is not None and float(actual) == float(expected)
    return actual == expected


def run(model: str) -> dict:
    extractor = ClaudeGoalsExtractor(os.environ["ANTHROPIC_API_KEY"], model)
    per_field = {f: {"correct": 0, "graded": 0} for f in FIELDS}
    missing_correct = missing_graded = 0
    details = []

    for case in CASES:
        row = {"id": case["id"]}
        try:
            x = extractor.extract(case["text"], today=REFERENCE_DATE)
        except GoalsExtractionError as e:
            details.append({**row, "error": str(e)})
            continue

        expected = case.get("expected", {})
        for f in FIELDS:
            if f not in expected:
                continue
            actual = getattr(x, FIELD_ATTR[f])
            ok = _matches(expected[f], actual)
            per_field[f]["graded"] += 1
            per_field[f]["correct"] += int(ok)
            row[f] = {"expected": expected[f], "actual": _jsonable(actual), "ok": ok}

        if "expect_missing_includes" in case:
            missing_graded += 1
            actual_missing = set(missing_critical_fields(x))
            ok = set(case["expect_missing_includes"]) <= actual_missing
            missing_correct += int(ok)
            row["missing_check"] = {
                "expected_subset": case["expect_missing_includes"],
                "actual": sorted(actual_missing),
                "ok": ok,
            }
        details.append(row)

    total_correct = sum(v["correct"] for v in per_field.values())
    total_graded = sum(v["graded"] for v in per_field.values())
    per_field_accuracy = {f: (v["correct"] / v["graded"] if v["graded"] else None) for f, v in per_field.items()}

    return {
        "model": model,
        "date": date.today().isoformat(),
        "reference_date": REFERENCE_DATE.isoformat(),
        "cases": len(CASES),
        "overall_accuracy": (total_correct / total_graded) if total_graded else None,
        "per_field_accuracy": per_field_accuracy,
        "missing_critical_accuracy": (missing_correct / missing_graded) if missing_graded else None,
        "details": details,
    }


def _jsonable(v):
    # Decimal nėra JSON serializuojamas tiesiogiai - konvertuojam rezultatui į float/str.
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return v


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default=os.getenv("AI_MODEL", "claude-haiku-5-5"))
    args = parser.parse_args()

    if not os.getenv("ANTHROPIC_API_KEY"):
        print("Reikia ANTHROPIC_API_KEY aplinkos kintamojo tikram eval'ui paleisti.", file=sys.stderr)
        sys.exit(1)

    summary = run(args.model)
    out_dir = ASSISTANT_DIR / "evals" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{summary['date']}_{args.model}.json"
    out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    acc = summary["overall_accuracy"]
    print(f"Bendras tikslumas (gradable laukai): {acc:.1%}" if acc is not None else "Bendras tikslumas: n/a")
    for f, a in summary["per_field_accuracy"].items():
        if a is not None:
            print(f"  {f}: {a:.1%}")
    print(f"Rezultatas įrašytas: {out_path}")


if __name__ == "__main__":
    main()
