#!/usr/bin/env python3
"""Print public and protected JVM declarations from an AAR's classes.jar."""

import argparse
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path, help="path to the built or published AAR")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as directory:
        classes_jar = Path(directory) / "classes.jar"
        with ZipFile(args.aar) as aar:
            classes_jar.write_bytes(aar.read("classes.jar"))
        with ZipFile(classes_jar) as classes:
            class_names = sorted(
                entry.removesuffix(".class").replace("/", ".")
                for entry in classes.namelist()
                if entry.endswith(".class")
            )
        if not class_names:
            parser.error("classes.jar contains no classes")

        declarations = subprocess.check_output(
            [
                "javap",
                "-J-Duser.language=en",
                "-J-Dfile.encoding=UTF-8",
                "-classpath",
                str(classes_jar),
                "-protected",
                "-s",
                "-constants",
                *class_names,
            ],
            encoding="utf-8",
        )

    # Source file names can change without affecting JVM linkage.
    classes = declarations.splitlines()
    print("\n".join(line for line in classes if not line.startswith('Compiled from "')))


if __name__ == "__main__":
    main()
