-- TODO(you) 5.1 (PW-2, PW-3, PW-8; SPEC §8.2): добавь таблицы аудита и меток.
-- transaction_audit должна быть RANGE-партиционирована по created_at.
-- Уникальный ключ родительской таблицы включает transaction_id и created_at.
-- Итог проверяется задачей :persistence-worker:generateJooq: должны появиться обе таблицы jOOQ.
CREATE TABLE transaction_audit (
    transaction_id   UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    user_id          TEXT         NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    merchant_id      TEXT         NOT NULL,
    country          CHAR(2)      NOT NULL,
    decision         TEXT         NOT NULL,
    risk_score       REAL         NOT NULL,
    reasons          TEXT[]       NOT NULL,
    degraded         BOOLEAN      NOT NULL,
    model_version    TEXT         NOT NULL,
    processing_us    INT          NOT NULL,
    PRIMARY KEY (transaction_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE transaction_label (
    transaction_id  UUID     PRIMARY KEY,
    expected_fraud  BOOLEAN  NOT NULL,
    pattern         TEXT
);