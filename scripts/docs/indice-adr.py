#!/usr/bin/env python3
"""D-06: índice de ADRs generado desde el catálogo del documento maestro (§5) y comprobación catálogo ↔ ficheros.

Comprueba:
  1. todo fichero Documentos/ADR-*.md tiene su número en el catálogo;
  2. todo fichero ADR-*.md citado en el catálogo existe (en Documentos/ o, si el catálogo dice que es de otro
     repositorio, se informa y no se exige);
  3. no hay huecos: todos los números desde ADR-001 hasta el anterior al "Siguiente número libre" aparecen.
Escribe Documentos/ADR-INDICE.md (con --escribir). Sale con 1 si alguna comprobación falla.
Uso: python3 scripts/docs/indice-adr.py [--escribir]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
DOCS = ROOT / "Documentos"
MASTER = DOCS / "documento-maestro-proyecto.md"
OUT = DOCS / "ADR-INDICE.md"


def catalog_text():
    text = MASTER.read_text(encoding="utf-8")
    start = text.index("## 5. Catálogo de Decisiones Arquitectónicas")
    end = text.index("\n## 6.", start)
    return text[start:end]


def main(write):
    cat = catalog_text()
    errors = []
    m = re.search(r"Siguiente número libre: ADR-(\d{3})", cat)
    if not m:
        sys.exit("ERROR: el catálogo no declara 'Siguiente número libre'")
    next_free = int(m.group(1))

    entries = {}  # número -> primera línea del catálogo que lo define
    for line in cat.splitlines():
        for num in re.findall(r"\*\*ADR-(\d{3})(?:-[A-D])?\b", line):
            entries.setdefault(int(num), line.strip())
    for num in re.findall(r"ADR-(\d{3})", cat):
        entries.setdefault(int(num), "")

    files = sorted(p.name for p in DOCS.glob("ADR-[0-9][0-9][0-9]*.md") if p.name != OUT.name)
    for f in files:
        if int(f[4:7]) not in entries:
            errors.append(f"{f}: su número no está en el catálogo")

    other_repo = re.compile(r"repositorio `Toffy22Cj/(PaxFide|PaxFide_Mv)`")
    for line in cat.splitlines():
        for cited in re.findall(r"`(ADR-\d{3}[^`]*\.md)`", line):
            if not (DOCS / cited).exists() and not other_repo.search(line):
                errors.append(f"{cited}: citado en el catálogo pero no existe en Documentos/")

    for n in range(1, next_free):
        if n not in entries:
            errors.append(f"ADR-{n:03d}: hueco en la numeración (ni en el catálogo ni reservado)")

    if write:
        rows = ["# Índice de ADRs (generado)", "",
                "Generado por `scripts/docs/indice-adr.py --escribir` desde el catálogo de `documento-maestro-proyecto.md` §5. "
                "No se edita a mano: la fuente es el catálogo.", "",
                "| ADR | Ficheros en Documentos/ | Entrada del catálogo |", "|---|---|---|"]
        for n in sorted(k for k in entries if k < next_free):
            own = [f for f in files if int(f[4:7]) == n]
            title = re.sub(r"\s+", " ", entries[n]).lstrip("- ")[:160].replace("|", "\\|")
            rows.append(f"| ADR-{n:03d} | {'<br>'.join(f'`{f}`' for f in own) or '(en el catálogo)'} | {title} |")
        rows += ["", f"Siguiente número libre: ADR-{next_free:03d}.", ""]
        OUT.write_text("\n".join(rows), encoding="utf-8")
        print(f"escrito {OUT.relative_to(ROOT)}")

    for e in errors:
        print(f"ERROR: {e}")
    print(f"ADRs en el catálogo: {len([k for k in entries if k < next_free])}; ficheros: {len(files)}; errores: {len(errors)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main("--escribir" in sys.argv[1:]))
