"""Train both scoring models and export ONNX and Java contract samples."""

import argparse
from hashlib import sha256
from pathlib import Path

import numpy as np
import onnx
import onnxmltools
import onnxruntime as ort
from onnxmltools.convert.common.data_types import FloatTensorType
from sklearn.metrics import auc, average_precision_score, precision_recall_curve, precision_score, recall_score
from xgboost import XGBClassifier

from feature_matrix import FeatureMatrix, HISTORY, REQUEST, read_dataset


ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = ROOT / "scoring-service/src/main/resources/models"
CONTRACT_CSV = ROOT / "scoring-service/src/test/resources/models/python-scores.csv"


def export_model(name, model, encoder, target):
    graph = onnxmltools.convert_xgboost(
        model, name=f"antifraud_{name}",
        initial_types=[("features", FloatTensorType([None, len(encoder.columns)]))], target_opset=15,
    )
    revision = sha256(graph.SerializeToString()).hexdigest()[:12]
    onnx.helper.set_model_props(graph, {
        "model_version": f"xgboost-{name}-{revision}",
        "feature_columns": ",".join(encoder.columns),
    })
    onnx.checker.check_model(graph)
    onnx.save(graph, target)


def train_one(name, columns, train, holdout, test, output_dir):
    encoder = FeatureMatrix.fit(train, columns)
    model = XGBClassifier(
        n_estimators=120, max_depth=5, learning_rate=0.1, min_child_weight=3, scale_pos_weight=6,
        objective="binary:logistic", eval_metric="logloss", tree_method="hist", random_state=42, n_jobs=2,
    )
    model.fit(encoder.transform(train), train["expected_fraud"])
    path = output_dir / f"{name}.onnx"
    export_model(name, model, encoder, path)

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(path, sess_options=options, providers=["CPUExecutionProvider"])
    features = encoder.transform(holdout)
    scores = session.run(["probabilities"], {"features": features})[0][:, 1]
    max_delta = float(np.max(np.abs(model.predict_proba(features)[:, 1] - scores)))
    if max_delta > 1e-5:
        raise ValueError(f"{name}: XGBoost и ONNX разошлись на {max_delta:.8g}")

    print(f"{name} ({path.stat().st_size} bytes) max_export_delta={max_delta:.8g}")
    print_model_metrics("  holdout", holdout["expected_fraud"], scores)
    test_scores = None
    if test is not None:
        test_scores = session.run(["probabilities"], {"features": encoder.transform(test)})[0][:, 1]
        print_model_metrics("  test", test["expected_fraud"], test_scores)
    return scores, test_scores


def print_model_metrics(name, truth, scores):
    precision, recall, _ = precision_recall_curve(truth, scores)
    print(f"{name} PR-AUC={auc(recall, precision):.6f} AP={average_precision_score(truth, scores):.6f}")
    for threshold in (0.50, 0.85):
        print_metrics(truth, scores >= threshold, f"  threshold={threshold:.2f}")


def print_metrics(truth, prediction, label):
    print(f"{label} precision={precision_score(truth, prediction, zero_division=0):.6f} "
          f"recall={recall_score(truth, prediction):.6f} "
          f"false_positives={int((~truth & prediction).sum())}")


def compare_weights(data, full, light, weights):
    history_score = (
        (data["tx_count_1m"] >= 8) * 0.5
        + (data["is_new_device"] & data["is_new_country"] & (data["amount_to_avg_ratio"] >= 3)) * 0.6
        + ((data["small_tx_count_10m"] >= 5) & (data["small_tx_distinct_merchants_10m"] >= 3)) * 0.5
        + (data["amount_to_avg_ratio"] >= 10) * 0.4
        + (data["travel_speed_kmh"] >= 900) * 0.7
    ).clip(upper=1.0)
    hard_block = (data["currency"] == "USD") & (data["amount"] >= 1_000_000)
    truth = data["expected_fraud"]
    for ml_weight in weights:
        for mode, scores in (("full", ml_weight * full + (1 - ml_weight) * history_score),
                             ("light", ml_weight * light)):
            flagged = hard_block | (scores >= 0.50)
            label = f"{mode:5} ml_weight={ml_weight:.2f}"
            print_metrics(truth, flagged, label)
            print_metrics(truth, hard_block | (scores >= 0.85), "  block")
            if mode == "full":
                print("  pattern recall", (data.loc[truth].assign(flagged=flagged[truth])
                                            .groupby("pattern")["flagged"].mean().round(4).to_dict()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--train", type=Path, required=True)
    parser.add_argument("--holdout", type=Path, required=True)
    parser.add_argument("--test", type=Path)
    parser.add_argument("--models", type=Path, default=MODEL_DIR)
    parser.add_argument("--contract", type=Path, default=CONTRACT_CSV)
    args = parser.parse_args()
    paths = [path.resolve() for path in (args.train, args.holdout, args.test) if path is not None]
    if len(set(paths)) != len(paths):
        parser.error("Для каждой выборки нужен отдельный файл")

    train = read_dataset(args.train)
    holdout = read_dataset(args.holdout)
    if len(holdout) < 100:
        parser.error("Для проверки Java/Python нужно минимум 100 контрольных строк")
    test = read_dataset(args.test) if args.test is not None else None
    args.models.mkdir(parents=True, exist_ok=True)
    full, test_full = train_one("full", REQUEST + HISTORY, train, holdout, test, args.models)
    light, test_light = train_one("light", REQUEST, train, holdout, test, args.models)
    print("Holdout weight comparison")
    compare_weights(holdout, full, light, (0.70, 0.80, 0.85, 0.90, 0.95))
    if test is not None:
        print("Independent test at the selected weight")
        compare_weights(test, test_full, test_light, (0.90,))

    contract = holdout.loc[:, list(REQUEST + HISTORY)].assign(python_full=full, python_light=light)
    truth = holdout["expected_fraud"]
    negatives = min(100 - min(50, int(truth.sum())), int((~truth).sum()))
    positive = contract[truth].sample(n=100 - negatives, random_state=42)
    negative = contract[~truth].sample(n=negatives, random_state=42)
    args.contract.parent.mkdir(parents=True, exist_ok=True)
    contract.loc[positive.index.union(negative.index)].to_csv(args.contract, index=False, lineterminator="\n")


if __name__ == "__main__":
    main()
