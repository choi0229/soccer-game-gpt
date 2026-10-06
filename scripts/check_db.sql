-- Read-only effects: UPDATE/DELETE target no rows; invalid INSERT is rolled back by the exception block.
DO $$
DECLARE test_run UUID; next_sequence BIGINT;
BEGIN
    BEGIN
        UPDATE actions SET request_json = request_json WHERE false;
        RAISE EXCEPTION 'UPDATE guard did not fire';
    EXCEPTION WHEN others THEN
        IF SQLERRM <> 'actions are append-only' THEN RAISE; END IF;
    END;
    BEGIN
        DELETE FROM actions WHERE false;
        RAISE EXCEPTION 'DELETE guard did not fire';
    EXCEPTION WHEN others THEN
        IF SQLERRM <> 'actions are append-only' THEN RAISE; END IF;
    END;
    SELECT run_id,MAX(seq)+2 INTO test_run,next_sequence FROM actions GROUP BY run_id LIMIT 1;
    IF test_run IS NULL THEN RAISE EXCEPTION 'run API tests first'; END IF;
    BEGIN
        INSERT INTO actions(run_id,seq,request_json) VALUES(test_run,next_sequence,'{}');
        RAISE EXCEPTION 'sequence guard did not fire';
    EXCEPTION WHEN others THEN
        IF SQLERRM <> 'action sequence must increase by one' THEN RAISE; END IF;
    END;
    RAISE NOTICE 'PASS: append-only UPDATE/DELETE guards and contiguous sequence guard';
END;
$$;
