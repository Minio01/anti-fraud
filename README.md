# Anti-Fraud & Risk Engine

Real-time скоринг финансовых транзакций: решение `ALLOW` / `FLAG` / `BLOCK` меньше чем за 30 мс. Фичи считаются в Redis через скользящие окна, модель работает внутри процесса (ONNX), события идут через Kafka, а живая лента решений отображается по WebSocket.

> 🚧 В разработке.

## Стек

Java 25 · Kotlin 2.3 · Spring Boot 4.1 (WebFlux) · gRPC · Redis (Lua) · Kafka · PostgreSQL (jOOQ, Flyway) · ONNX Runtime · Prometheus · Grafana · Testcontainers

Скоринг и аудит пишутся на Java. Генератор транзакций, профиль и Live Monitor — на Kotlin с корутинами.

## Архитектура

_Схема и бюджет задержки — после этапа 7. Сейчас подробности в [ARCHITECTURE.md](ARCHITECTURE.md)._

## Результаты

_Нагрузочный тест (p50, p95, p99, RPS) и качество обнаружения (precision и recall по паттернам) — после этапов 5 и 7._

## Запуск

```bash
cp .env.example .env          # заполнить значения
docker compose up -d          # Postgres, Redis, Kafka, Prometheus, Grafana
./gradlew :scoring-service:bootRun
```

## Решения и компромиссы

Ключевые решения описаны в [архитектуре](ARCHITECTURE.md#2-принятые-решения).

## Документация

- [SPEC.md](SPEC.md) — техническое задание
- [ARCHITECTURE.md](ARCHITECTURE.md) — архитектура
- [openapi.yaml](openapi.yaml) — REST API
- [proto/](proto/src/main/proto/risk/v1/) — gRPC- и Kafka-контракты
