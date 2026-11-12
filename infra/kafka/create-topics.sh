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
create transactions-scored.retry 6
create transactions-scored.dlq 1
create transactions-labels 3

$TOPICS --bootstrap-server "$BOOTSTRAP" --list
