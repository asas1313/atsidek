CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    auth_provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (auth_provider, provider_subject)
);
