-- Owner instruction (2026-11-16, stat_defaults sprendimas #5): adds a `version` column so a future
-- confirmed import (e.g. the planned VDA namų ūkių biudžetų tyrimo import, see
-- scripts/import_vda_expenses.py) can record which edition/table of the source survey a row came
-- from (e.g. a survey year or table code), alongside the already-existing `source`/`source_url`/
-- `as_of`. Nullable: existing rows (none from a versioned import yet) simply have no version.
ALTER TABLE stat_defaults ADD COLUMN version VARCHAR(100);
