"""ADR-044 Enmienda 4 (PROPUESTA): el generador con mezcla de curvas de llegada.

Se ejecutan con:  python -m pytest tests -q   (desde paxfide-predictor/)
La huella de referencia es la de la Enmienda 3 (generador original, semilla 20261006, 1 500 convocatorias).
"""
import hashlib
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
import generate_synthetic_dataset as gen  # noqa: E402

LEGACY_SNAPSHOTS_SHA256 = "2d235ef5504720b4370567c33ee29a5ad326c2f3cb035a562a65e48abea0e67a"


def run_generator(out: Path, *extra: str, n: int = 1500) -> dict:
    subprocess.run(
        [sys.executable, str(HERE / "generate_synthetic_dataset.py"), "--out", str(out), "--n-campaigns", str(n), *extra],
        check=True, capture_output=True, text=True,
    )
    return json.loads((out / "manifest.json").read_text())


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_mezcla_desactivada_reproduce_la_huella_de_la_enmienda_3(tmp_path):
    manifest = run_generator(tmp_path, "--curve-mix", "u=1")
    assert sha(tmp_path / "snapshots.csv") == LEGACY_SNAPSHOTS_SHA256
    assert manifest["datasetVersion"] == "synthetic-v1"


def test_la_mezcla_por_defecto_es_reproducible_y_es_v2(tmp_path):
    a = run_generator(tmp_path / "a")
    b = run_generator(tmp_path / "b")
    assert sha(tmp_path / "a" / "snapshots.csv") == sha(tmp_path / "b" / "snapshots.csv")
    assert sha(tmp_path / "a" / "snapshots.csv") != LEGACY_SNAPSHOTS_SHA256
    assert a["datasetVersion"] == "synthetic-v2"
    assert a["synthetic"] is True
    assert a["curveMix"] == {"u": 0.4, "linear": 0.4, "front": 0.2}
    assert a["sha256"] == b["sha256"]


@pytest.mark.parametrize("kind", ["u", "linear", "front"])
@pytest.mark.parametrize("n_days", [15, 60, 90])
def test_cada_curva_esta_normalizada_a_media_1_y_es_positiva(kind, n_days):
    curve = gen._arrival_curve(n_days, kind)
    assert len(curve) == n_days
    assert curve.mean() == pytest.approx(1.0, abs=1e-12)
    assert (curve > 0).all()


def test_las_formas_de_las_tres_curvas():
    u = gen._arrival_curve(60, "u")
    # la en U no cambió: es la fórmula original
    x = np.linspace(0.0, 1.0, 60)
    original = 1.6 * np.exp(-x / 0.12) + 0.35 + 1.1 * np.exp(-(1.0 - x) / 0.08)
    assert np.allclose(u, original / original.mean())
    # la lineal es constante
    assert np.allclose(gen._arrival_curve(60, "linear"), 1.0)
    # la concentrada al inicio solo baja
    front = gen._arrival_curve(60, "front")
    assert (np.diff(front) < 0).all()
    assert front[0] > 2 * front[-1]
    # al 50 % del tiempo, la lineal ha recibido la mitad y la «front» bastante más que la mitad
    assert gen._arrival_curve(60, "linear")[:30].sum() / 60 == pytest.approx(0.5, abs=1e-12)
    assert front[:30].sum() / front.sum() > 0.7


def test_las_proporciones_de_la_mezcla_se_cumplen_y_se_registran(tmp_path):
    manifest = run_generator(tmp_path)
    import pandas as pd
    campaigns = pd.read_csv(tmp_path / "campaigns.csv")
    counts = Counter(campaigns["arrivalCurve"])
    total = sum(counts.values())
    assert total == 1500
    for kind, share in {"u": 0.4, "linear": 0.4, "front": 0.2}.items():
        assert counts[kind] / total == pytest.approx(share, abs=0.04), (kind, counts)
    assert manifest["curveMixCounts"] == dict(counts)


@pytest.mark.parametrize("bad", ["u=0.5,linear=0.4", "u=0.5,linear=0.6", "u=1,mala=0", "u=-0.1,linear=1.1", "u=abc", "", "u=1,u=0"])
def test_una_mezcla_invalida_se_rechaza(bad, tmp_path):
    result = subprocess.run(
        [sys.executable, str(HERE / "generate_synthetic_dataset.py"), "--out", str(tmp_path), "--n-campaigns", "5", "--curve-mix", bad],
        capture_output=True, text=True,
    )
    assert result.returncode != 0
    # el error es el de la mezcla (no el de un argumento desconocido) y no se escribió nada
    assert "mezcla de curvas" in result.stderr
    assert not (tmp_path / "snapshots.csv").exists()


def test_el_modo_legacy_no_anade_columnas(tmp_path):
    run_generator(tmp_path, "--curve-mix", "u=1", n=20)
    import pandas as pd
    assert "arrivalCurve" not in pd.read_csv(tmp_path / "campaigns.csv").columns
