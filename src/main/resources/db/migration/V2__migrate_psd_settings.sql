-- Vienkartinė duomenų migracija (perkelta iš buvusio schema.sql be pakeitimų, F0 fazė):
-- senas bendras PSD nustatymas tampa 2026 m. nustatymu.
INSERT INTO user_setting (user_id, setting_key, setting_value)
    SELECT user_id, 'psd_from_2026', setting_value FROM user_setting o WHERE setting_key = 'psd_from'
    AND NOT EXISTS (SELECT 1 FROM user_setting n WHERE n.user_id = o.user_id AND n.setting_key = 'psd_from_2026');
INSERT INTO user_setting (user_id, setting_key, setting_value)
    SELECT user_id, 'psd_to_2026', setting_value FROM user_setting o WHERE setting_key = 'psd_to'
    AND NOT EXISTS (SELECT 1 FROM user_setting n WHERE n.user_id = o.user_id AND n.setting_key = 'psd_to_2026');
DELETE FROM user_setting WHERE setting_key IN ('psd_from', 'psd_to');
