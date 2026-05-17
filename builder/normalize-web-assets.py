#!/usr/bin/env python3
import argparse
import json
import posixpath
import re
from pathlib import Path


HTML_ATTR_PATTERN = re.compile(
    r'(?P<attr>\b(?:src|href|poster)\s*=\s*)(?P<quote>["\'])(?P<value>[^"\']+)(?P=quote)',
    re.IGNORECASE,
)
CSS_URL_PATTERN = re.compile(r"url\(\s*(?P<quote>['\"]?)(?P<value>[^'\"\)]+)(?P=quote)\s*\)")

SKIP_PREFIXES = (
    "http://",
    "https://",
    "//",
    "data:",
    "blob:",
    "mailto:",
    "tel:",
    "javascript:",
    "#",
)


def should_skip_url(value: str) -> bool:
    lower = value.lower()
    return lower.startswith(SKIP_PREFIXES)


def is_root_local_url(value: str) -> bool:
    return value.startswith("/") and not value.startswith("//")


def rewrite_url(value: str, file_rel: str) -> str:
    path_part = value
    suffix = ""
    for sep in ("?", "#"):
        idx = path_part.find(sep)
        if idx != -1:
            suffix = path_part[idx:]
            path_part = path_part[:idx]
            break

    target = path_part.lstrip("/")
    source_dir = posixpath.dirname(file_rel) or "."
    rel = posixpath.relpath(target, source_dir)
    if not rel.startswith("."):
        rel = f"./{rel}"
    return f"{rel}{suffix}"


def split_url_path(value: str) -> str:
    clean = value.split("?", 1)[0].split("#", 1)[0]
    if clean.startswith("./"):
        clean = clean[2:]
    elif clean.startswith("/"):
        clean = clean[1:]
    return clean


def rewrite_html(content: str, rewritten_refs: list[tuple[str, str]], file_rel: str) -> str:
    def repl(match: re.Match[str]) -> str:
        value = match.group("value")
        if should_skip_url(value) or not is_root_local_url(value):
            return match.group(0)
        next_value = rewrite_url(value, file_rel)
        rewritten_refs.append((file_rel, next_value))
        return f"{match.group('attr')}{match.group('quote')}{next_value}{match.group('quote')}"

    return HTML_ATTR_PATTERN.sub(repl, content)


def rewrite_css(content: str, rewritten_refs: list[tuple[str, str]], file_rel: str) -> str:
    def repl(match: re.Match[str]) -> str:
        value = match.group("value").strip()
        if should_skip_url(value) or not is_root_local_url(value):
            return match.group(0)
        next_value = rewrite_url(value, file_rel)
        rewritten_refs.append((file_rel, next_value))
        quote = match.group("quote")
        return f"url({quote}{next_value}{quote})"

    return CSS_URL_PATTERN.sub(repl, content)


def collect_unresolved(content: str) -> list[str]:
    unresolved: list[str] = []
    for match in HTML_ATTR_PATTERN.finditer(content):
        value = match.group("value")
        if not should_skip_url(value) and is_root_local_url(value):
            unresolved.append(value)

    for match in CSS_URL_PATTERN.finditer(content):
        value = match.group("value").strip()
        if not should_skip_url(value) and is_root_local_url(value):
            unresolved.append(value)

    return unresolved


def normalize_web_assets(dist_dir: Path) -> dict:
    rewritten_refs: list[tuple[str, str]] = []
    touched_files: set[str] = set()
    unresolved_refs: list[dict] = []
    missing_refs: list[dict] = []

    text_files = sorted(
        [p for p in dist_dir.rglob("*") if p.is_file() and p.suffix.lower() in {".html", ".css"}]
    )

    for file_path in text_files:
        rel = file_path.relative_to(dist_dir).as_posix()
        original = file_path.read_text(encoding="utf-8")
        rewritten = original

        if file_path.suffix.lower() == ".html":
            rewritten = rewrite_html(rewritten, rewritten_refs, rel)
        elif file_path.suffix.lower() == ".css":
            rewritten = rewrite_css(rewritten, rewritten_refs, rel)

        if rewritten != original:
            file_path.write_text(rewritten, encoding="utf-8")
            touched_files.add(rel)

    # Validate unresolved root-local refs
    for file_path in text_files:
        rel = file_path.relative_to(dist_dir).as_posix()
        content = file_path.read_text(encoding="utf-8")
        for unresolved in collect_unresolved(content):
            unresolved_refs.append({"file": rel, "ref": unresolved})

    # Validate rewritten refs resolve to files
    for file_rel, rewritten_ref in rewritten_refs:
        source_file = dist_dir / file_rel
        target_rel = split_url_path(rewritten_ref)
        target_path = (source_file.parent / target_rel).resolve()
        if not target_path.exists():
            missing_refs.append({"file": file_rel, "ref": rewritten_ref})

    status = "ok" if not unresolved_refs and not missing_refs else "failed"
    return {
        "status": status,
        "rewrittenRefs": len(rewritten_refs),
        "filesTouched": len(touched_files),
        "unresolvedRefs": unresolved_refs,
        "missingRefs": missing_refs,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Normalize root-absolute web asset URLs for file:// runtime")
    parser.add_argument("--dist-dir", required=True)
    parser.add_argument("--report-path", required=True)
    args = parser.parse_args()

    dist_dir = Path(args.dist_dir)
    report_path = Path(args.report_path)

    if not dist_dir.exists() or not dist_dir.is_dir():
        raise SystemExit(f"dist directory not found: {dist_dir}")

    report = normalize_web_assets(dist_dir)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    if report["status"] != "ok":
        print("portl-builder: web asset normalization failed", flush=True)
        if report["unresolvedRefs"]:
            print(json.dumps({"unresolvedRefs": report["unresolvedRefs"]}, indent=2), flush=True)
        if report["missingRefs"]:
            print(json.dumps({"missingRefs": report["missingRefs"]}, indent=2), flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
