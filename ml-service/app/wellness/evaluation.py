"""Model comparison, cross-validation, calibration checks and risk bands."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Iterable

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, clone
from sklearn.calibration import CalibratedClassifierCV
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, brier_score_loss, f1_score, log_loss, roc_auc_score
from sklearn.model_selection import RepeatedStratifiedKFold, StratifiedGroupKFold, StratifiedKFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

Split = tuple[np.ndarray, np.ndarray]

# Lower index = simpler / more explainable; used for the tie-break rule.
SIMPLICITY_ORDER: tuple[str, ...] = ("logreg", "hist_gb", "random_forest")


def candidate_models(seed: int, n_rows: int) -> dict[str, BaseEstimator]:
    """Unfitted candidates. Trees are kept shallow-ish on small data."""
    small = n_rows < 5000
    return {
        "logreg": Pipeline(
            [("scale", StandardScaler()), ("clf", LogisticRegression(max_iter=5000, C=1.0, random_state=seed))]
        ),
        "random_forest": RandomForestClassifier(
            n_estimators=300, min_samples_leaf=10 if small else 20, n_jobs=-1, random_state=seed
        ),
        "hist_gb": HistGradientBoostingClassifier(
            learning_rate=0.05,
            max_iter=150 if small else 300,
            max_depth=3,
            l2_regularization=1.0,
            early_stopping=False,
            random_state=seed,
        ),
    }


def baseline_model() -> DummyClassifier:
    """Majority-class accuracy with prior probabilities (AUC 0.5)."""
    return DummyClassifier(strategy="prior")


def make_splits(
    y: np.ndarray, seed: int, folds: int, repeats: int = 1, groups: np.ndarray | None = None
) -> list[Split]:
    """Stratified (optionally repeated, optionally grouped) CV splits."""
    dummy = np.zeros(len(y))
    if groups is None:
        if repeats == 1:
            cv: Iterable[Split] = StratifiedKFold(folds, shuffle=True, random_state=seed).split(dummy, y)
        else:
            cv = RepeatedStratifiedKFold(n_splits=folds, n_repeats=repeats, random_state=seed).split(dummy, y)
        return [(tr, te) for tr, te in cv]
    out: list[Split] = []
    for r in range(repeats):
        sgk = StratifiedGroupKFold(folds, shuffle=True, random_state=seed + r)
        out.extend((tr, te) for tr, te in sgk.split(dummy, y, groups))
    return out


@dataclass
class CVResult:
    """Fold-level metrics plus out-of-fold probabilities (mean over repeats)."""

    name: str
    fold_metrics: dict[str, list[float]]
    oof_proba: np.ndarray

    def summary(self) -> dict[str, dict[str, float]]:
        return {
            k: {"mean": round(float(np.mean(v)), 4), "std": round(float(np.std(v)), 4)}
            for k, v in self.fold_metrics.items()
        }

    def mean(self, metric: str) -> float:
        return float(np.mean(self.fold_metrics[metric]))


def _metrics(y_true: np.ndarray, proba: np.ndarray, n_classes: int) -> dict[str, float]:
    labels = np.arange(n_classes)
    pred = proba.argmax(axis=1)
    out = {"accuracy": accuracy_score(y_true, pred), "log_loss": log_loss(y_true, proba, labels=labels)}
    if n_classes == 2:
        out["roc_auc"] = roc_auc_score(y_true, proba[:, 1]) if len(np.unique(y_true)) == 2 else float("nan")
        out["brier"] = brier_score_loss(y_true, proba[:, 1])
    else:
        onehot = np.eye(n_classes)[y_true]
        out["f1_macro"] = f1_score(y_true, pred, average="macro", labels=labels, zero_division=0)
        out["brier"] = float(np.mean(np.sum((proba - onehot) ** 2, axis=1)))
    return {k: float(v) for k, v in out.items()}


EstimatorFactory = Callable[[np.ndarray], BaseEstimator]
"""Builds an unfitted estimator given the training-row indices of a fold."""


def cross_validate(
    name: str, factory: EstimatorFactory, X: pd.DataFrame, y: np.ndarray, splits: list[Split], n_classes: int
) -> CVResult:
    """Fit/evaluate on every split; OOF probabilities are averaged over repeats."""
    fold_metrics: dict[str, list[float]] = {}
    oof_sum = np.zeros((len(y), n_classes))
    oof_cnt = np.zeros(len(y))
    for tr, te in splits:
        est = factory(tr)
        est.fit(X.iloc[tr], y[tr])
        proba = _align_proba(est, est.predict_proba(X.iloc[te]), n_classes)
        for k, v in _metrics(y[te], proba, n_classes).items():
            fold_metrics.setdefault(k, []).append(v)
        oof_sum[te] += proba
        oof_cnt[te] += 1
    return CVResult(name, fold_metrics, oof_sum / np.maximum(oof_cnt, 1)[:, None])


def _align_proba(est: BaseEstimator, proba: np.ndarray, n_classes: int) -> np.ndarray:
    """Place columns by class label (a fold may miss a class)."""
    classes = np.asarray(est.classes_, dtype=int)
    if len(classes) == n_classes:
        return proba
    full = np.zeros((proba.shape[0], n_classes))
    full[:, classes] = proba
    return full


def plain_factory(template: BaseEstimator) -> EstimatorFactory:
    return lambda _tr: clone(template)


def calibrated_factory(
    template: BaseEstimator,
    method: str,
    y: np.ndarray,
    seed: int,
    folds: int,
    groups: np.ndarray | None = None,
) -> EstimatorFactory:
    """CalibratedClassifierCV whose inner CV respects groups when given."""

    def make(tr: np.ndarray) -> BaseEstimator:
        inner = make_splits(y[tr], seed, folds, 1, None if groups is None else groups[tr])
        return CalibratedClassifierCV(clone(template), method=method, cv=inner, ensemble=False)

    return make


def choose_model(results: dict[str, CVResult], metric: str, margin: float) -> str:
    """Best metric wins unless a simpler model is within ``margin`` of it."""
    best = max(results, key=lambda n: results[n].mean(metric))
    best_score = results[best].mean(metric)
    for name in SIMPLICITY_ORDER:
        if name in results and results[name].mean(metric) >= best_score - margin:
            return name
    return best


def reliability_table(y: np.ndarray, p: np.ndarray, bins: int = 10) -> tuple[list[dict[str, float]], float]:
    """Quantile-binned predicted vs observed rate, and expected calibration error."""
    order = np.argsort(p, kind="stable")
    rows: list[dict[str, float]] = []
    ece = 0.0
    for chunk in np.array_split(order, bins):
        if len(chunk) == 0:
            continue
        mp, obs = float(p[chunk].mean()), float(y[chunk].mean())
        ece += len(chunk) / len(p) * abs(mp - obs)
        rows.append({"n": int(len(chunk)), "mean_predicted": round(mp, 4), "observed_rate": round(obs, 4)})
    return rows, round(ece, 4)


def calibration_report(y: np.ndarray, raw: np.ndarray, calibrated: np.ndarray) -> dict[str, object]:
    """Brier + ECE before/after calibration on OOF probabilities.

    Binary: probability of the positive class. Multiclass: one-vs-rest on the
    top-class confidence (is the predicted class right?).
    """
    if raw.shape[1] == 2:
        p_raw, p_cal = raw[:, 1], calibrated[:, 1]
        evt_raw = evt_cal = y
        brier_raw, brier_cal = brier_score_loss(y, p_raw), brier_score_loss(y, p_cal)
    else:
        onehot = np.eye(raw.shape[1])[y]
        brier_raw = float(np.mean(np.sum((raw - onehot) ** 2, axis=1)))
        brier_cal = float(np.mean(np.sum((calibrated - onehot) ** 2, axis=1)))
        p_raw, p_cal = raw.max(axis=1), calibrated.max(axis=1)
        evt_raw = (raw.argmax(axis=1) == y).astype(int)
        evt_cal = (calibrated.argmax(axis=1) == y).astype(int)
    _, ece_raw = reliability_table(evt_raw, p_raw)
    table, ece_cal = reliability_table(evt_cal, p_cal)
    return {
        "brier_uncalibrated": round(float(brier_raw), 4),
        "brier_calibrated": round(float(brier_cal), 4),
        "ece_uncalibrated": ece_raw,
        "ece_calibrated": ece_cal,
        "reliability_calibrated": table,
    }


@dataclass
class RiskBands:
    """Calibrated-probability cut points for Low / Moderate / High."""

    low_below: float
    high_at_or_above: float
    base_rate: float
    odds_ratio: float
    occupancy: dict[str, dict[str, float]] = field(default_factory=dict)

    def band(self, p: float) -> str:
        if p < self.low_below:
            return "Low"
        if p >= self.high_at_or_above:
            return "High"
        return "Moderate"

    def to_card(self) -> dict[str, object]:
        return {
            "low_below": round(self.low_below, 4),
            "high_at_or_above": round(self.high_at_or_above, 4),
            "rule": (
                f"Low = odds at most 1/{self.odds_ratio:g} of the training base odds; "
                f"High = odds at least {self.odds_ratio:g}x the base odds (base rate {self.base_rate:.3f})"
            ),
            "oof_occupancy": self.occupancy,
        }


def derive_bands(y: np.ndarray, p: np.ndarray, odds_ratio: float = 3.0) -> RiskBands:
    """Base-rate-relative bands: odds 3x above / below the training base odds.

    A fixed absolute cut (say 0.5) would put most students in "High" (base
    rate ~58%) and almost no working professionals there (~10%). Anchoring on
    the base odds makes "High" mean "clearly above a typical respondent in
    this segment" for both models.
    """
    base = float(y.mean())
    base_odds = base / (1 - base)

    def to_p(odds: float) -> float:
        return odds / (1 + odds)

    bands = RiskBands(to_p(base_odds / odds_ratio), to_p(base_odds * odds_ratio), base, odds_ratio)
    labels = np.array([bands.band(float(v)) for v in p])
    for name in ("Low", "Moderate", "High"):
        m = labels == name
        bands.occupancy[name] = {
            "share_of_rows": round(float(m.mean()), 4),
            "observed_positive_rate": round(float(y[m].mean()), 4) if m.any() else float("nan"),
        }
    return bands
