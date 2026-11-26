# Модели скоринга

Scoring использует две бинарные модели XGBoost. Полная получает все 19 исходных признаков из `SPEC.md` §6. Облегчённая получает семь признаков запроса и используется при недоступности Redis. `expected_fraud` служит целевой меткой, `pattern` — полем для анализа качества. Категории кодируются one-hot. Список колонок сохраняется в метаданных ONNX в порядке, в котором их читает Java.

## Воспроизведение

Соберите `:transaction-mock:bootJar` на JDK 25. Для каждой выборки запускайте Transaction Mock на порту 8081 с указанным `--mock.runtime.seed`; остановите свой экземпляр перед сменой seed.

| Файл в `ml/data/` | `mock.runtime.seed` | `size` | `seed` в запросе | Фродовых строк |
|---|---:|---:|---:|---:|
| `train.csv` | 42 | 240 000 | 42 | 3607 |
| `holdout.csv` | 43 | 60 000 | 1042 | 914 |
| `test.csv` | 44 | 60 000 | 2042 | 900 |

Пример запроса из PowerShell для `train.csv`. Для остальных выборок подставьте значения и имя файла из таблицы.

```powershell
New-Item -ItemType Directory -Path ml/data -Force | Out-Null
$body = @{ size = 240000; fraudRate = 0.015; seed = 42 } | ConvertTo-Json -Compress
Invoke-WebRequest http://localhost:8081/api/v1/mock/datasets -Method Post `
    -ContentType 'application/json' -Body $body -OutFile ml/data/train.csv
```

Из корня репозитория на Python 3.12 выполните следующие команды.

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r ml/requirements.txt
.\.venv\Scripts\python.exe ml/train.py --train ml/data/train.csv `
    --holdout ml/data/holdout.csv --test ml/data/test.csv
.\gradlew.bat :scoring-service:test --tests '*OnnxContractTest'
```

Скрипт заменяет `full.onnx` и `light.onnx` в `scoring-service/src/main/resources/models/` и сохраняет эталон Python в `scoring-service/src/test/resources/models/python-scores.csv`. Параметры `--models` и `--contract` позволяют направить результат в отдельный каталог. `OnnxContractTest` проверяет Java и Python на 100 общих строках. После замены моделей пересоберите и перезапустите Scoring.

## Качество на независимой выборке

Модели обучены на `train.csv`. Для веса правил использовали сравнение на `holdout.csv`. Таблицы ниже рассчитаны на отдельном `test.csv` из 60 000 транзакций, в том числе 900 фродовых. PR-AUC рассчитана трапецеидально. Precision и recall в первой таблице относятся к самой вероятности модели, до правил. [Определения scikit-learn](https://scikit-learn.org/stable/modules/model_evaluation.html#precision-recall-f-measure-metrics)

| Модель | PR-AUC | Average precision | Precision ≥ 0,50 | Recall ≥ 0,50 | Precision ≥ 0,85 | Recall ≥ 0,85 |
|---|---:|---:|---:|---:|---:|---:|
| Full | 0,9244 | 0,9244 | 82,14% | 88,89% | 96,46% | 84,89% |
| Light | 0,1270 | 0,1288 | 26,69% | 18,44% | 43,75% | 0,78% |

Итоговый риск использует `0.90 × ml_score + 0.10 × rule_score`, кроме R6, который устанавливает 1 и блокирует. В режиме без Redis R6 остаётся доступен. Ни один платёж тестовой выборки не достигал лимита R6.

| Режим и решение | Precision | Recall | Ложные срабатывания из 59 100 легитимных |
|---|---:|---:|---:|
| Full · FLAG или BLOCK | 84,52% | 88,56% | 146 |
| Full · только BLOCK | 97,91% | 83,11% | 16 |
| Light · FLAG или BLOCK | 31,15% | 15,89% | 316 |
| Light · только BLOCK | 66,67% | 0,22% | 1 |

С полной моделью доля фродовых строк, получивших FLAG или BLOCK, различалась по сценариям. CARD_TESTING — 94,06%, IMPOSSIBLE_TRAVEL — 90,91%, VELOCITY_BURST — 88,55%, ACCOUNT_TAKEOVER — 71,11%, AMOUNT_ANOMALY — 42,86%. Выгрузки синтетические и используют одинаковое семейство генераторов. Онлайн-профиль до этапа 5 может быть пустым, даже если Redis доступен. Это ухудшает обнаружение сценариев, зависящих от среднего чека или известных атрибутов.

На 100 строках максимальная абсолютная разница Java/Python составила `2.81e-8` для Full и `2.55e-8` для Light при допуске `1e-5`. Перед сохранением эталона скрипт также сравнивает исходный XGBoost с экспортированной моделью в Python.
