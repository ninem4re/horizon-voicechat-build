#!/usr/bin/env python3
"""Apply the official voice-chat bundle to the November 13 engine and package it."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

HORIZON_REV = "9f42064c11efe7ef3e5ed2baad7ddc2a3b36eea0"
MODS_REV = "87cbc721e68a689559c508cb5357d77cdae2ab81"
ARCHIVE = "horizon-engine-voicechat-linux-x86_64.tar.gz"


def run(command, **kwargs):
    print("+", " ".join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    mods = args.mods_root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=mods, text=True).strip()
    if actual != MODS_REV:
        raise SystemExit(f"Expected Horizon Mods {MODS_REV}, got {actual}")

    spec = importlib.util.spec_from_file_location("horizon_native", mods / "native/tools/native.py")
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    mod = native.Mod("voicechat-server")
    print("::group::Apply voicechat-server to pinned Horizon", flush=True)
    stage = native.stage([mod], HORIZON_REV)
    print("::endgroup::", flush=True)

    target = mods / "native/target/cargo"
    env = dict(os.environ, CARGO_TARGET_DIR=str(target))
    print("::group::Compile optimized server engine", flush=True)
    run(["cargo", "build", "--release", "--locked", "-p", "horizon-client",
         "--bin", "horizon-client", "--no-default-features"], cwd=stage, env=env)
    print("::endgroup::", flush=True)

    package = output / "horizon-voicechat-linux-x86_64"
    package.mkdir(exist_ok=True)
    engine = package / "horizon-engine"
    shutil.copy2(target / "release/horizon-client", engine)
    engine.chmod(0o755)
    # Only inspect the executable; no game/world or prepared-resource folders are opened.
    with tempfile.TemporaryDirectory(prefix="horizon-voicechat-smoke-") as temporary:
        isolated = dict(env, HORIZON_HOME=temporary, XDG_DATA_HOME=temporary,
                        XDG_CONFIG_HOME=temporary, HORIZON_NO_AUDIO="1")
        help_text = run([engine, "--help"], cwd=temporary, env=isolated,
                        capture_output=True, text=True, timeout=60).stdout
        if "--resources" not in help_text:
            raise SystemExit("Engine --help did not expose --resources")
    libraries = run(["ldd", engine], capture_output=True, text=True).stdout
    if any(word in libraries for word in ("not found", "libasound", "libudev")):
        raise SystemExit(f"Unexpected or missing runtime library:\n{libraries}")
    (package / "runtime-libraries.txt").write_text(libraries)

    with engine.open("rb") as built_engine:
        engine_digest = hashlib.file_digest(built_engine, "sha256").hexdigest()
    info = {
        "horizon_repository": "https://github.com/horizon-rust/horizon",
        "horizon_revision": HORIZON_REV,
        "nightly_date": "2026-11-13",
        "mods_repository": "https://github.com/horizon-rust/horizon-mods",
        "mods_revision": MODS_REV,
        "voicechat_mod": mod.id,
        "voicechat_version": mod.version,
        "horizon_mixins_revision": mod.compat["horizon_mixins_revision"],
        "target": "x86_64-unknown-linux-gnu",
        "rustc": subprocess.check_output(["rustc", "--version"], text=True).strip(),
        "build_repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "build_commit": os.environ.get("GITHUB_SHA", ""),
        "build_run": os.environ.get("GITHUB_RUN_ID", ""),
        "profile": "release; lto=false; codegen-units=16; target-cpu=x86-64",
        "engine_sha256": engine_digest,
        "verification": ["relay tests", "cargo build --locked", "--help", "ldd"],
    }
    (package / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
    shutil.copy2(Path(__file__).resolve().parents[1] / "README.md", package / "INSTALL.md")
    shutil.copy2(stage / "LICENSE", package / "LICENSE-HORIZON")
    shutil.copy2(mods / "native/voicechat-server/LICENSE", package / "LICENSE-VOICECHAT")
    if (stage / "THIRD_PARTY_NOTICES.md").is_file():
        shutil.copy2(stage / "THIRD_PARTY_NOTICES.md", package)

    # Corresponding modified source travels with the LGPL engine. Never include Cargo
    # output, Minecraft resources, game jars, or a local game directory.
    def source_filter(member):
        parts = Path(member.name).parts
        if any(p in {"target", ".git", ".local", "__pycache__"} for p in parts):
            return None
        return member

    with tarfile.open(package / "horizon-voicechat-source.tar.gz", "w:gz") as source:
        source.add(stage, arcname="horizon-voicechat-source", filter=source_filter)
    with tarfile.open(output / ARCHIVE, "w:gz") as archive:
        for path in sorted(package.iterdir()):
            archive.add(path, arcname=path.name)
    with (output / ARCHIVE).open("rb") as built:
        digest = hashlib.file_digest(built, "sha256").hexdigest()
    (output / (ARCHIVE + ".sha256")).write_text(f"{digest}  {ARCHIVE}\n")
    shutil.copy2(package / "build-info.json", output / "build-info.json")
    shutil.rmtree(package)
    print(json.dumps(info, indent=2), flush=True)


if __name__ == "__main__":
    main()
