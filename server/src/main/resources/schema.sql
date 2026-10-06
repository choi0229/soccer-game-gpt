CREATE TABLE IF NOT EXISTS runs (
    id UUID PRIMARY KEY,
    seed BIGINT NOT NULL,
    school_id TEXT NOT NULL,
    config_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
)@@
CREATE TABLE IF NOT EXISTS actions (
    run_id UUID NOT NULL REFERENCES runs(id),
    seq BIGINT NOT NULL CHECK(seq > 0),
    request_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id, seq)
)@@
CREATE OR REPLACE FUNCTION immutable_action_log() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'actions are append-only';
END;
$$@@
DROP TRIGGER IF EXISTS action_log_immutable ON actions@@
CREATE TRIGGER action_log_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON actions
    FOR EACH STATEMENT EXECUTE FUNCTION immutable_action_log()@@
CREATE OR REPLACE FUNCTION action_sequence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_seq BIGINT;
BEGIN
    PERFORM 1 FROM runs WHERE id=NEW.run_id FOR UPDATE;
    SELECT COALESCE(MAX(seq),0) INTO previous_seq FROM actions WHERE run_id=NEW.run_id;
    IF NEW.seq <> previous_seq + 1 THEN RAISE EXCEPTION 'action sequence must increase by one'; END IF;
    RETURN NEW;
END;
$$@@
DROP TRIGGER IF EXISTS action_sequence ON actions@@
CREATE TRIGGER action_sequence BEFORE INSERT ON actions
    FOR EACH ROW EXECUTE FUNCTION action_sequence_guard()@@
CREATE OR REPLACE FUNCTION immutable_run_settings() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seed IS DISTINCT FROM OLD.seed OR NEW.school_id IS DISTINCT FROM OLD.school_id
       OR NEW.config_json IS DISTINCT FROM OLD.config_json THEN RAISE EXCEPTION 'run settings are immutable'; END IF;
    RETURN NEW;
END;
$$@@
DROP TRIGGER IF EXISTS run_settings_immutable ON runs@@
CREATE TRIGGER run_settings_immutable BEFORE UPDATE ON runs FOR EACH ROW EXECUTE FUNCTION immutable_run_settings()@@
