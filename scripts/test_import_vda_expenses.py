"""Tests for import_vda_expenses.py, run against a FAKE (hand-built, in-memory) XLSX file - the
owner has not yet supplied the real VDA file (see that script's module docstring). These tests
only check the script's parsing/SQL-generation logic is correct for a plausible-shaped input; the
HOUSEHOLD_LABEL_TO_SEGMENT mapping itself is provisional and must be re-checked once the real file
arrives.

Run with: .venv/bin/python -m pytest scripts/ (not wired into `make test` - this is a one-off
admin script, not part of either deployed service covered by that target).
"""
from decimal import Decimal

import pytest
from openpyxl import Workbook

from import_vda_expenses import (
    STAT_DEFAULTS_KEY,
    ExpenseRow,
    UnmappedHouseholdLabelError,
    build_upsert_sql,
    parse_expense_rows,
)


def _fake_xlsx(tmp_path, rows, header=("Namų ūkio tipas", "Vidutinės išlaidos, €/mėn.")):
    wb = Workbook()
    ws = wb.active
    ws.append(list(header))
    for row in rows:
        ws.append(list(row))
    path = tmp_path / "fake_vda.xlsx"
    wb.save(path)
    return path


def test_parse_expense_rows_maps_known_household_labels(tmp_path):
    path = _fake_xlsx(
        tmp_path,
        [
            ("1 asmuo", 950),
            ("4 asmenys, 2 dirbantys", 2100.50),
        ],
    )

    rows = parse_expense_rows(path)

    assert {r.segment for r in rows} == {"members=1;workers=1", "members=4;workers=2"}
    by_segment = {r.segment: r.value for r in rows}
    assert by_segment["members=1;workers=1"] == Decimal("950")
    assert by_segment["members=4;workers=2"] == Decimal("2100.50")


def test_parse_expense_rows_skips_blank_rows(tmp_path):
    path = _fake_xlsx(tmp_path, [("1 asmuo", 950), (None, None), ("2 asmenys, 1 dirbantis", 1200)])

    rows = parse_expense_rows(path)

    assert len(rows) == 2


def test_parse_expense_rows_raises_on_unmapped_household_label_by_default(tmp_path):
    path = _fake_xlsx(tmp_path, [("1 asmuo", 950), ("Visiškai nežinomas tipas", 1234)])

    with pytest.raises(UnmappedHouseholdLabelError) as exc_info:
        parse_expense_rows(path)
    assert "Visiškai nežinomas tipas" in str(exc_info.value)


def test_parse_expense_rows_skips_unmapped_when_allowed(tmp_path):
    path = _fake_xlsx(tmp_path, [("1 asmuo", 950), ("Visiškai nežinomas tipas", 1234)])

    rows = parse_expense_rows(path, allow_unmapped=True)

    assert len(rows) == 1
    assert rows[0].segment == "members=1;workers=1"


def test_parse_expense_rows_raises_on_empty_result(tmp_path):
    path = _fake_xlsx(tmp_path, [])

    with pytest.raises(ValueError, match="nerasta"):
        parse_expense_rows(path)


def test_parse_expense_rows_reads_a_named_sheet(tmp_path):
    wb = Workbook()
    wb.active.title = "Kita lentelė"
    ws = wb.create_sheet("Išlaidos")
    ws.append(["Namų ūkio tipas", "Vidutinės išlaidos, €/mėn."])
    ws.append(["1 asmuo", 950])
    path = tmp_path / "multi_sheet.xlsx"
    wb.save(path)

    rows = parse_expense_rows(path, sheet_name="Išlaidos")

    assert len(rows) == 1


def test_build_upsert_sql_contains_expected_key_segment_value_and_metadata(tmp_path):
    path = _fake_xlsx(tmp_path, [("1 asmuo", 950)])
    rows = parse_expense_rows(path)

    sql = build_upsert_sql(
        rows,
        source="VDA namų ūkių biudžetų tyrimas 2024",
        source_url="https://osp.stat.gov.lt/example",
        source_date="2025-01-01",
        version="2024 m. tyrimas",
    )

    assert f"'{STAT_DEFAULTS_KEY}'" in sql
    assert "'members=1;workers=1'" in sql
    assert "950" in sql
    assert "'VDA namų ūkių biudžetų tyrimas 2024'" in sql
    assert "'https://osp.stat.gov.lt/example'" in sql
    assert "'2025-01-01'" in sql
    assert "'2024 m. tyrimas'" in sql
    assert "FALSE, FALSE" in sql  # is_illustrative=FALSE, needs_review=FALSE for a confirmed import
    assert "ON CONFLICT (key, segment)" in sql


def test_build_upsert_sql_escapes_single_quotes_in_source():
    rows = [ExpenseRow(segment="members=1;workers=1", value=Decimal("950"))]

    sql = build_upsert_sql(rows, source="VDA's survey", source_url=None, source_date="2025-01-01", version="v1")

    assert "VDA''s survey" in sql
    assert "NULL" in sql  # source_url omitted


def test_build_upsert_sql_omits_source_url_as_null_when_not_given():
    rows = [ExpenseRow(segment="members=1;workers=1", value=Decimal("950"))]

    sql = build_upsert_sql(rows, source="VDA", source_url=None, source_date="2025-01-01", version="v1")

    lines = [line for line in sql.splitlines() if "members=1;workers=1" in line]
    assert len(lines) == 1
    assert "NULL" in lines[0]
