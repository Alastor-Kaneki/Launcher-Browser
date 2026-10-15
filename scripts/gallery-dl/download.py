#!/usr/bin/env python3
"""Batch gallery downloader built on gallery-dl.

Wraps the gallery-dl CLI with defaults suited to saving galleries on a phone
(Termux) or desktop: one output folder, a download archive so re-runs skip
files that were already saved, optional JSON metadata, and a summary of what
the run added.

Examples:
    python3 scripts/gallery-dl/download.py https://www.flickr.com/photos/nasacommons/ -r 1-5
    python3 scripts/gallery-dl/download.py -i urls.txt -o ~/storage/downloads/galleries
    python3 scripts/gallery-dl/download.py URL -- --cookies-from-browser firefox

Anything after a bare "--" is passed to gallery-dl unchanged.
"""

import argparse
import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path

ARCHIVE_NAME = ".gallery-dl-archive.sqlite3"
REQUIREMENTS = Path(__file__).with_name("requirements.txt")


def gallery_dl_command():
    if importlib.util.find_spec("gallery_dl") is not None:
        return [sys.executable, "-m", "gallery_dl"]
    executable = shutil.which("gallery-dl")
    return [executable] if executable else None


def read_url_file(path):
    urls = []
    for line in Path(path).expanduser().read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            urls.append(line)
    return urls


def snapshot(root):
    if not root.is_dir():
        return {}
    return {
        path: path.stat().st_size
        for path in root.rglob("*")
        if path.is_file() and path.name != ARCHIVE_NAME and not path.name.endswith(".part")
    }


def human_size(size):
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024


def parse_args(argv):
    if "--" in argv:
        split = argv.index("--")
        argv, passthrough = argv[:split], argv[split + 1:]
    else:
        passthrough = []

    parser = argparse.ArgumentParser(
        description="Download image galleries and media with gallery-dl.",
        epilog='Arguments after a bare "--" are passed straight to gallery-dl.',
    )
    parser.add_argument("urls", nargs="*", metavar="URL", help="gallery, profile, or post URL")
    parser.add_argument("-i", "--input-file", action="append", default=[], metavar="FILE",
                        help="read URLs from FILE, one per line; '#' starts a comment")
    parser.add_argument("-o", "--output", default="downloads", metavar="DIR",
                        help="base directory for downloads (default: %(default)s)")
    parser.add_argument("-r", "--range", metavar="RANGE",
                        help="only fetch these items, e.g. 1-10 or 5-")
    parser.add_argument("-m", "--metadata", action="store_true",
                        help="write a .json metadata file next to each download")
    parser.add_argument("--no-archive", action="store_true",
                        help="do not record or skip previously downloaded files")
    parser.add_argument("-n", "--dry-run", action="store_true",
                        help="print the file URLs that would be downloaded, save nothing")
    args = parser.parse_args(argv)
    args.passthrough = passthrough
    return parser, args


def main(argv=None):
    parser, args = parse_args(sys.argv[1:] if argv is None else argv)

    urls = list(args.urls)
    for path in args.input_file:
        try:
            urls.extend(read_url_file(path))
        except OSError as error:
            parser.error(f"cannot read {path}: {error.strerror}")
    if not urls:
        parser.error("give at least one URL or --input-file")

    base = gallery_dl_command()
    if base is None:
        print("gallery-dl is not installed. Install it with:\n"
              f"    python3 -m pip install -r {REQUIREMENTS}", file=sys.stderr)
        return 2

    output = Path(args.output).expanduser().resolve()
    command = base + ["--destination", str(output)]
    if args.range:
        command += ["--range", args.range]
    if args.dry_run:
        command.append("--get-urls")
    else:
        if args.metadata:
            command.append("--write-metadata")
        if not args.no_archive:
            command += ["--download-archive", str(output / ARCHIVE_NAME)]
            output.mkdir(parents=True, exist_ok=True)
    command += args.passthrough + ["--"] + urls

    before = snapshot(output)
    try:
        status = subprocess.call(command)
    except KeyboardInterrupt:
        status = 130

    if not args.dry_run:
        after = snapshot(output)
        added = sorted(after.keys() - before.keys())
        total = sum(after[path] for path in added)
        print(f"\nSaved {len(added)} new file(s), {human_size(total)}, under {output}")
        for path in added:
            print(f"  {path.relative_to(output)}")
    if status:
        print(f"gallery-dl exited with status {status}", file=sys.stderr)
    return status


if __name__ == "__main__":
    sys.exit(main())
