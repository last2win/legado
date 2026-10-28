#!/usr/bin/env python3
"""Validate CI release credentials before Gradle and preserve exact Java-properties values."""
import base64
import binascii
import os
from pathlib import Path
import subprocess
import sys
import tempfile


class SigningError(Exception):
    pass


def property_value(value):
    # Properties.load uses ISO-8859-1 plus UTF-16 \u escapes. Escape whitespace too: leading
    # spaces and backslashes in a valid password must not be trimmed or interpreted as escapes.
    units = value.encode("utf-16-be")
    return "".join("\\u%04x" % int.from_bytes(units[i:i+2], "big") for i in range(0, len(units), 2))


def prepare(root, environ=None):
    env = dict(os.environ if environ is None else environ)
    required = {"RS_FILE": "RELEASE_KEY_STORE", "RS_ALIAS": "RELEASE_KEY_ALIAS",
                "RS_KEY_PASS": "RELEASE_KEY_PASSWORD", "RS_STORE_PASS": "RELEASE_STORE_PASSWORD"}
    for key, label in required.items():
        if not env.get(key):
            raise SigningError(f"Missing {label}; release signing cannot proceed")
    try:
        encoded = "".join(env["RS_FILE"].split())
        if len(encoded) > 12 * 1024 * 1024:
            raise ValueError()
        binary = base64.b64decode(encoded, validate=True)
        if not binary:
            raise ValueError()
    except (ValueError, binascii.Error):
        raise SigningError("RELEASE_KEY_STORE must be complete, valid base64") from None
    root = Path(root)
    app = root / "app"
    app.mkdir(parents=True, exist_ok=True)
    validator = Path(__file__).with_name("ValidateAndroidSigning.java")
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
    with tempfile.TemporaryDirectory(prefix=".signing-", dir=app) as scratch:
        pending = Path(scratch) / "key.jks"
        pending.write_bytes(binary)
        pending.chmod(0o600)
        result = subprocess.run([java, str(validator), str(pending)], env=env, capture_output=True, text=True)
        if result.returncode:
            # Only forward the validator's fixed diagnostic, never arbitrary JVM stderr.
            safe = [line for line in result.stderr.splitlines() if line.startswith((
                "Keystore could not", "RELEASE_KEY_ALIAS does not", "Private key could not", "Signing key and certificate"))]
            raise SigningError(safe[-1] if safe else "Signing validation failed; use JDK 21 and verify the four release secrets")
        store_type = result.stdout.strip().upper()
        if store_type not in ("JKS", "PKCS12"):
            raise SigningError("Unsupported release keystore type")
        settings = {"RELEASE_STORE_FILE": "./key.jks", "RELEASE_STORE_TYPE": store_type,
                    "RELEASE_KEY_ALIAS": env["RS_ALIAS"], "RELEASE_KEY_PASSWORD": env["RS_KEY_PASS"],
                    "RELEASE_STORE_PASSWORD": env["RS_STORE_PASS"]}
        properties = root / "gradle.properties"
        previous = properties.read_text() if properties.exists() else ""
        generated = Path(scratch) / "gradle.properties"
        generated.write_text(previous + "\n" + "".join(f"{key}={property_value(value)}\n" for key, value in settings.items()))
        generated.chmod(0o600)
        # Do not replace an existing key or alter project settings until all validation succeeds.
        os.replace(pending, app / "key.jks")
        os.replace(generated, properties)
    print("Release signing preflight passed (keystore, alias, passwords and key/certificate pair)")


if __name__ == "__main__":
    try:
        prepare(Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd())
    except SigningError as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
