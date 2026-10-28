import base64
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("signing", ROOT / "scripts/prepare-android-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
KEYTOOL = str(Path(os.environ["JAVA_HOME"]) / "bin/keytool") if os.environ.get("JAVA_HOME") else "keytool"


class SigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.envs = {}
        for kind in ("JKS", "PKCS12"):
            env = dict(os.environ, RS_ALIAS="签名 key", RS_STORE_PASS=" store\\t:!$=pass", RS_KEY_PASS=" key\\n!:#=pass")
            if kind == "PKCS12":
                env["RS_KEY_PASS"] = env["RS_STORE_PASS"]
            path = Path(cls.tmp.name) / (kind + ".keystore")
            result = subprocess.run([KEYTOOL, "-genkeypair", "-alias", env["RS_ALIAS"], "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=Test Fixture", "-storetype", kind, "-keystore", str(path),
                "-storepass:env", "RS_STORE_PASS", "-keypass:env", "RS_KEY_PASS"], env=env, capture_output=True)
            if result.returncode: raise RuntimeError("Could not generate disposable signing fixture")
            env["RS_FILE"] = base64.b64encode(path.read_bytes()).decode()
            cls.envs[kind] = env

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_valid_keystores_and_exact_properties(self):
        for kind, env in self.envs.items():
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                signing.prepare(root, env)
                result = subprocess.run([JAVA, str(ROOT / "scripts/security-tests/signing/CheckSigningProperties.java"),
                    str(Path(root) / "gradle.properties"), str(Path(root) / "app/key.jks")], env=env, capture_output=True)
                self.assertEqual(0, result.returncode, "Java Properties/key lookup did not retain original credentials")
                self.assertEqual(0o600, (Path(root) / "app/key.jks").stat().st_mode & 0o777)

    def test_old_echo_properties_corrupt_special_characters(self):
        env = self.envs["JKS"]
        with tempfile.TemporaryDirectory() as root:
            signing.prepare(root, env)
            path = Path(root) / "gradle.properties"
            path.write_text("RELEASE_STORE_TYPE=JKS\nRELEASE_KEY_ALIAS=" + signing.property_value(env["RS_ALIAS"]) +
                "\nRELEASE_STORE_PASSWORD=" + env["RS_STORE_PASS"] + "\nRELEASE_KEY_PASSWORD=" + env["RS_KEY_PASS"] + "\n")
            result = subprocess.run([JAVA, str(ROOT / "scripts/security-tests/signing/CheckSigningProperties.java"),
                str(path), str(Path(root) / "app/key.jks")], env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)

    def test_invalid_secrets_fail_before_writing(self):
        cases = [("RS_FILE", "bad base64!", "base64"), ("RS_FILE", base64.b64encode(b"not a keystore").decode(), "Keystore could not"),
                 ("RS_ALIAS", "missing-alias", "RELEASE_KEY_ALIAS"), ("RS_STORE_PASS", "wrongpass", "Keystore could not"),
                 ("RS_KEY_PASS", "wrongpass", "RELEASE_KEY_PASSWORD"), ("RS_ALIAS", "", "Missing RELEASE_KEY_ALIAS")]
        for key, value, error in cases:
            with self.subTest(key=key, error=error), tempfile.TemporaryDirectory() as root:
                env = dict(self.envs["JKS"], **{key: value})
                app = Path(root) / "app"; app.mkdir()
                (app / "key.jks").write_bytes(b"keep existing key")
                props = Path(root) / "gradle.properties"; props.write_text("keep=existing\n")
                with self.assertRaisesRegex(signing.SigningError, error): signing.prepare(root, env)
                self.assertEqual(b"keep existing key", (app / "key.jks").read_bytes())
                self.assertEqual("keep=existing\n", props.read_text())
                self.assertFalse(list(app.glob(".signing-*")))


if __name__ == "__main__": unittest.main()
