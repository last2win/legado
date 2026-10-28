#!/usr/bin/env python3
"""Fail-closed download/compare for the pinned QuickJS vendor files."""
import argparse
import os
from pathlib import Path
import re
import urllib.request


def file_names(path):
    names = Path(path).read_text().splitlines()
    if not names or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", n) or n in (".", "..") for n in names):
        raise ValueError("Invalid or empty upstream file list")
    if len(set(names)) != len(names):
        raise ValueError("Duplicate upstream filename")
    return names


def download(names, staging, base_url):
    staging.mkdir(parents=True, exist_ok=True)
    for name in names:
        target = staging / name
        target.unlink(missing_ok=True)
        # Any HTTP/network failure aborts the job. It must never become a skipped comparison.
        with urllib.request.urlopen(base_url + "/" + name, timeout=30) as response:
            data = response.read(16 * 1024 * 1024 + 1)
            if response.status != 200 or not data or len(data) > 16 * 1024 * 1024:
                raise ValueError("Invalid upstream response: " + name)
        target.write_bytes(data)


def compare(names, staging, vendor):
    changed = False
    for name in names:
        upstream = staging / name
        if not upstream.is_file() or upstream.stat().st_size == 0:
            raise ValueError("Missing upstream file: " + name)
        local = vendor / name
        same = local.is_file() and local.read_bytes().replace(b"\r\n", b"\n") == upstream.read_bytes().replace(b"\r\n", b"\n")
        print(name + (": identical" if same else ": different"))
        changed |= not same
    return changed


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("download", "compare"))
    parser.add_argument("--files", required=True)
    parser.add_argument("--staging", required=True, type=Path)
    parser.add_argument("--vendor", required=True, type=Path)
    parser.add_argument("--sha")
    args = parser.parse_args()
    names = file_names(args.files)
    if args.action == "download":
        if not re.fullmatch(r"[0-9a-fA-F]{40}", args.sha or ""):
            raise ValueError("Upstream must resolve to a full commit SHA")
        download(names, args.staging, "https://raw.githubusercontent.com/quickjs-ng/quickjs/" + args.sha)
    else:
        changed = compare(names, args.staging, args.vendor)
        # Write a verdict only after EVERY listed file was compared successfully.
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("changed=" + str(int(changed)) + "\n")
