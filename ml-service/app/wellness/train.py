"""Train, evaluate and save every wellness check-in model.

Usage (from ``ml-service/``)::

    python -m app.wellness.train                 # data/raw -> models/wellness
    python -m app.wellness.train --data-dir D --out-dir O

For each model: compare logistic regression / random forest /
HistGradientBoosting against a majority-class baseline with stratified
(repeated / grouped where needed) CV, pick the simplest model within
``SIMPLICITY_MARGIN`` of the best, measure calibration with nested CV, fit
the final calibrated model on all rows and write ``<name>.joblib`` plus
``<name>.model_card.json``.
"""

from __future__ import annotations

import argparse
import json
import logging
import platform
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

import joblib
import numpy as np
import pandas as pd
import scipy
import shap
import sklearn
from sklearn.base import clone
from sklearn.calibration import CalibratedClassifierCV

from . import data as D
from . import evaluation as E
from .config import (
    CV_FOLDS,
    CV_REPEATS_SMALL,
    FORBIDDEN_COLUMNS,
    MODEL_DIR,
    MODEL_MAX_CONFIDENCE,
    RANDOM_SEED,
    RAW_DATA_DIR,
    SIMPLICITY_MARGIN,
)
from .display_config import SLEEP_LABELS
from .schema import (
    MOOD_FEATURES,
    MOOD_WORKING_FEATURES,
    SLEEP_CLASSES,
    SLEEP_CORE_FEATURES,
    SLEEP_EXTENDED_FEATURES,
    FeatureSpec,
    feature_names,
)

log = logging.getLogger("wellness.train")

BUNDLE_VERSION = 1
CALIBRATION_TOLERANCE = 0.001  # allowed Brier increase before calibration is rejected
CONFIDENCE_LEVELS: tuple[str, ...] = ("low", "medium", "high")

_COMMON_LIMITATIONS = [
    "Not a diagnosis: outputs are screening-style estimates for self-reflection, never a clinical label.",
    "Trained on public Kaggle survey data; labels are self-reported / survey-derived, not clinician assessed.",
    "Associations only; drivers describe what moves the model's estimate, not causes.",
]


@dataclass
class ModelJob:
    """Everything that differs between the models."""

    name: str
    source_key: str
    dataset: D.CleanDataset
    specs: tuple[FeatureSpec, ...]
    task: Literal["binary", "multiclass"]
    metric: Literal["roc_auc", "f1_macro"]
    repeats: int
    group_by: list[str] | None  # features whose identical tuples form CV groups
    calibration: Literal["isotonic", "sigmoid"]
    range_quantiles: tuple[float, float]
    limitations: list[str]
    # Columns deliberately left out; a reference CV with them added back goes in the card.
    excluded_features: dict[str, str] = field(default_factory=dict)  # name -> reason


def _library_versions() -> dict[str, str]:
    return {
        "python": platform.python_version(),
        "scikit-learn": sklearn.__version__,
        "numpy": np.__version__,
        "pandas": pd.__version__,
        "scipy": scipy.__version__,
        "shap": shap.__version__,
        "joblib": joblib.__version__,
    }


def _confidence_ceiling(task: str, chosen: E.CVResult, baseline: E.CVResult) -> tuple[str, str]:
    """Best confidence a model may ever report, from its CV quality.

    binary:     AUC - 2*sd < 0.60 -> low (weak); AUC < 0.75 -> medium; else high
    multiclass: macro-F1 - 2*sd < baseline + 0.10 -> low; macro-F1 < 0.70 -> medium; else high
    """
    if task == "binary":
        m, sd = chosen.mean("roc_auc"), float(np.std(chosen.fold_metrics["roc_auc"]))
        if m - 2 * sd < 0.60:
            return "low", f"weak: ROC-AUC {m:.3f} (sd {sd:.3f}) not clearly above baseline"
        if m < 0.75:
            return "medium", f"moderate discrimination: ROC-AUC {m:.3f}"
        return "high", f"ROC-AUC {m:.3f} (sd {sd:.3f})"
    m, sd = chosen.mean("f1_macro"), float(np.std(chosen.fold_metrics["f1_macro"]))
    base = baseline.mean("f1_macro")
    if m - 2 * sd < base + 0.10:
        return "low", f"weak: macro-F1 {m:.3f} (sd {sd:.3f}) vs baseline {base:.3f}"
    if m < 0.70:
        return "medium", f"moderate: macro-F1 {m:.3f} (sd {sd:.3f}) vs baseline {base:.3f}"
    return "high", f"macro-F1 {m:.3f} (sd {sd:.3f})"


def train_job(job: ModelJob, seed: int = RANDOM_SEED, folds: int = CV_FOLDS) -> tuple[dict[str, Any], dict[str, Any]]:
    """Run the full evaluation for one model and return ``(bundle, card)``."""
    names = feature_names(job.specs)
    D.assert_no_forbidden(names)
    D.assert_no_forbidden(D.SOURCE_COLUMNS[job.source_key]["features"])
    X = job.dataset.X[names]
    y = job.dataset.y
    n_classes = 2 if job.task == "binary" else len(SLEEP_CLASSES)
    groups = None if job.group_by is None else D.feature_groups(job.dataset.X[job.group_by])
    splits = E.make_splits(y, seed, folds, job.repeats, groups)
    log.info("[%s] rows=%d features=%s splits=%d", job.name, len(y), names, len(splits))

    # 1) baseline + candidates
    results: dict[str, E.CVResult] = {
        "baseline_majority": E.cross_validate("baseline_majority", E.plain_factory(E.baseline_model()), X, y, splits, n_classes)
    }
    templates = E.candidate_models(seed, len(y))
    for cname, template in templates.items():
        results[cname] = E.cross_validate(cname, E.plain_factory(template), X, y, splits, n_classes)
        log.info("[%s] %-14s %s=%.4f", job.name, cname, job.metric, results[cname].mean(job.metric))
    candidates = {k: v for k, v in results.items() if k in templates}
    chosen = E.choose_model(candidates, job.metric, SIMPLICITY_MARGIN)
    best = max(candidates, key=lambda n: candidates[n].mean(job.metric))
    reason = (
        f"{chosen} has the best {job.metric}"
        if chosen == best
        else f"{chosen} is within {SIMPLICITY_MARGIN} {job.metric} of {best} and simpler to explain"
    )
    log.info("[%s] chosen=%s (%s)", job.name, chosen, reason)

    # 2) calibration, evaluated with nested CV on the same outer splits
    template = templates[chosen]
    cal_cv = E.cross_validate(
        "calibrated",
        E.calibrated_factory(template, job.calibration, y, seed, folds, groups),
        X, y, splits, n_classes,
    )
    calib = E.calibration_report(y, results[chosen].oof_proba, cal_cv.oof_proba)
    log.info("[%s] brier raw=%.4f calibrated=%.4f", job.name, calib["brier_uncalibrated"], calib["brier_calibrated"])

    # Keep calibration only if it does not make nested-CV Brier worse. On
    # tiny data (sleep: ~20 rows per inner fold) a sigmoid fit can hurt.
    use_calibration = calib["brier_calibrated"] <= calib["brier_uncalibrated"] + CALIBRATION_TOLERANCE
    if use_calibration:
        calib_decision = (f"kept: nested-CV Brier {calib['brier_uncalibrated']} -> {calib['brier_calibrated']}, "
                          f"ECE {calib['ece_uncalibrated']} -> {calib['ece_calibrated']}")
    else:
        calib_decision = (f"rejected: {job.calibration} calibration worsened nested-CV Brier "
                          f"{calib['brier_uncalibrated']} -> {calib['brier_calibrated']}; shipping uncalibrated probabilities")
    log.info("[%s] calibration %s", job.name, calib_decision)
    oof_final = cal_cv.oof_proba if use_calibration else results[chosen].oof_proba

    # 3) final model on all rows (inner CV is grouped for sleep)
    if use_calibration:
        inner = E.make_splits(y, seed, folds, 1, groups)
        model = CalibratedClassifierCV(clone(template), method=job.calibration, cv=inner, ensemble=False).fit(X, y)
        base_model = model.calibrated_classifiers_[0].estimator
    else:
        model = base_model = clone(template).fit(X, y)

    lo_q, hi_q = job.range_quantiles
    train_ranges = {c: [float(X[c].quantile(lo_q)), float(X[c].quantile(hi_q))] for c in names}
    impute_values = {c: float(X[c].median()) for c in names}
    ceiling, ceiling_reason = _confidence_ceiling(job.task, results[chosen], results["baseline_majority"])
    cap = MODEL_MAX_CONFIDENCE.get(job.name)
    if cap is not None and CONFIDENCE_LEVELS.index(cap) < CONFIDENCE_LEVELS.index(ceiling):
        ceiling, ceiling_reason = cap, f"capped at '{cap}' by MODEL_MAX_CONFIDENCE ({ceiling_reason})"

    bands = E.derive_bands(y, oof_final[:, 1]) if job.task == "binary" else None

    extras: dict[str, Any] = {}
    if job.task == "binary" and "age" in names:
        no_age = [c for c in names if c != "age"]
        abl = E.cross_validate("no_age", E.plain_factory(template), X[no_age], y, splits, n_classes)
        extras["ablation_without_age"] = {"model": chosen, "metrics": abl.summary()}
    if job.excluded_features:
        with_excl = names + list(job.excluded_features)
        ref = E.cross_validate("reference", E.plain_factory(template), job.dataset.X[with_excl], y, splits, n_classes)
        extras["excluded_features"] = {
            "features": job.excluded_features,
            "reference_cv_with_them": {"model": chosen, "features": with_excl, "metrics": ref.summary()},
        }
    if groups is not None:
        conflict = D.label_conflict_rate(X, y)
        extras["label_conflict_rate"] = round(conflict, 4)
        extras["accuracy_ceiling"] = round(1 - conflict, 4)
        extras["unique_feature_tuples"] = int(len(np.unique(groups)))

    classes = ["not_depressed", "depressed"] if job.task == "binary" else list(SLEEP_CLASSES)
    bundle: dict[str, Any] = {
        "bundle_version": BUNDLE_VERSION,
        "name": job.name,
        "task": job.task,
        "features": names,
        "feature_labels": [s.label for s in job.specs],
        "classes": classes,
        "model": model,
        "base_model": base_model,
        "calibrated": use_calibration,
        "algorithm": chosen,
        "impute_values": impute_values,
        "train_ranges": train_ranges,
        "bands": None if bands is None else {"low_below": bands.low_below, "high_at_or_above": bands.high_at_or_above},
        "confidence_ceiling": ceiling,
    }

    ds = job.dataset
    card: dict[str, Any] = {
        "name": job.name,
        "bundle_version": BUNDLE_VERSION,
        "trained_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "intended_use": "NeuroNest wellness check-in: self-reflection estimate with explanation. Not a diagnosis.",
        "task": job.task,
        "algorithm": chosen,
        "selection_reason": reason,
        "calibration_method": (f"CalibratedClassifierCV(method='{job.calibration}', ensemble=False)"
                               if use_calibration else "none (see calibration.decision)"),
        "features": [
            {**s.to_card(), "training_range": train_ranges[s.name], "impute_value_if_missing": impute_values[s.name]}
            for s in job.specs
        ],
        "label_mapping": ds.label_mapping if job.task == "binary" else {c: i for i, c in enumerate(classes)},
        "output": (
            "risk band (Low / Moderate / High) + calibrated probability"
            if job.task == "binary"
            else "pattern code (see display_labels) + class probabilities (see calibration_method)"
        ),
        "display_labels": None if job.task == "binary" else {
            cls: {"code": lab.code, "display_text": lab.display_text, "see_doctor": lab.see_doctor}
            for cls, lab in SLEEP_LABELS.items()
        },
        "risk_bands": None if bands is None else bands.to_card(),
        "confidence": {
            "ceiling": ceiling,
            "ceiling_reason": ceiling_reason,
            "rule": "start at the ceiling; -1 level per imputed (missing optional) input the model consumes (max -2; "
            "sleep: -1 if some but not all boosters are given, none on the core-only path); "
            "-1 if any provided input is outside the training range",
        },
        "dataset": {
            "file": ds.source_file.name,
            "sha256": ds.source_sha256,
            "cleaning": ds.cleaning.to_card(len(ds.frame)),
            "class_counts": {classes[int(k)]: int(v) for k, v in zip(*np.unique(y, return_counts=True))},
            "source_columns_used": D.SOURCE_COLUMNS[job.source_key],
            "never_used_columns": sorted(FORBIDDEN_COLUMNS),
        },
        "cross_validation": {
            "scheme": (f"StratifiedGroupKFold (groups = identical tuples of {job.group_by})" if job.group_by else
                       "RepeatedStratifiedKFold" if job.repeats > 1 else "StratifiedKFold"),
            "folds": folds,
            "repeats": job.repeats,
            "seed": seed,
            "primary_metric": job.metric,
            "candidates": {k: v.summary() for k, v in results.items()},
            "calibrated_chosen_model": cal_cv.summary(),
        },
        "calibration": {"decision": calib_decision, **calib},
        **extras,
        "library_versions": _library_versions(),
        "known_limitations": _COMMON_LIMITATIONS + job.limitations,
    }
    return bundle, card


def build_jobs(raw_dir: Path) -> list[ModelJob]:
    roles = D.discover_datasets(raw_dir)
    student = D.load_student_mood(roles[D.ROLE_STUDENT])
    working = D.load_working_mood(roles[D.ROLE_FINAL])
    sleep = D.load_sleep(roles[D.ROLE_SLEEP])
    sleep_groups = feature_names(SLEEP_CORE_FEATURES)
    sleep_lim = [
        "Sleep dataset is SYNTHETIC (374 rows, 132 after removing exact duplicates); treat outputs as illustrative.",
        "Identical feature rows carry conflicting labels; see label_conflict_rate / accuracy_ceiling.",
        "Training population is adults aged 27-59 with 5.8-8.5 h sleep; inputs outside that are extrapolation.",
        "Class names come from the dataset; present them as sleep *patterns*, not as a diagnosis of insomnia or apnea.",
    ]
    return [
        ModelJob(
            "mood_student", "mood_student", student, MOOD_FEATURES, "binary", "roc_auc", 1, None, "isotonic",
            (0.01, 0.99),
            [
                "Student survey from India (Kaggle 'Student Depression Dataset'); ~58% positive, far above population rates.",
                "Probabilities are calibrated to this survey's base rate, not to the general student population.",
                "Ages concentrate at 18-34; older users are flagged as outside the training range.",
            ],
        ),
        ModelJob(
            "mood_working", "mood_working", working, MOOD_WORKING_FEATURES, "binary", "roc_auc", CV_REPEATS_SMALL,
            None, "sigmoid", (0.01, 0.99),
            [
                "Small dataset: 2,054 working professionals with only ~200 positives (~10%).",
                "Age is deliberately NOT a feature (see excluded_features); an age sent by the app is ignored.",
                "Confidence is capped at 'medium' because of the small sample and likely synthetic origin.",
                "Likely partly synthetic (Kaggle playground-style data); CV AUC should not be read as clinical accuracy.",
            ],
            excluded_features={
                "age": "In this dataset age alone gives ROC-AUC ~0.87 (positive rate ~67% at 18-19, ~0% at 50+), a likely "
                "synthetic artefact that would label almost every older worker Low regardless of their answers.",
            },
        ),
        ModelJob(
            "sleep_pattern_core", "sleep_pattern", sleep, SLEEP_CORE_FEATURES, "multiclass", "f1_macro",
            CV_REPEATS_SMALL, sleep_groups, "sigmoid", (0.0, 1.0), sleep_lim,
        ),
        ModelJob(
            "sleep_pattern_extended", "sleep_pattern", sleep, SLEEP_EXTENDED_FEATURES, "multiclass", "f1_macro",
            CV_REPEATS_SMALL, sleep_groups, "sigmoid", (0.0, 1.0), sleep_lim,
        ),
    ]


def train_all(raw_dir: Path = RAW_DATA_DIR, out_dir: Path = MODEL_DIR) -> dict[str, dict[str, Any]]:
    """Train every model, write artefacts and return the cards by name."""
    out_dir.mkdir(parents=True, exist_ok=True)
    cards: dict[str, dict[str, Any]] = {}
    bundles: dict[str, dict[str, Any]] = {}
    for job in build_jobs(raw_dir):
        bundles[job.name], cards[job.name] = train_job(job)

    # Core vs extended comparison goes into both sleep cards.
    core, ext = cards["sleep_pattern_core"], cards["sleep_pattern_extended"]
    cmp = {
        "core_macro_f1": core["cross_validation"]["candidates"][core["algorithm"]]["f1_macro"],
        "extended_macro_f1": ext["cross_validation"]["candidates"][ext["algorithm"]]["f1_macro"],
        "note": "Extended adds bmi_category, heart_rate, daily_steps. Predictor uses the extended model when any "
        "booster is supplied (missing boosters imputed) and the core model otherwise.",
    }
    core["variant_comparison"] = ext["variant_comparison"] = cmp

    for name in bundles:
        joblib.dump(bundles[name], out_dir / f"{name}.joblib", compress=3)
        (out_dir / f"{name}.model_card.json").write_text(json.dumps(cards[name], indent=2, default=str) + "\n",
                                                          encoding="utf-8")
        log.info("wrote %s", out_dir / f"{name}.joblib")
    return cards


def _print_summary(cards: dict[str, dict[str, Any]]) -> None:
    print("\n=== Wellness models ===")
    for name, card in cards.items():
        cv = card["cross_validation"]
        metric = cv["primary_metric"]
        chosen = cv["candidates"][card["algorithm"]]
        base = cv["candidates"]["baseline_majority"]
        rows = card["dataset"]["cleaning"]
        print(
            f"{name:24s} {card['algorithm']:14s} {metric}={chosen[metric]['mean']:.3f}±{chosen[metric]['std']:.3f} "
            f"(baseline {base[metric]['mean']:.3f}) acc={chosen['accuracy']['mean']:.3f} (baseline {base['accuracy']['mean']:.3f}) "
            f"brier {card['calibration']['brier_uncalibrated']:.3f}->{card['calibration']['brier_calibrated']:.3f} "
            f"rows {rows['rows_before']}->{rows['rows_after']} confidence<= {card['confidence']['ceiling']}"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train all NeuroNest wellness check-in models.")
    parser.add_argument("--data-dir", type=Path, default=RAW_DATA_DIR)
    parser.add_argument("--out-dir", type=Path, default=MODEL_DIR)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING, format="%(message)s")
    cards = train_all(args.data_dir, args.out_dir)
    _print_summary(cards)
    return 0


if __name__ == "__main__":
    sys.exit(main())
