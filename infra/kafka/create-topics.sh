#!/bin/bash
# Создаёт топики при старте compose. Повторный запуск безопасен (--if-not-exists).
# Список топиков и партиций: SPEC.md §8.1.
set -euo pipefail

BOOTSTRAP=kafka:29092
TOPICS=/opt/kafka/bin/kafka-topics.sh

create() {
  local name=$1 partitions=$2
  $TOPICS --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor 1
}

create transactions-scored 6

# TODO(you): создай остальные топики из SPEC.md §8.1 по примеру выше:
#   transactions-scored.retry, transactions-scored.dlq, transactions-labels.
# Вопрос на подумать: почему у retry-топика столько же партиций, сколько у основного,
# а DLQ хватает одной?

$TOPICS --bootstrap-server "$BOOTSTRAP" --list
