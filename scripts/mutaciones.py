#!/usr/bin/env python3
"""Ejecutor de mutaciones del proyecto (regla de evidencia: cada mutación debe hacer fallar algún test).

Uso:
    python3 scripts/mutaciones.py mutaciones.json

con un JSON de la forma:
    {"module": "core", "tests": "TestA,TestB",
     "mutations": [{"name": "...", "edits": [{"file": "core/src/...java", "old": "...", "new": "..."}]}]}

Garantías:
- Se niega a empezar si algún archivo a mutar tiene cambios sin commit: la restauración vuelve a HEAD.
- Restaura SIEMPRE con `git checkout -- <archivo>`, nunca desde una copia en memoria. Una copia guardada después de
  una primera edición del mismo archivo restauraría código mutado sin que nadie lo note.
- Al terminar comprueba que ningún archivo mutado quedó distinto de HEAD.
"""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def failing_tests(output):
    # "[ERROR] paquete.Clase.metodo(Tipo)[1] -- Time…": se corta solo el primer "]" (el del nivel de log); los tests
    # parametrizados llevan otro "]" en el nombre
    return sorted({line.split("]", 1)[-1].split(" -- ")[0].strip().split("(")[0].split(".")[-1]
                   + ("[" + line.split(" -- ")[0].rsplit("[", 1)[-1] if line.split(" -- ")[0].endswith("]") else "")
                   for line in output.splitlines()
                   if ("<<< FAILURE!" in line or "<<< ERROR!" in line) and "Tests run" not in line})


def main(spec_path):
    spec = json.loads(pathlib.Path(spec_path).read_text(encoding="utf-8"))
    files = sorted({e["file"] for m in spec["mutations"] for e in m["edits"]})
    dirty = [f for f in files if git("status", "--porcelain", "--", f).strip()]
    if dirty:
        sys.exit(f"Archivos con cambios sin commit (la restauración volvería a HEAD): {dirty}")

    survivors = 0
    for mutation in spec["mutations"]:
        touched = []
        try:
            for edit in mutation["edits"]:
                path = ROOT / edit["file"]
                text = path.read_text(encoding="utf-8")
                if text.count(edit["old"]) != 1:
                    raise ValueError(f"texto a mutar encontrado {text.count(edit['old'])} veces en {edit['file']}")
                path.write_text(text.replace(edit["old"], edit["new"]), encoding="utf-8")
                touched.append(edit["file"])
            result = subprocess.run(["mvn", "-o", "-q", "test", "-pl", spec["module"], f"-Dtest={spec['tests']}"],
                                    cwd=ROOT, capture_output=True, text=True)
            killed = result.returncode != 0
            survivors += 0 if killed else 1
            print(f"- {mutation['name']}: {'MUERTA' if killed else 'VIVA'}  {'; '.join(failing_tests(result.stdout))}",
                  flush=True)
        except ValueError as e:
            print(f"- {mutation['name']}: NO APLICADA ({e})", flush=True)
        finally:
            for f in sorted(set(touched)):
                git("checkout", "--", f)

    leftover = [f for f in files if git("status", "--porcelain", "--", f).strip()]
    if leftover:
        sys.exit(f"ERROR: archivos que no volvieron a HEAD: {leftover}")
    print(f"Restauración verificada contra HEAD. Mutaciones vivas: {survivors}")


if __name__ == "__main__":
    main(sys.argv[1])
