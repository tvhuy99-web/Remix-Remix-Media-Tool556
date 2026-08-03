#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_NAME = f"{{{ANDROID_NS}}}name"
ERRORS: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)


def require_file(relative_path: str) -> Path:
    path = ROOT / relative_path
    check(path.is_file(), f"Thiếu tệp bắt buộc: {relative_path}")
    return path


def read_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = (
            value.strip()
            .replace(r"\:", ":")
            .replace(r"\=", "=")
        )
    return properties


def verify_required_files() -> None:
    required = [
        "settings.gradle.kts",
        "build.gradle.kts",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "gradle/libs.versions.toml",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradlew",
        "gradlew.bat",
        "README.md",
        "PROJECT_STATUS.md",
        "CHANGELOG.md",
        "THIRD_PARTY_NOTICES.md",
        "docs/ARCHITECTURE.md",
        "docs/ADDING_STEM_MODELS.md",
        "docs/DIAGNOSTICS.md",
        "docs/RELEASE_CHECKLIST.md",
        "scripts/check_local.sh",
        "scripts/test_wrapper_bootstrap.py",
        "app/src/main/assets/third_party_notices.txt",
    ]
    for relative_path in required:
        require_file(relative_path)


def verify_xml_and_manifest() -> None:
    manifest_path = require_file("app/src/main/AndroidManifest.xml")
    manifest: ET.Element | None = None
    xml_files = sorted((ROOT / "app/src/main/res").rglob("*.xml"))
    if manifest_path.is_file():
        xml_files.append(manifest_path)

    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
            if path == manifest_path:
                manifest = root
        except (OSError, ET.ParseError) as error:
            ERRORS.append(f"XML lỗi {path.relative_to(ROOT)}: {error}")

    if manifest is None:
        return

    permissions = {
        node.attrib.get(ANDROID_NAME)
        for node in manifest.findall("uses-permission")
        if node.attrib.get(ANDROID_NAME)
    }
    required_permissions = {
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    }
    for permission in sorted(required_permissions):
        check(permission in permissions, f"Manifest thiếu quyền {permission}")
    check(
        "android.permission.MANAGE_EXTERNAL_STORAGE" not in permissions,
        "Manifest không được xin MANAGE_EXTERNAL_STORAGE",
    )

    application = manifest.find("application")
    check(application is not None, "Manifest thiếu application")
    if application is None:
        return

    check(
        application.attrib.get(f"{{{ANDROID_NS}}}allowBackup") == "false",
        "Application phải tắt allowBackup",
    )

    service_types: set[str] = set()
    for service in application.findall("service"):
        raw_types = service.attrib.get(
            f"{{{ANDROID_NS}}}foregroundServiceType",
            "",
        )
        service_types.update(
            part.strip()
            for part in raw_types.split("|")
            if part.strip()
        )
    check("mediaProcessing" in service_types, "Manifest thiếu service mediaProcessing")

    provider_names = {
        provider.attrib.get(ANDROID_NAME)
        for provider in application.findall("provider")
        if provider.attrib.get(ANDROID_NAME)
    }
    check(
        "androidx.core.content.FileProvider" in provider_names,
        "Manifest thiếu androidx.core.content.FileProvider",
    )


def verify_version_catalog() -> None:
    path = require_file("gradle/libs.versions.toml")
    if not path.is_file():
        return
    try:
        catalog = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as error:
        ERRORS.append(f"Version catalog TOML lỗi: {error}")
        return

    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})
    plugins = catalog.get("plugins", {})

    for key in ("agp", "kotlin", "onnxruntime", "litert", "ffmpegKit"):
        check(bool(versions.get(key)), f"Version catalog thiếu versions.{key}")

    expected_modules = {
        "onnxruntime-android": "com.microsoft.onnxruntime:onnxruntime-android",
        "litert": "com.google.ai.edge.litert:litert",
        "ffmpeg-kit-full": "dev.ffmpegkit-maintained:ffmpeg-kit-full",
    }
    for alias, module in expected_modules.items():
        entry = libraries.get(alias, {})
        check(entry.get("module") == module, f"Alias {alias} không trỏ tới {module}")

    expected_plugins = {
        "android-application": "com.android.application",
        "kotlin-android": "org.jetbrains.kotlin.android",
        "kotlin-compose": "org.jetbrains.kotlin.plugin.compose",
    }
    for alias, plugin_id in expected_plugins.items():
        entry = plugins.get(alias, {})
        check(entry.get("id") == plugin_id, f"Plugin {alias} không trỏ tới {plugin_id}")


def verify_gradle_wrapper() -> None:
    jar_path = require_file("gradle/wrapper/gradle-wrapper.jar")
    properties_path = require_file("gradle/wrapper/gradle-wrapper.properties")

    if jar_path.is_file():
        try:
            with zipfile.ZipFile(jar_path) as wrapper_jar:
                check(
                    "org/gradle/wrapper/GradleWrapperMain.class" in wrapper_jar.namelist(),
                    "Wrapper JAR thiếu GradleWrapperMain.class",
                )
        except (OSError, zipfile.BadZipFile) as error:
            ERRORS.append(f"Wrapper JAR lỗi: {error}")

    if properties_path.is_file():
        properties = read_properties(properties_path)
        distribution_url = properties.get("distributionUrl", "")
        checksum = properties.get("distributionSha256Sum", "")
        check(
            distribution_url.startswith("https://"),
            "Gradle distributionUrl phải dùng HTTPS",
        )
        check(
            re.fullmatch(r"[0-9a-fA-F]{64}", checksum) is not None,
            "Checksum Gradle wrapper thiếu hoặc sai định dạng SHA-256",
        )


def verify_repository_hygiene() -> None:
    ignored_parts = {".git", ".gradle", "build"}
    files = [
        path
        for path in ROOT.rglob("*")
        if path.is_file() and not any(part in ignored_parts for part in path.parts)
    ]
    empty_files = [
        str(path.relative_to(ROOT))
        for path in files
        if path.stat().st_size == 0
    ]
    check(not empty_files, "Có tệp rỗng: " + ", ".join(empty_files))

    local_aars = [str(path.relative_to(ROOT)) for path in ROOT.rglob("*.aar")]
    check(not local_aars, "Không được chứa AAR cục bộ: " + ", ".join(local_aars))
    check(not (ROOT / ".env").exists(), "Không được phụ thuộc tệp .env")


def main() -> int:
    verify_required_files()
    verify_xml_and_manifest()
    verify_version_catalog()
    verify_gradle_wrapper()
    verify_repository_hygiene()

    if ERRORS:
        print("VERIFY FAILED")
        for error in ERRORS:
            print(f"- {error}")
        return 1

    print("VERIFY OK: cấu trúc, XML, TOML, manifest và Gradle wrapper hợp lệ")
    print("Chạy scripts/check_local.sh để biên dịch, lint và unit test bằng Gradle.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
