#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["openpyxl>=3.1"]
# ///
"""Imports a VDA (Valstybės duomenų agentūra) namų ūkių biudžetų tyrimo XLSX lentelę (vartojimo
išlaidos per mėnesį pagal namų ūkio tipą) į `stat_defaults` (key='expenses') SQL UPSERT eilutes.

Owner instruction (2026-11-16, stat_defaults sprendimas #5): "Paruošk importo skriptą VDA namų
ūkių biudžetų tyrimo XLSX lentelei... į stat_defaults su source, source_date ir versija. Failą
pateiksiu vėliau - kol kas tik skriptas ir testas su netikru failu."

STATUS (2026-11-16): the owner has not yet supplied the real XLSX file. `HOUSEHOLD_LABEL_TO_SEGMENT`
below and the assumed column layout (household-type label in column A, EUR/month value in column
B, header in row 1, data from row 2) are PROVISIONAL - built from this project's existing segment
vocabulary (see `src/main/resources/db/migration/V3__stat_defaults.sql`), not from the real file's
actual structure. Re-verify both against the real file before using this script's output.

This is a one-off admin tool, not part of the deployed `assistant/` service - hence the PEP 723
inline dependency block above (run with `uv run scripts/import_vda_expenses.py ...`) instead of
adding openpyxl to `assistant/requirements.txt`.

This script only PRINTS/WRITES SQL for review - it never connects to a database itself, so a human
always reviews the generated UPSERT statements (and promotes them into a proper, reviewed Flyway
migration) before they run against any real `stat_defaults` table.

Usage:
    uv run scripts/import_vda_expenses.py path/to/vda.xlsx \\
        --source "VDA namų ūkių biudžetų tyrimas 2024" \\
        --source-url "https://osp.stat.gov.lt/..." \\
        --source-date 2025-01-01 \\
        --version "2024 m. tyrimas" \\
        [--sheet "Lapas1"] [--allow-unmapped] [--output stat_defaults_expenses.sql]
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

from openpyxl import load_workbook

# PROVISIONAL (see module docstring) - extend/replace once the real file's household-type labels
# are known. Segment format matches the existing 'income'/'expenses' rows in V3/V4 migrations.
HOUSEHOLD_LABEL_TO_SEGMENT = {
    "1 asmuo": "members=1;workers=1",
    "2 asmenys, 1 dirbantis": "members=2;workers=1",
    "2 asmenys, 2 dirbantys": "members=2;workers=2",
    "3 asmenys, 2 dirbantys": "members=3;workers=2",
    "4 asmenys, 2 dirbantys": "members=4;workers=2",
}

# This import is specifically "vartojimo išlaidos per mėnesį" - always the 'expenses' key, never
# 'income'/'typical_amount' (those are a different VDA table, out of this script's scope).
STAT_DEFAULTS_KEY = "expenses"


class UnmappedHouseholdLabelError(ValueError):
    """Raised when the XLSX has a household-type label not in HOUSEHOLD_LABEL_TO_SEGMENT - fails
    loudly by default rather than silently dropping a row (pass allow_unmapped=True to skip it
    instead, e.g. while first exploring an unfamiliar real file)."""


@dataclass(frozen=True)
class ExpenseRow:
    segment: str
    value: Decimal


def parse_expense_rows(
    xlsx_path: Path,
    sheet_name: str | None = None,
    label_to_segment: dict[str, str] = HOUSEHOLD_LABEL_TO_SEGMENT,
    allow_unmapped: bool = False,
) -> list[ExpenseRow]:
    """Reads (household-type label, EUR/month value) from columns A/B, data starting row 2 (row 1
    is a header). Blank rows (either cell empty) are skipped. Raises UnmappedHouseholdLabelError
    if any non-blank label isn't in `label_to_segment`, unless `allow_unmapped` is set."""
    workbook = load_workbook(xlsx_path, read_only=True, data_only=True)
    worksheet = workbook[sheet_name] if sheet_name else workbook.worksheets[0]

    rows: list[ExpenseRow] = []
    unmapped: list[str] = []
    for label, raw_value in worksheet.iter_rows(min_row=2, max_col=2, values_only=True):
        if label is None or raw_value is None:
            continue
        label = str(label).strip()
        segment = label_to_segment.get(label)
        if segment is None:
            unmapped.append(label)
            continue
        try:
            value = Decimal(str(raw_value))
        except InvalidOperation as exc:
            raise ValueError(f"Netinkama skaitinė reikšmė eilutėje {label!r}: {raw_value!r}") from exc
        rows.append(ExpenseRow(segment=segment, value=value))

    if unmapped and not allow_unmapped:
        raise UnmappedHouseholdLabelError(
            "Nežinomi namų ūkio tipo žymenys (papildykite HOUSEHOLD_LABEL_TO_SEGMENT arba "
            f"naudokite --allow-unmapped, kad šios eilutės būtų praleistos): {unmapped!r}"
        )
    if not rows:
        raise ValueError("XLSX faile nerasta nė vienos atpažintos namų ūkio išlaidų eilutės")
    return rows


def _sql_literal(value: object) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def build_upsert_sql(
    rows: list[ExpenseRow], source: str, source_url: str | None, source_date: str, version: str
) -> str:
    """Confirmed-source import -> is_illustrative=FALSE, needs_review=FALSE (see stat_defaults
    sprendimai #1-2: only needs_review=false rows render as "Statistika: šaltinis, metai", not
    "Iliustracinė prielaida")."""
    value_lines = [
        "    ("
        f"{_sql_literal(STAT_DEFAULTS_KEY)}, {_sql_literal(row.segment)}, {row.value}, "
        f"{_sql_literal('EUR_PER_MONTH')}, {_sql_literal(source)}, "
        f"{_sql_literal(source_url) if source_url else 'NULL'}, "
        f"{_sql_literal(source_date)}, "
        f"FALSE, FALSE, {_sql_literal(version)})"
        for row in rows
    ]
    return "\n".join(
        [
            "-- Generated by scripts/import_vda_expenses.py - review before applying.",
            "INSERT INTO stat_defaults "
            "(key, segment, value, unit, source, source_url, as_of, is_illustrative, needs_review, version) VALUES",
            ",\n".join(value_lines),
            "ON CONFLICT (key, segment) DO UPDATE SET "
            "value = EXCLUDED.value, source = EXCLUDED.source, source_url = EXCLUDED.source_url, "
            "as_of = EXCLUDED.as_of, is_illustrative = EXCLUDED.is_illustrative, "
            "needs_review = EXCLUDED.needs_review, version = EXCLUDED.version;",
        ]
    )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("xlsx_path", type=Path)
    parser.add_argument("--sheet", default=None, help="Lapo pavadinimas (numatyta: pirmas lapas)")
    parser.add_argument("--source", required=True)
    parser.add_argument("--source-url", default=None)
    parser.add_argument("--source-date", required=True, help="ISO YYYY-MM-DD")
    parser.add_argument("--version", required=True, help="Tyrimo leidimo/lentelės versija")
    parser.add_argument(
        "--allow-unmapped", action="store_true", help="Praleisti neatpažintus namų ūkio tipo žymenis"
    )
    parser.add_argument("--output", type=Path, default=None, help="Rašyti SQL į failą vietoj stdout")
    args = parser.parse_args(argv)

    rows = parse_expense_rows(args.xlsx_path, sheet_name=args.sheet, allow_unmapped=args.allow_unmapped)
    sql = build_upsert_sql(rows, args.source, args.source_url, args.source_date, args.version)

    if args.output:
        args.output.write_text(sql + "\n", encoding="utf-8")
    else:
        print(sql)


if __name__ == "__main__":
    main()
