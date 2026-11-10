-- Seed duomenys taupymo tikslų statistikos numatymams (F2 fazė).
--
-- SVARBU DĖL TIKSLUMO (spec.md 5 skyrius: "neišgalvokite statistinių skaičių"):
-- * Šeimos (4 nariai, 2 dirbantys) pajamų/išlaidų reikšmės (3200 €/1900 €) ir "Vaikui" tipinė suma
--   (20000 €) bei automobilio pavyzdys (25000 €) paimtos TIESIOGIAI iš patvirtinto maketo
--   (docs/specs/mockups/Atsidėk – Taupymo tikslų skaičiuoklė.pdf), kuris PATS AIŠKIAI nurodo, kad šios
--   reikšmės yra iliustracinės ("Maketas: statistinės reikšmės iliustracinės. Produkcijoje jos imamos iš
--   VDA ir Lietuvos banko duomenų su data ir nuoroda.").
-- * Kitų namų ūkio segmentų (1/1, 2/1, 2/2, 3/2) pajamos/išlaidos ĮVERTINTOS proporcingai nuo maketo
--   4/2 reikšmės: pajamos ~ pagal dirbančiųjų skaičių (1600 €/dirbančiam), išlaidos ~ pagal OECD
--   modifikuotą namų ūkio ekvivalentiškumo skalę (1-as suaugęs=1.0, kiekvienas papildomas narys +0.5/+0.3).
--   Tai METODAS įvertinimui, NE patikrinta statistika – visos VISŲ segmentų reikšmės žymimos
--   is_illustrative = TRUE.
-- * "HOME_DOWN_PAYMENT" ir "OTHER" tikslo tipų tipinės sumos makete NEPATEIKTOS – F2 agentas (šis PR)
--   pats parinko pagrįstas apvalias sumas (15000 € / 5000 €). Tai ATVIRAS KLAUSIMAS, surašytas PR apraše.
-- * Realių VDA (osp.stat.gov.lt) ir Lietuvos banko (lb.lt) puslapių buvo bandyta patikrinti tiesiogiai
--   (WebFetch) prieš šią migraciją: abu portalai duomenis rodo tik per JS varomą rodiklių
--   duomenų bazę / atsisiunčiamus XLSX failus, todėl konkrečios datuotos lentelės automatiškai patikrinti
--   nepavyko (kaip ir numatyta PLAN.md 2 skyriuje, K4). source_url žemiau rodo į KONKREČIUS puslapius,
--   kuriuos agentas atidarė ir kuriuose artimiausia tema (darbo rinka / namų ūkių biudžetų tyrimas, jų
--   informaciniai pranešimai), kad būtų lengva patikrinti rankiniu būdu vėliau.

-- ---- namų ūkio pajamos "į rankas" / mėn. (segmentas pagal members/workers derinį)
INSERT INTO stat_defaults (key, segment, value, unit, source, source_url, as_of, is_illustrative) VALUES
    ('income', 'members=1;workers=1', 1600.00, 'EUR_PER_MONTH',
        'Įvertinta: 1 dirbantis × ~1600 €/mėn. (proporcingai nuo maketo 4 asmenų/2 dirbančiųjų pavyzdžio, žr. migracijos komentarą); tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://www.lb.lt/lt/darbo-rinka', NULL, TRUE),
    ('income', 'members=2;workers=1', 1600.00, 'EUR_PER_MONTH',
        'Įvertinta: 1 dirbantis × ~1600 €/mėn.; tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://www.lb.lt/lt/darbo-rinka', NULL, TRUE),
    ('income', 'members=2;workers=2', 3200.00, 'EUR_PER_MONTH',
        'Įvertinta: 2 dirbantys × ~1600 €/mėn.; tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://www.lb.lt/lt/darbo-rinka', NULL, TRUE),
    ('income', 'members=3;workers=2', 3200.00, 'EUR_PER_MONTH',
        'Įvertinta: 2 dirbantys × ~1600 €/mėn.; tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://www.lb.lt/lt/darbo-rinka', NULL, TRUE),
    ('income', 'members=4;workers=2', 3200.00, 'EUR_PER_MONTH',
        'Maketo iliustracinė reikšmė: "vidutinis neto atlyginimas × 2 dirbantys (VDA, iliustracinė)" (docs/specs/mockups)',
        'https://www.lb.lt/lt/darbo-rinka', NULL, TRUE),
    ('income', 'general', 2500.00, 'EUR_PER_MONTH',
        'Apskaičiuota kaip žinomų namų ūkio segmentų aritmetinis vidurkis; naudojama, kai members/workers nenurodyti (iliustracinė)',
        NULL, NULL, TRUE)
ON CONFLICT (key, segment) DO UPDATE SET
    value = EXCLUDED.value, unit = EXCLUDED.unit, source = EXCLUDED.source,
    source_url = EXCLUDED.source_url, as_of = EXCLUDED.as_of, is_illustrative = EXCLUDED.is_illustrative;

-- ---- namų ūkio išlaidos / mėn. (segmentas pagal members/workers derinį; dydis skaičiuojamas pagal members,
-- ne workers, nes išlaidos priklauso nuo namų ūkio dydžio, ne nuo dirbančiųjų skaičiaus)
INSERT INTO stat_defaults (key, segment, value, unit, source, source_url, as_of, is_illustrative) VALUES
    ('expenses', 'members=1;workers=1', 900.00, 'EUR_PER_MONTH',
        'Įvertinta OECD modifikuota ekvivalentiškumo skale nuo maketo 4 asmenų namų ūkio reikšmės (1900 €); tiksli VDA namų ūkių biudžetų tyrimo reikšmė nepatikrinta automatiškai',
        'https://osp.stat.gov.lt/informaciniai-pranesimai', NULL, TRUE),
    ('expenses', 'members=2;workers=1', 1350.00, 'EUR_PER_MONTH',
        'Įvertinta OECD modifikuota ekvivalentiškumo skale nuo maketo 4 asmenų namų ūkio reikšmės (1900 €); tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://osp.stat.gov.lt/informaciniai-pranesimai', NULL, TRUE),
    ('expenses', 'members=2;workers=2', 1350.00, 'EUR_PER_MONTH',
        'Įvertinta OECD modifikuota ekvivalentiškumo skale nuo maketo 4 asmenų namų ūkio reikšmės (1900 €); tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://osp.stat.gov.lt/informaciniai-pranesimai', NULL, TRUE),
    ('expenses', 'members=3;workers=2', 1600.00, 'EUR_PER_MONTH',
        'Įvertinta OECD modifikuota ekvivalentiškumo skale nuo maketo 4 asmenų namų ūkio reikšmės (1900 €); tiksli VDA reikšmė nepatikrinta automatiškai',
        'https://osp.stat.gov.lt/informaciniai-pranesimai', NULL, TRUE),
    ('expenses', 'members=4;workers=2', 1900.00, 'EUR_PER_MONTH',
        'Maketo iliustracinė reikšmė: "vidutinės 4 asmenų namų ūkio išlaidos (VDA, iliustracinė)" (docs/specs/mockups)',
        'https://osp.stat.gov.lt/informaciniai-pranesimai', NULL, TRUE),
    ('expenses', 'general', 1400.00, 'EUR_PER_MONTH',
        'Apskaičiuota kaip žinomų namų ūkio segmentų aritmetinis vidurkis; naudojama, kai members/workers nenurodyti (iliustracinė)',
        NULL, NULL, TRUE)
ON CONFLICT (key, segment) DO UPDATE SET
    value = EXCLUDED.value, unit = EXCLUDED.unit, source = EXCLUDED.source,
    source_url = EXCLUDED.source_url, as_of = EXCLUDED.as_of, is_illustrative = EXCLUDED.is_illustrative;

-- ---- tikslo tipinė suma (segmentas pagal goalType; be annualInflation/annualReturn, žr. V3 komentarą)
INSERT INTO stat_defaults (key, segment, value, unit, source, source_url, as_of, is_illustrative) VALUES
    ('typical_amount', 'goalType=CHILD', 20000.00, 'EUR',
        'Maketo iliustracinė suma: "Vaikui: 20000 € – tipinė suma šiam tikslui (iliustracinė)" (docs/specs/mockups)',
        NULL, NULL, TRUE),
    ('typical_amount', 'goalType=CAR', 25000.00, 'EUR',
        'Maketo pavyzdys: "elektromobilis už ~25 tūkst." (docs/specs/mockups, mobilaus ekrano pavyzdys)',
        NULL, NULL, TRUE),
    ('typical_amount', 'goalType=HOME_DOWN_PAYMENT', 15000.00, 'EUR',
        'Makete nepateikta tiksli suma – F2 agento paties parinkta pagrįsta iliustracinė suma (atviras klausimas PR apraše)',
        NULL, NULL, TRUE),
    ('typical_amount', 'goalType=OTHER', 5000.00, 'EUR',
        'Makete nepateikta tiksli suma (tik pavyzdžiai "Kelionė, remontas…") – F2 agento paties parinkta iliustracinė suma (atviras klausimas PR apraše)',
        NULL, NULL, TRUE)
ON CONFLICT (key, segment) DO UPDATE SET
    value = EXCLUDED.value, unit = EXCLUDED.unit, source = EXCLUDED.source,
    source_url = EXCLUDED.source_url, as_of = EXCLUDED.as_of, is_illustrative = EXCLUDED.is_illustrative;
