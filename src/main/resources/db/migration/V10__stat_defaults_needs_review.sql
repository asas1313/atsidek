-- Owner instruction (2026-11-16, po B6 eval #11): kai kurios V4__seed_stat_defaults.sql "expenses"
-- segmento reikšmės nėra tiesiogiai iš maketo - jos gautos EKSTRAPOLIUOJANT maketo 4 asmenų namų
-- ūkio reikšmę (1900 €) OECD modifikuota namų ūkio ekvivalentiškumo skale (žr. V4 migracijos
-- komentarą). Tai METODAS, ne patvirtinta statistika ir ne tiesioginė maketo reikšmė - savininkas
-- nurodė pažymėti šias eilutes kaip needs_review ir NEBENAUDOTI jų, kol jis jų nepatvirtins.
--
-- "general" segmentas (vidurkis) ir "members=4;workers=2" (tiesioginė maketo reikšmė) ČIA
-- NEŽYMIMI - jie nėra OECD-skalės ekstrapoliacija. Income segmentai taip pat nežymimi - jų
-- vertinimo metodas kitoks (proporcingai pagal dirbančiųjų skaičių), ne OECD skalė.
ALTER TABLE stat_defaults ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE stat_defaults SET needs_review = TRUE
WHERE key = 'expenses'
  AND segment IN ('members=1;workers=1', 'members=2;workers=1', 'members=2;workers=2', 'members=3;workers=2');
