# Техническое задание: Anti-Fraud & Risk Engine

Версия 1.0 · 2026-10-02

Связанные документы:
- [ARCHITECTURE.md](ARCHITECTURE.md) — схема и устройство системы;
- [openapi.yaml](openapi.yaml) — REST API;
- [proto/src/main/proto/risk/v1/scoring.proto](proto/src/main/proto/risk/v1/scoring.proto) — gRPC-контракт.

---

## 1. Назначение

Система оценивает риск финансовой транзакции в момент её совершения и возвращает решение:

| Решение | Значение |
|---------|----------|
| `ALLOW` | Транзакция допустима |
| `FLAG`  | Нужна дополнительная проверка (например, 3-D Secure или ручной разбор) |
| `BLOCK` | Транзакция отклоняется |

Проект учебный (портфолио). Реальных платёжных данных нет: транзакции генерирует моковый сервис с заранее известной разметкой «фрод / не фрод».

## 2. Границы

**Входит в проект:** скоринг по gRPC, feature store в Redis, правила, ONNX-модели (полная и облегчённая), Kafka-пайплайн, аудит в PostgreSQL, моковый сервис, Live Monitor на WebSocket, метрики, дашборды, нагрузочные тесты.

**Не входит в проект:** аутентификация пользователей API, реальные платёжные системы, UI для ручного разбора FLAG-транзакций, Kubernetes (опционально в самом конце).

## 3. Компоненты и порты

| Модуль | Назначение | Порты (по умолчанию) |
|--------|-----------|----------------------|
| `scoring-service` (Java) | Online-скоринг | gRPC `9090`, HTTP `8090` (только actuator; 8080 часто занят) |
| `transaction-mock` (Kotlin) | Генерация транзакций, управление сценариями | HTTP `8081` |
| `live-monitor` (Kotlin) | WebSocket-лента решений и HTML-страница | HTTP/WS `8082` |
| `persistence-worker` (Java) | Kafka → PostgreSQL, API аудита и качества | HTTP `8083` |
| `profile-worker` (Kotlin) | Kafka → медленный профиль в Redis | HTTP `8084` (только actuator) |

У каждого сервиса есть `/actuator/health` и `/actuator/prometheus`.

## 4. Модель транзакции

| Поле | Тип | Обязательно | Правила валидации |
|------|-----|-------------|-------------------|
| `transaction_id` | string (UUID) | да | валидный UUID |
| `user_id` | string | да | 1–64 символа |
| `amount_minor` | int64 | да | > 0; сумма в минорных единицах (центах) |
| `currency` | string | да | ISO 4217, 3 заглавные буквы |
| `timestamp` | Timestamp | да | не в будущем больше чем на 5 минут |
| `merchant_id` | string | да | 1–64 символа |
| `merchant_category` | string | да | MCC, 4 цифры |
| `device_id` | string | да | 1–128 символов |
| `ip` | string | да | валидный IPv4 или IPv6 |
| `country` | string | да | ISO 3166-1 alpha-2 |
| `latitude`, `longitude` | double | нет | −90..90, −180..180 |
| `channel` | enum | да | `ONLINE`, `POS`, `ATM` |

Деньги хранятся только в минорных единицах (`int64`), никогда не в `double`.

Упрощение первой версии: мок генерирует только `USD`, поэтому пороги в правилах заданы в центах USD. Конвертация валют не реализуется.

## 5. Функциональные требования

### 5.1. Scoring Service

| ID | Требование |
|----|-----------|
| SC-1 | Принимает `Score(ScoreRequest)` по gRPC и возвращает `ScoreResponse` с полями `decision`, `risk_score` (0..1), `reasons[]`, `degraded`, `model_version`, `processing_time_us` |
| SC-2 | Невалидный запрос: gRPC-статус `INVALID_ARGUMENT` и список ошибочных полей |
| SC-3 | **Идемпотентность.** Повторный `transaction_id` в течение 24 ч возвращает сохранённый ответ, не пересчитывая его и не меняя окна |
| SC-4 | **Фичи.** Один вызов Lua-скрипта атомарно добавляет транзакцию в окна, удаляет устаревшие записи и возвращает агрегаты и профиль (раздел 6) |
| SC-5 | **Правила.** Набор правил из раздела 7. Пороги и веса настраиваются в `application.yml` |
| SC-6 | **ML.** Встроенный ONNX Runtime. Модели загружаются при старте, а не на каждый запрос, внешних вызовов нет |
| SC-7 | **Решение.** Итоговый `risk_score` и решение считаются по правилам раздела 7.3 |
| SC-8 | **Degraded-режим.** Если Redis не ответил за 5 мс или открыт circuit breaker, скоринг идёт облегчённой моделью и правилами без Redis-фич, `degraded=true` |
| SC-9 | После ответа событие `ScoredTransaction` публикуется в Kafka `transactions-scored` (ключ `user_id`), не блокируя ответ |
| SC-10 | Если Kafka недоступна, события копятся в ограниченном буфере (10 000 шт.). При переполнении событие отбрасывается, и растёт метрика `scoring_kafka_dropped_total` |

### 5.2. Transaction Mock

| ID | Требование |
|----|-----------|
| TM-1 | Пул нормальных пользователей (по умолчанию 10 000) со стабильным профилем: домашняя страна, 1–2 устройства, 1–3 IP, средний чек (логнормальное распределение) |
| TM-2 | Режим **потока**: постоянный RPS и доля фрода, управляются через REST (`/api/v1/mock/stream`) |
| TM-3 | Режим **сценария**: запуск одного фрод-паттерна по REST для заданного или случайного пользователя |
| TM-4 | Пять паттернов: `VELOCITY_BURST`, `ACCOUNT_TAKEOVER`, `CARD_TESTING`, `AMOUNT_ANOMALY`, `IMPOSSIBLE_TRAVEL`. Параметры описаны в ARCHITECTURE.md, раздел 9 |
| TM-5 | Легитимные отклонения (поездка, новое устройство) с частотой около 0,5 % |
| TM-6 | Для каждой транзакции публикуется метка в Kafka `transactions-labels`: `transaction_id`, `expected_fraud`, `pattern` |
| TM-7 | Режим **экспорта датасета**: генерирует N транзакций с фичами и метками в CSV для обучения моделей, без вызовов Scoring |
| TM-8 | Вызывает Scoring по gRPC с дедлайном 100 мс и записывает собственную метрику задержки |

### 5.3. Persistence Worker

| ID | Требование |
|----|-----------|
| PW-1 | Читает `transactions-scored` и пишет в `transaction_audit` батчами (до 500 записей или раз в 200 мс) |
| PW-2 | Запись идемпотентна: `INSERT … ON CONFLICT DO NOTHING` по `(transaction_id, created_at)` |
| PW-3 | Читает `transactions-labels` и пишет в `transaction_label` |
| PW-4 | Ошибку обработки повторяет через retry-топик (3 попытки с нарастающей задержкой), после этого сообщение уходит в `.dlq` |
| PW-5 | Offset коммитится только после успешной записи в БД |
| PW-6 | REST API аудита: транзакция по id и поиск с фильтрами (openapi.yaml) |
| PW-7 | REST API качества: precision, recall и F1 по решениям, отдельно по каждому паттерну и для degraded-режима |
| PW-8 | Партиции `transaction_audit` по месяцам создаются заранее, минимум на 2 месяца вперёд |

### 5.4. Profile Worker

| ID | Требование |
|----|-----------|
| PR-1 | Читает `transactions-scored` и обновляет медленный профиль: средний чек (EMA, α = 0,1), число транзакций, множества устройств, IP и стран |
| PR-2 | Множества ограничены: последние 20 значений |
| PR-3 | Обновление идемпотентно: повторная обработка того же `transaction_id` не меняет профиль. Обработанные id хранятся в Redis с TTL |
| PR-4 | Не обновляет профиль транзакциями с решением `BLOCK`, чтобы фрод не «обучал» профиль жертвы |

### 5.5. Live Monitor

| ID | Требование |
|----|-----------|
| LM-1 | Читает `transactions-scored` и поддерживает одно общее состояние `MonitorState`: счётчики ALLOW/FLAG/BLOCK, RPS за последние 10 с, доля degraded, p99 задержки, последние 50 событий FLAG/BLOCK |
| LM-2 | WebSocket `/ws/monitor`: при подключении клиент сразу получает текущее состояние, затем обновления не чаще 10 раз в секунду |
| LM-3 | **Conflation.** Медленный клиент получает последнее состояние, промежуточные пропускаются. Сервер не буферизует больше одного состояния на клиента |
| LM-4 | Клиент может прислать фильтр `{"type":"subscribe","decisions":["BLOCK"],"userId":"..."}`. Фильтр применяется к списку событий, счётчики остаются общими |
| LM-5 | `GET /api/v1/monitor/state` возвращает тот же снимок состояния по REST |
| LM-6 | `GET /` отдаёт статическую HTML-страницу (чистый JS), которая подключается к WebSocket |

## 6. Фичи

| Фича | Источник | Обновление |
|------|----------|-----------|
| `amount`, `log_amount`, `currency`, `hour_of_day`, `merchant_category`, `channel`, `country` | запрос | — |
| `tx_count_1m`, `tx_count_5m`, `tx_count_60m` | `win:{userId}` (ZSET) | синхронно, Lua |
| `amount_sum_5m` | `win:{userId}` | синхронно, Lua |
| `small_tx_count_10m`, `small_tx_distinct_merchants_10m` | `small:{userId}` (ZSET, сумма < $2) | синхронно, Lua |
| `travel_speed_kmh` | `last:{userId}` (гео и время прошлой транзакции) | синхронно, Lua |
| `amount_to_avg_ratio`, `user_tx_count` | `prof:{userId}` (Hash) | асинхронно, Profile Worker |
| `is_new_device`, `is_new_ip`, `is_new_country` | `dev:`, `ip:`, `cty:{userId}` (Set) | асинхронно, Profile Worker |

Облегчённая модель использует только фичи из запроса.

## 7. Правила и решение

### 7.1. Правила

Все пороги задаются в `application.yml`. Значения ниже — стартовые.

| ID | Условие | Действие |
|----|---------|----------|
| R1 | `tx_count_1m ≥ 8` | +0,5 к rule score |
| R2 | `is_new_device && is_new_country && amount_to_avg_ratio ≥ 3` | +0,6 |
| R3 | `small_tx_count_10m ≥ 5 && small_tx_distinct_merchants_10m ≥ 3` | +0,5 |
| R4 | `amount_to_avg_ratio ≥ 10` | +0,4 |
| R5 | `travel_speed_kmh ≥ 900` | +0,7 |
| R6 | `amount ≥ hard_limit` (по умолчанию $10 000) | сразу `BLOCK` |

Rule score — сумма срабатываний, ограниченная значением 1,0. Каждое сработавшее правило попадает в `reasons`.

### 7.2. ML

- **Полная модель:** все фичи раздела 6. Gradient boosting (XGBoost или LightGBM), экспорт в ONNX.
- **Облегчённая модель:** только фичи запроса.
- **Обучение:** на датасете из Transaction Mock (TM-7), в `ml/` (Python). Метрики PR-AUC, recall и precision на отложенной выборке фиксируются в `ml/README.md`.
- **Контрактный тест:** на одних и тех же 100 строках ONNX в Java и Python дают результат с разницей не больше 1e-5.

### 7.3. Итоговое решение

```
risk_score = max(ml_score, rule_score)     # настраивается: max или взвешенная сумма
BLOCK, если R6 сработало или risk_score ≥ 0.85
FLAG,  если risk_score ≥ 0.50
ALLOW  иначе
```

## 8. Хранилища и сообщения

### 8.1. Kafka

| Топик | Ключ | Сообщение | Партиции |
|-------|------|-----------|----------|
| `transactions-scored` | `user_id` | `ScoredTransaction` (Protobuf) | 6 |
| `transactions-scored.retry` | `user_id` | то же | 6 |
| `transactions-scored.dlq` | `user_id` | то же + заголовки с ошибкой | 1 |
| `transactions-labels` | `transaction_id` | `TransactionLabel` (Protobuf) | 3 |

Схемы сообщений лежат в `proto/src/main/proto/risk/v1/events.proto`.

### 8.2. PostgreSQL

```sql
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
```

Миграции — Flyway, в модуле `persistence-worker`.

## 9. Нефункциональные требования

| ID | Требование |
|----|-----------|
| NF-1 | **Задержка Scoring:** p99 < 30 мс, p50 < 10 мс при 1 000 RPS на ноутбуке разработчика (железо указывается в отчёте) |
| NF-2 | **Пропускная способность:** найти и задокументировать максимальный RPS, при котором p99 < 30 мс |
| NF-3 | **Устойчивость:** при остановке Redis Scoring продолжает отвечать (degraded), при остановке Kafka и Postgres отвечает без деградации |
| NF-4 | **Наблюдаемость:** метрики `scoring_step_duration_seconds{step}`, `scoring_decisions_total{decision}`, `scoring_degraded_total`, `scoring_kafka_dropped_total`, consumer lag. Дашборд Grafana хранится в репозитории |
| NF-5 | **Тесты:** unit-тесты на правила и маппинг; интеграционные тесты на Testcontainers для Redis, Kafka и Postgres; контрактный тест ONNX; нагрузочные тесты на k6 |
| NF-6 | **Запуск:** `docker compose up` поднимает инфраструктуру, `./gradlew bootRun` или compose-профиль `apps` поднимает сервисы |
| NF-7 | **Стек:** JDK 25, Java и Kotlin 2.3 (распределение в §3), Spring Boot 4.1 (WebFlux, корутины в Kotlin-сервисах), Spring gRPC (встроен в Boot 4.1), spring-kafka, jOOQ, Flyway, Lettuce, ONNX Runtime |

## 10. Критерии приёмки проекта

1. `docker compose up` и запуск сервисов дают работающий поток: мок → скоринг → Kafka → Postgres и Live Monitor.
2. Каждый паттерн из TM-4 при запуске сценарием получает `FLAG` или `BLOCK` с понятными `reasons`.
3. Есть отчёт нагрузочного теста с p50, p95, p99 и RPS и скриншотом Grafana.
4. Есть отчёт качества: precision и recall по паттернам, отдельно для degraded-режима.
5. README содержит схему, бюджет задержки, инструкцию запуска и раздел «Решения и компромиссы».
6. CI (GitHub Actions) собирает проект и прогоняет тесты.
