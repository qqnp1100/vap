#!/usr/bin/env python3
"""Verify the staged VAP Maven coordinates, Android AARs and legacy JVM ABI."""

import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile


GROUP = "io.github.qqnp1100"
ARTIFACTS = {"vap-core": 16, "vap": 16, "vap-compose": 23}
GRADLE_DIR = Path(__file__).resolve().parent
ABI_BASELINE = GRADLE_DIR.parent / "docs/validation/animplayer-2.0.30-jvm-api.txt"
MISSING_GENERATED_CLASSES = {
    "com.tencent.qgame.animplayer.AudioPlayer$IdleAction",
    "com.tencent.qgame.animplayer.HardDecoder$release$1$10",
    "com.tencent.qgame.animplayer.mix.FrameSet$special$$inlined$sortBy$1",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_dependencies(label, dependencies, version, expected_core_scope, forbid_compose):
    project_dependencies = [(name, constraint, scope) for group, name, constraint, scope in dependencies
                            if group == GROUP]
    expected = [] if expected_core_scope is None else [("vap-core", version, expected_core_scope)]
    require(project_dependencies == expected, f"{label}: expected {expected}, found {project_dependencies}")
    if forbid_compose:
        unwanted = [f"{group}:{name}" for group, name, _, _ in dependencies
                    if group.startswith("androidx.compose")
                    or (group, name) == ("org.jetbrains.kotlinx", "kotlinx-coroutines-android")]
        require(not unwanted, f"{label}: unexpected UI dependencies: {unwanted}")


def check_maven_metadata(base, artifact, version):
    pom = ElementTree.parse(base.with_name(f"{base.name}.pom")).getroot()
    coordinates = tuple(pom.findtext(f"{{*}}{tag}") for tag in ("groupId", "artifactId", "version"))
    require(coordinates == (GROUP, artifact, version), f"{base.name}: wrong POM coordinates {coordinates}")
    require(pom.findtext("{*}packaging") == "aar", f"{base.name}: POM packaging is not aar")
    pom_dependencies = [
        (dep.findtext("{*}groupId"), dep.findtext("{*}artifactId"), dep.findtext("{*}version"),
         dep.findtext("{*}scope", "compile"))
        for dep in pom.findall("{*}dependencies/{*}dependency")
    ]
    pom_scope = {"vap": "runtime", "vap-compose": "compile"}.get(artifact)
    check_dependencies(f"{base.name} POM", pom_dependencies, version, pom_scope, artifact != "vap-compose")

    metadata = json.loads(base.with_name(f"{base.name}.module").read_text())
    component = metadata["component"]
    require(tuple(component[key] for key in ("group", "module", "version")) == (GROUP, artifact, version),
            f"{base.name}: wrong Gradle module coordinates {component}")
    published_usages = set()
    for variant in metadata["variants"]:
        attributes = variant["attributes"]
        if attributes.get("org.gradle.category") != "library":
            continue
        usage = attributes.get("org.gradle.usage")
        published_usages.add(usage)
        dependencies = [(dep["group"], dep["module"], dep.get("version", {}).get("requires"), usage)
                        for dep in variant.get("dependencies", [])]
        has_core = artifact == "vap-compose" or (artifact == "vap" and usage == "java-runtime")
        check_dependencies(f"{base.name} {variant['name']}", dependencies, version,
                           usage if has_core else None, artifact != "vap-compose")
    require({"java-api", "java-runtime"} <= published_usages,
            f"{base.name}: missing Gradle API or runtime library variant")

    for classifier in ("sources", "javadoc"):
        path = base.with_name(f"{base.name}-{classifier}.jar")
        with ZipFile(path) as jar:
            require(any(not name.endswith("/") and not name.startswith("META-INF/") for name in jar.namelist()),
                    f"{path.name}: empty documentation or sources JAR")


def check_aar(path, min_sdk, class_owners):
    with ZipFile(path) as aar:
        manifest = ElementTree.fromstring(aar.read("AndroidManifest.xml"))
        uses_sdk = manifest.find("uses-sdk")
        actual = uses_sdk.get("{http://schemas.android.com/apk/res/android}minSdkVersion") if uses_sdk is not None else None
        require(actual == str(min_sdk), f"{path.name}: expected minSdk {min_sdk}, found {actual}")
        jars = [name for name in aar.namelist() if name == "classes.jar"
                or (name.startswith("libs/") and name.endswith(".jar"))]
        require("classes.jar" in jars, f"{path.name}: classes.jar is missing")
        for name in jars:
            with ZipFile(io.BytesIO(aar.read(name))) as jar:
                for entry in jar.namelist():
                    if not entry.endswith(".class"):
                        continue
                    owner = f"{path.name}!{name}"
                    require(entry not in class_owners,
                            f"Duplicate {entry}: {class_owners.get(entry)} and {owner}")
                    class_owners[entry] = owner


def read_abi(text):
    classes = {}
    for header, body in re.findall(r"^([^ \n][^\n]* \{)\n(.*?)^\}", text, re.MULTILINE | re.DOTALL):
        name = re.search(r"\b(?:class|interface|enum) ([^\s<{]+)", header)
        require(name is not None, f"Unrecognized javap header: {header}")
        members = set(re.findall(r"^  (\S[^\n]*)\n    descriptor: (\S+)$", body, re.MULTILINE))
        require(len(members) == body.count("    descriptor: "), f"Incomplete javap member data for {header}")
        classes[name[1]] = (header, members)
    require(classes, "No javap declarations found")
    return classes


def check_abi(aars):
    before = read_abi(ABI_BASELINE.read_text())
    after = {}
    for aar in aars:
        output = subprocess.check_output([sys.executable, str(GRADLE_DIR / "dump-aar-api.py"), str(aar)], text=True)
        after.update(read_abi(output))
    missing = before.keys() - after.keys() - MISSING_GENERATED_CLASSES
    require(not missing, f"Missing legacy JVM classes: {', '.join(sorted(missing))}")
    for name in sorted(before.keys() & after.keys()):
        header, members = before[name]
        new_header, new_members = after[name]
        require(header == new_header, f"Legacy JVM declaration changed: {header} → {new_header}")
        removed = members - new_members
        require(not removed, f"Missing or changed legacy JVM members in {name}: {sorted(removed)}")
    return len(before.keys() & after.keys())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, help="root of the local Maven repository")
    parser.add_argument("version", help="release version to verify")
    args = parser.parse_args()

    aars = {}
    class_owners = {}
    try:
        for artifact, min_sdk in ARTIFACTS.items():
            base = args.repository.joinpath(*GROUP.split("."), artifact, args.version, f"{artifact}-{args.version}")
            check_maven_metadata(base, artifact, args.version)
            aar = base.with_name(f"{base.name}.aar")
            check_aar(aar, min_sdk, class_owners)
            aars[artifact] = aar
            print(f"PASS {GROUP}:{artifact}:{args.version} (minSdk {min_sdk})")
        retained = check_abi((aars["vap"], aars["vap-core"]))
    except (ValueError, OSError, KeyError, BadZipFile, ElementTree.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Verification failed: {error}\n")
    print(f"PASS {retained} legacy JVM classes retained; {len(class_owners)} unique class files across the AARs")


if __name__ == "__main__":
    main()
