-- Ledger smoke test: exercises the rules created in V3__create_ledger.sql.
-- All work happens inside one DO block that ends with an exception,
-- so everything is rolled back and no data is left behind.

DO $$
DECLARE
    v_tenant_a UUID;
    v_tenant_b UUID;
    v_cash_a   UUID;
    v_sales_a  UUID;
    v_cash_b   UUID;
    v_good     UUID;
    v_entry    UUID;
BEGIN
    INSERT INTO tenants (name) VALUES ('Smoke Tenant A') RETURNING id INTO v_tenant_a;
    INSERT INTO tenants (name) VALUES ('Smoke Tenant B') RETURNING id INTO v_tenant_b;
    INSERT INTO accounts (tenant_id, code, name, type, currency)
        VALUES (v_tenant_a, '1000', 'Cash', 'ASSET', 'USD') RETURNING id INTO v_cash_a;
    INSERT INTO accounts (tenant_id, code, name, type, currency)
        VALUES (v_tenant_a, '4000', 'Sales', 'REVENUE', 'USD') RETURNING id INTO v_sales_a;
    INSERT INTO accounts (tenant_id, code, name, type, currency)
        VALUES (v_tenant_b, '1000', 'Cash', 'ASSET', 'USD') RETURNING id INTO v_cash_b;
    RAISE NOTICE 'setup OK: same account code 1000 allowed in two different tenants';

    -- case 1: a balanced entry is accepted
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        INSERT INTO journal_entries (tenant_id, entry_date, description)
            VALUES (v_tenant_a, current_date, 'Balanced sale of 100 USD') RETURNING id INTO v_good;
        INSERT INTO journal_lines (tenant_id, entry_id, account_id, direction, amount_minor, currency)
            VALUES (v_tenant_a, v_good, v_cash_a,  'DEBIT',  10000, 'USD'),
                   (v_tenant_a, v_good, v_sales_a, 'CREDIT', 10000, 'USD');
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE NOTICE 'case 1 OK: balanced entry accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 1 FAILED (should have been accepted): %', SQLERRM;
    END;

    -- case 2: an unbalanced entry is rejected
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        INSERT INTO journal_entries (tenant_id, entry_date, description)
            VALUES (v_tenant_a, current_date, 'Unbalanced') RETURNING id INTO v_entry;
        INSERT INTO journal_lines (tenant_id, entry_id, account_id, direction, amount_minor, currency)
            VALUES (v_tenant_a, v_entry, v_cash_a,  'DEBIT',  10000, 'USD'),
                   (v_tenant_a, v_entry, v_sales_a, 'CREDIT',  9000, 'USD');
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE NOTICE 'case 2 FAILED: unbalanced entry was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 2 OK (rejected): %', SQLERRM;
    END;

    -- case 3: an entry with no lines is rejected
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        INSERT INTO journal_entries (tenant_id, entry_date, description)
            VALUES (v_tenant_a, current_date, 'Empty entry') RETURNING id INTO v_entry;
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE NOTICE 'case 3 FAILED: entry without lines was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 3 OK (rejected): %', SQLERRM;
    END;

    -- case 4: a line cannot use another tenant's account
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        INSERT INTO journal_entries (tenant_id, entry_date, description)
            VALUES (v_tenant_a, current_date, 'Cross-tenant') RETURNING id INTO v_entry;
        INSERT INTO journal_lines (tenant_id, entry_id, account_id, direction, amount_minor, currency)
            VALUES (v_tenant_a, v_entry, v_cash_b, 'DEBIT', 10000, 'USD');
        RAISE NOTICE 'case 4 FAILED: cross-tenant account was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 4 OK (rejected): %', SQLERRM;
    END;

    -- case 5: a line's currency must match its account's currency
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        INSERT INTO journal_entries (tenant_id, entry_date, description)
            VALUES (v_tenant_a, current_date, 'Wrong currency') RETURNING id INTO v_entry;
        INSERT INTO journal_lines (tenant_id, entry_id, account_id, direction, amount_minor, currency)
            VALUES (v_tenant_a, v_entry, v_cash_a, 'DEBIT', 10000, 'EUR');
        RAISE NOTICE 'case 5 FAILED: currency mismatch was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 5 OK (rejected): %', SQLERRM;
    END;

    -- case 6: a zero amount is rejected
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        INSERT INTO journal_entries (tenant_id, entry_date, description)
            VALUES (v_tenant_a, current_date, 'Zero amount') RETURNING id INTO v_entry;
        INSERT INTO journal_lines (tenant_id, entry_id, account_id, direction, amount_minor, currency)
            VALUES (v_tenant_a, v_entry, v_cash_a, 'DEBIT', 0, 'USD');
        RAISE NOTICE 'case 6 FAILED: zero amount was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 6 OK (rejected): %', SQLERRM;
    END;

    -- case 7: duplicate account code inside one tenant is rejected
    BEGIN
        INSERT INTO accounts (tenant_id, code, name, type, currency)
            VALUES (v_tenant_a, '1000', 'Duplicate cash', 'ASSET', 'USD');
        RAISE NOTICE 'case 7 FAILED: duplicate account code was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 7 OK (rejected): %', SQLERRM;
    END;

    -- case 8: posted lines cannot be updated
    BEGIN
        UPDATE journal_lines SET amount_minor = 1
            WHERE tenant_id = v_tenant_a AND entry_id = v_good;
        RAISE NOTICE 'case 8 FAILED: line update was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 8 OK (rejected): %', SQLERRM;
    END;

    -- case 9: posted entries cannot be deleted
    BEGIN
        DELETE FROM journal_entries WHERE tenant_id = v_tenant_a AND id = v_good;
        RAISE NOTICE 'case 9 FAILED: entry delete was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'case 9 OK (rejected): %', SQLERRM;
    END;

    RAISE EXCEPTION 'Smoke test finished - everything was rolled back';
END;
$$;