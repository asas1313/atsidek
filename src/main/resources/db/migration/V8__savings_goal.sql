CREATE TABLE savings_goal (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    goal_type VARCHAR(30) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    target_date DATE,
    assumptions JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id)                         -- "exactly one goal per user", enforced in the DB
);
