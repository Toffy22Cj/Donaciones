"""ADR-044 Enmienda 4, E4-6: la comparación usa un conjunto de validación DISTINTO del de entrenamiento y reproducible."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
import compare_models  # noqa: E402


def test_la_validacion_es_reproducible_y_no_es_la_de_entrenamiento(tmp_path):
    a, sha_a = compare_models.validation_set(20261107, 60, tmp_path / "a")
    b, sha_b = compare_models.validation_set(20261107, 60, tmp_path / "b")
    train, sha_train = compare_models.validation_set(20261006, 60, tmp_path / "t")
    assert sha_a == sha_b
    assert sha_a != sha_train
    assert set(a["arrivalCurve"]) <= {"u", "linear", "front"}
    assert (a["alreadyReachedAtT"] == 0).all()
    assert "STRICT" not in set(a["targetPolicy"])
