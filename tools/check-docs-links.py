#!/usr/bin/env python3
"""Checks relative links in README.md and docs/**/*.md: every linked file exists and every #anchor matches a heading
(GitHub / Jekyll slugs). External links are not fetched. Run by tools/test-all.sh and CI."""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LINK = re.compile(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)|href=\"([^\"]+)\"")


def slug(text):
    text = re.sub(r"<[^>]+>|[`*_]", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.replace(" ", "-")


def anchors(path):
    out, fence = set(), False
    for line in open(path, encoding="utf-8"):
        if line.startswith("```"):
            fence = not fence
        m = None if fence else re.match(r"#{1,6}\s+(.*?)\s*#*$", line)
        if m:
            out.add(slug(m.group(1)))
        out.update(re.findall(r'id="([^"]+)"', line))
    return out


def files():
    yield os.path.join(ROOT, "README.md")
    for d, _, names in os.walk(os.path.join(ROOT, "docs")):
        if "history" in d.split(os.sep):
            continue
        for n in names:
            if n.endswith(".md"):
                yield os.path.join(d, n)


errors = []
for f in files():
    text = open(f, encoding="utf-8").read()
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    for m in LINK.finditer(text):
        target = m.group(1) or m.group(2)
        if re.match(r"^(https?:|mailto:|tel:|tether:)", target):
            continue
        path, _, frag = target.partition("#")
        dest = os.path.normpath(os.path.join(os.path.dirname(f), path)) if path else f
        rel = os.path.relpath(f, ROOT)
        # Generated at publish time (DocC / Dokka reference).
        if re.search(r"docs/(swift|kotlin)(/|$)", os.path.relpath(dest, ROOT)):
            continue
        if not os.path.exists(dest):
            errors.append(f"{rel}: {target}: no such file"); continue
        if frag and dest.endswith(".md") and frag not in anchors(dest):
            errors.append(f"{rel}: {target}: no heading #{frag}")

if errors:
    print("\n".join(errors), file=sys.stderr); sys.exit(1)
print("docs links ok")
