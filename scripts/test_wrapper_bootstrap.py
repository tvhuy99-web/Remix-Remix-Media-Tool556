#!/usr/bin/env python3
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="mediatool-wrapper-") as raw:
        temp = Path(raw)
        project = temp / "project"
        gradle_home = temp / "gradle-home"
        (project / "gradle/wrapper").mkdir(parents=True)

        for relative in (
            "gradlew",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            source = ROOT / relative
            destination = project / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)

        (project / "gradlew").chmod(0o755)
        (project / "settings.gradle.kts").write_text(
            'rootProject.name = "wrapper-smoke"\n',
            encoding="utf-8",
        )

        env = os.environ.copy()
        env["GRADLE_USER_HOME"] = str(gradle_home)
        result = subprocess.run(
            [str(project / "gradlew"), "--version"],
            cwd=project,
            env=env,
            text=True,
            capture_output=True,
            timeout=300,
        )
        output = result.stdout + "\n" + result.stderr
        if result.returncode != 0:
            raise SystemExit(
                "WRAPPER TEST FAILED\n"
                f"STDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}"
            )
        if "Gradle 8.13" not in output:
            raise SystemExit(
                "WRAPPER TEST FAILED: expected Gradle 8.13\n"
                f"OUTPUT:\n{output}"
            )
        print("WRAPPER BOOTSTRAP OK")


if __name__ == "__main__":
    main()
