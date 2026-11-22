"""Fast per-prediction feature contributions ("drivers").

* Logistic regression: standardized coefficient x standardized input, i.e.
  each feature's additive log-odds contribution relative to the average
  training respondent. Exact and microseconds per call.
* Tree models: SHAP TreeExplainer (built once at load time, then reused).

Contributions are computed on the uncalibrated base model. Calibration is a
monotone map of its score, so directions are unchanged.
"""

from __future__ import annotations

from typing import Any, Protocol, Sequence

import numpy as np
from sklearn.pipeline import Pipeline


class Contributor(Protocol):
    def contributions(self, x: np.ndarray, class_index: int) -> np.ndarray:
        """Per-feature contribution (shape ``(n_features,)``) for one row."""
        ...


class LinearContributor:
    """Exact contributions for ``StandardScaler -> LogisticRegression``."""

    def __init__(self, pipeline: Pipeline) -> None:
        scaler, clf = pipeline.named_steps["scale"], pipeline.named_steps["clf"]
        self._mean = np.asarray(scaler.mean_, dtype=float)
        self._scale = np.asarray(scaler.scale_, dtype=float)
        coef = np.asarray(clf.coef_, dtype=float)
        # Multinomial: centre across classes so each class has its own direction.
        self._coef = coef if coef.shape[0] == 1 else coef - coef.mean(axis=0, keepdims=True)

    def contributions(self, x: np.ndarray, class_index: int) -> np.ndarray:
        z = (np.asarray(x, dtype=float).ravel() - self._mean) / self._scale
        row = self._coef[0] if self._coef.shape[0] == 1 else self._coef[class_index]
        return row * z

    def standardized_coefficients(self) -> np.ndarray:
        return self._coef.copy()


class TreeContributor:
    """SHAP values for a fitted tree ensemble (HistGradientBoosting / RF)."""

    def __init__(self, model: Any) -> None:
        import shap  # imported lazily: only tree models need it

        self._explainer = shap.TreeExplainer(model)

    def contributions(self, x: np.ndarray, class_index: int) -> np.ndarray:
        vals = self._explainer.shap_values(np.asarray(x, dtype=float).reshape(1, -1), check_additivity=False)
        if isinstance(vals, list):  # older SHAP: one array per class
            return np.asarray(vals[class_index])[0]
        vals = np.asarray(vals)
        if vals.ndim == 2:  # binary model with a single log-odds output
            return vals[0] if class_index == 1 else -vals[0]
        return vals[0, :, class_index]


def build_contributor(base_model: Any) -> Contributor:
    if isinstance(base_model, Pipeline) and "clf" in base_model.named_steps:
        return LinearContributor(base_model)
    return TreeContributor(base_model)


def top_drivers(
    contrib: np.ndarray,
    names: Sequence[str],
    labels: Sequence[str],
    raw_values: dict[str, Any],
    exclude: set[str],
    subject: str,
    k: int = 3,
) -> list[dict[str, Any]]:
    """The ``k`` largest-magnitude contributions among provided inputs.

    Imputed (missing) inputs are excluded: we only explain what the user told us.
    """
    order = np.argsort(-np.abs(contrib), kind="stable")
    out: list[dict[str, Any]] = []
    for i in order:
        name = names[i]
        if name in exclude or contrib[i] == 0:
            continue
        direction = "increases" if contrib[i] > 0 else "decreases"
        out.append(
            {
                "feature": name,
                "label": labels[i],
                "value": raw_values.get(name),
                "direction": direction,
                "effect": f"{direction} {subject}",
                "impact": round(float(contrib[i]), 4),
            }
        )
        if len(out) == k:
            break
    return out
