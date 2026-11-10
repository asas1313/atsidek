CREATE TABLE app_setting (
    setting_key VARCHAR(60) PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL
);
-- No seed row for goals_beta_mode_enabled: absence = OFF (confirmed default).
