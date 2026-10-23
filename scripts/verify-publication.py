"""Check the actual Maven-local publication before either registry serves it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
properties = dict(
    line.strip().split("=", 1)
    for line in (root / "gradle.properties").read_text().splitlines()
    if line.strip() and not line.lstrip().startswith("#")
)
is_jitpack = os.environ.get("JITPACK") == "true"
version = os.environ["VERSION"] if is_jitpack else properties["version"]
require(not os.environ.get("RELEASE_TAG") or os.environ["RELEASE_TAG"] == version,
        "Release tag differs from the configured version")
group = properties["group"]
artifact = "flight-jda6"
directory = args.repository / group.replace(".", "/") / artifact / version
stem = f"{artifact}-{version}"
pom_path = directory / f"{stem}.pom"
pom_text = pom_path.read_text()
pom = ET.fromstring(pom_text)
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
for field, expected in (("groupId", group), ("artifactId", artifact), ("version", version)):
    require(pom.findtext(f"m:{field}", namespaces=namespace) == expected,
            f"POM {field} differs from {expected}")

revision = subprocess.check_output(
    ["git", "rev-parse", "--short", "HEAD"], cwd=root, text=True
).strip()
for classifier in ("", "-sources"):
    with ZipFile(directory / f"{stem}{classifier}.jar") as jar:
        require(jar.read("flight.txt").decode().splitlines() == [version, revision],
                f"{classifier or 'Main'} JAR contains stale FlightInfo metadata")
        entry = "me/devoxin/flight/FlightInfo" + (".kt" if classifier else ".class")
        require(entry in jar.namelist(), f"Missing {entry} in {classifier or 'main'} JAR")

module_path = directory / f"{stem}.module"
if is_jitpack:
    require(not module_path.exists(), "JitPack must use POM metadata to preserve classifiers")
    require("published-with-gradle-metadata" not in pom_text,
            "JitPack POM must not redirect Gradle to missing module metadata")
else:
    metadata = json.loads(module_path.read_text())
    component = metadata["component"]
    for field, expected in (("group", group), ("module", artifact), ("version", version)):
        require(component[field] == expected, f"Module {field} differs from {expected}")
    variants = {variant["name"]: variant for variant in metadata["variants"]}
    require("shadowRuntimeElements" not in variants, "Shadow JAR leaked into library metadata")
    for variant_name, classifier in (("apiElements", ""), ("runtimeElements", ""),
                                     ("sourcesElements", "-sources")):
        files = variants[variant_name]["files"]
        require(len(files) == 1 and files[0]["url"] == f"{stem}{classifier}.jar",
                f"Incorrect artifact URL for {variant_name}")
    for variant in variants.values():
        for entry in variant.get("files", []):
            data = (directory / entry["url"]).read_bytes()
            require(len(data) == entry["size"], f"Incorrect size for {entry['url']}")
            require(hashlib.sha256(data).hexdigest() == entry["sha256"],
                    f"Incorrect checksum for {entry['url']}")

print(f"Verified {group}:{artifact}:{version} ({'JitPack/POM' if is_jitpack else 'Gradle metadata'})")
