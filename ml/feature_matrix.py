"""Encode the raw mock CSV columns used by the two scoring models."""

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd


REQUEST = ("amount", "log_amount", "currency", "hour_of_day", "merchant_category", "channel", "country")
HISTORY = (
    "tx_count_1m", "tx_count_5m", "tx_count_60m", "amount_sum_5m", "small_tx_count_10m",
    "small_tx_distinct_merchants_10m", "travel_speed_kmh", "amount_to_avg_ratio", "user_tx_count",
    "is_new_device", "is_new_ip", "is_new_country",
)
CATEGORICAL = frozenset(("currency", "merchant_category", "channel", "country"))
BOOLEAN = ("is_new_device", "is_new_ip", "is_new_country", "expected_fraud")


def read_dataset(path: Path) -> pd.DataFrame:
    columns = (*REQUEST, *HISTORY, "expected_fraud", "pattern")
    data = pd.read_csv(path, usecols=list(columns), keep_default_na=False,
                       dtype={name: "string" for name in CATEGORICAL})
    if data.loc[:, list(columns[:-1])].eq("").any().any():
        raise ValueError(f"{path}: отсутствуют значения признаков или меток")
    for name in BOOLEAN:
        values = data[name].astype(str).str.lower().map({"true": True, "false": False})
        if values.isna().any():
            raise ValueError(f"{path}: недопустимое значение в {name}, ожидается true/false")
        data[name] = values.astype(bool)
    if data["expected_fraud"].nunique() != 2:
        raise ValueError(f"{path}: требуются транзакции обоих классов")
    return data


@dataclass(frozen=True)
class FeatureMatrix:
    columns: tuple[str, ...]

    @classmethod
    def fit(cls, data: pd.DataFrame, features: tuple[str, ...]) -> "FeatureMatrix":
        columns = []
        for feature in features:
            if feature in CATEGORICAL:
                columns.extend(f"{feature}={value}" for value in sorted(data[feature].unique()))
            else:
                columns.append(feature)
        return cls(tuple(columns))

    def transform(self, data: pd.DataFrame) -> np.ndarray:
        matrix = np.empty((len(data), len(self.columns)), dtype=np.float32)
        for index, column in enumerate(self.columns):
            feature, separator, category = column.partition("=")
            matrix[:, index] = data[feature].eq(category) if separator else data[feature]
        if not np.isfinite(matrix).all():
            raise ValueError("В матрице признаков есть бесконечность или NaN")
        return matrix
