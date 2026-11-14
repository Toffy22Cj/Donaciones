#!/usr/bin/env python3
"""T-07 (ADR-055): toda supresión del escaneo de dependencias tiene caducidad y justificación registrada.

Comprueba Documentos/seguridad/dependency-check-supresiones.xml:
  - cada <suppress> lleva until="AAAA-MM-DDZ" válido;
  - sus <notes> citan un identificador EXC-NNN que existe como encabezado en excepciones-dependencias.md;
  - una supresión ya caducada se informa (deja de aplicarse sola) y hace fallar la comprobación, para que se borre o
    se renueve con un motivo nuevo.
Sale con 0 si todo cuadra y con 1 en otro caso.
Uso: python3 scripts/seguridad/comprobar-supresiones.py [supresiones.xml registro.md]
"""
import datetime
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
XML = ROOT / "Documentos/seguridad/dependency-check-supresiones.xml"
MD = ROOT / "Documentos/seguridad/excepciones-dependencias.md"
NS = "{https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd}"


def main(xml=XML, md=MD, today=None):
    today = today or datetime.date.today()
    registered = set(re.findall(r"^##+\s+(EXC-\d{3})\b", pathlib.Path(md).read_text(encoding="utf-8"), flags=re.M))
    errors = []
    suppressions = ET.parse(xml).getroot().findall(f"{NS}suppress")
    for i, s in enumerate(suppressions, 1):
        until = s.get("until")
        notes = (s.findtext(f"{NS}notes") or "").strip()
        ids = re.findall(r"EXC-\d{3}", notes)
        if not until or not re.fullmatch(r"\d{4}-\d{2}-\d{2}Z", until):
            errors.append(f"supresión {i}: falta until=\"AAAA-MM-DDZ\"")
        elif datetime.date.fromisoformat(until[:-1]) < today:
            errors.append(f"supresión {i}: caducó el {until[:-1]}; bórrala o renuévala con un motivo nuevo")
        if not ids:
            errors.append(f"supresión {i}: sus <notes> no citan ninguna EXC-NNN")
        for exc in ids:
            if exc not in registered:
                errors.append(f"supresión {i}: {exc} no está en {pathlib.Path(md).name}")
    for e in errors:
        print(f"ERROR: {e}")
    print(f"supresiones: {len(suppressions)}; errores: {len(errors)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]) if len(sys.argv) >= 3 else main())
