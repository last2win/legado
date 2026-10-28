import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

spec = importlib.util.spec_from_file_location("upstream", Path(__file__).parents[1] / "quickjs-upstream.py")
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)


class UpstreamTests(unittest.TestCase):
    def test_missing_file_never_reports_identical(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            with self.assertRaises(ValueError):
                upstream.compare(["quickjs.c"], path, path)

    def test_download_failure_cannot_reuse_stale_file(self):
        for failure in [HTTPError("url", 404, "missing", {}, None), URLError("offline")]:
            with tempfile.TemporaryDirectory() as root:
                path = Path(root)
                (path / "quickjs.c").write_text("stale")
                with patch.object(upstream.urllib.request, "urlopen", side_effect=failure):
                    with self.assertRaises((HTTPError, URLError)):
                        upstream.download(["quickjs.c"], path, "https://example.invalid")
                self.assertFalse((path / "quickjs.c").exists())

    def test_all_files_are_compared_and_crlf_is_normalized(self):
        with tempfile.TemporaryDirectory() as root:
            local, remote = Path(root) / "local", Path(root) / "remote"
            local.mkdir(); remote.mkdir()
            (local / "q.c").write_bytes(b"line\r\n")
            (remote / "q.c").write_bytes(b"line\n")
            self.assertFalse(upstream.compare(["q.c"], remote, local))
            (remote / "q.c").write_text("modified\n")
            self.assertTrue(upstream.compare(["q.c"], remote, local))
            with self.assertRaises(ValueError):
                upstream.compare(["q.c", "missing.h"], remote, local)


if __name__ == "__main__":
    unittest.main()
