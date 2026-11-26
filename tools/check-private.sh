#!/bin/zsh
# Fails when a change would publish something private. Run it before every commit and push (tools/test-all.sh does).
#   tools/check-private.sh          lines added since origin/main (or HEAD), new untracked files, and commit messages
#   tools/check-private.sh --all    every tracked file
# What counts as private: the values in your git-ignored local config (ios/Config/Local.xcconfig, tools/local.env,
# android/local.properties), any literal strings you list in tools/private-patterns.txt (git-ignored, one per line:
# your phone numbers, names, SIM names…), home-directory paths, device ids and Bluetooth addresses, e-mail addresses
# other than GitHub noreply ones, and commit trailers or assistant names. Matches are reported by file and line, never
# by value. Vendored third-party code (ios/TetherCore/Sources/COpus) is not scanned.
set -u
cd "$(dirname "$0")/.."
exec python3 -I - "$@" <<'PY'
import os, re, subprocess, sys

def run(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout

mode_all = "--all" in sys.argv[1:]

# Literal private values from the local config (placeholders from the .example files are not secrets).
secrets = []
def add(label, v):
    v = (v or "").strip().strip('"').strip("'")
    if len(v) >= 6 and not re.fullmatch(r"(com\.example\.tether|com\.yourname\.tether|ABCDE12345|Cellular|Tether)", v):
        secrets.append((label, v))
def kv(path, keys, sep="="):
    if not os.path.exists(path): return
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line or line.startswith(("#", "//")) or sep not in line: continue
        k, v = line.split(sep, 1)
        if k.strip() in keys: add(f"{os.path.basename(path)}:{k.strip()}", v)
kv("ios/Config/Local.xcconfig", {"TETHER_TEAM_ID", "TETHER_BUNDLE_ID"})
kv("tools/local.env", {"TETHER_ANDROID_SERIAL", "TETHER_IOS_DEVICE", "ANDROID_SERIAL"})
kv("android/local.properties", {"tether.signing.storeFile", "tether.signing.storePassword", "tether.signing.keyAlias", "tether.signing.keyPassword", "sdk.dir"})
if os.path.exists("tools/private-patterns.txt"):
    for i, line in enumerate(open("tools/private-patterns.txt", encoding="utf-8", errors="ignore")):
        if line.strip() and not line.startswith("#"): secrets.append((f"private-patterns.txt line {i+1}", line.strip()))

patterns = [
    ("home-directory path", re.compile(r"/(Users|home)/(?!runner/|<|\$|USER|you|name)[A-Za-z0-9._-]+/")),
    ("iPhone UDID", re.compile(r"\b0000[0-9]{4}-[0-9A-F]{16}\b")),
    ("Bluetooth address", re.compile(r"\b(?!00:11:22|AA:BB:CC|11:22:33|XX:)([0-9A-F]{2}:){5}[0-9A-F]{2}\b")),
    # Not Kotlin labels (this@Outer) or image scales (icon@3x.png).
    ("e-mail address", re.compile(r"\b(?!this@|super@)[A-Za-z0-9._%+-]+@(?!users\.noreply\.github\.com)(?!example\.(com|org))(?![0-9]x\.)[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.(?!png\b|jpe?g\b|pdf\b|gif\b|svg\b|webp\b)[a-z]{2,6}\b(?!\()")),
    ("assistant or model name", re.compile(r"\b(claude|anthropic|chatgpt|openai|copilot|gemini)\b", re.I)),
]
skip_dirs = ("ios/TetherCore/Sources/COpus/",)
skip_files = {"tools/check-private.sh", ".github/copilot-instructions.md"}

findings = []
def scan(path, lineno, text):
    if path.startswith(skip_dirs) or path in skip_files: return
    for label, v in secrets:
        if v in text: findings.append((path, lineno, f"private value ({label})"))
    for label, rx in patterns:
        if rx.search(text): findings.append((path, lineno, label))

if mode_all:
    for path in run("git", "ls-files").splitlines():
        try:
            with open(path, encoding="utf-8") as f:
                for n, line in enumerate(f, 1): scan(path, n, line)
        except (UnicodeDecodeError, IsADirectoryError, FileNotFoundError):
            pass
else:
    base = run("git", "merge-base", "HEAD", "origin/main").strip() or "HEAD"
    path, n = None, 0
    for line in run("git", "diff", "--unified=0", "--no-color", base).splitlines():
        if line.startswith("+++ "): path = line[6:] if line.startswith("+++ b/") else None; continue
        m = re.match(r"@@ -\S+ \+(\d+)", line)
        if m: n = int(m.group(1)); continue
        if path and line.startswith("+"): scan(path, n, line[1:]); n += 1
    for p in run("git", "ls-files", "--others", "--exclude-standard").splitlines():
        try:
            with open(p, encoding="utf-8") as f:
                for k, l in enumerate(f, 1): scan(p, k, l)
        except (UnicodeDecodeError, IsADirectoryError, FileNotFoundError):
            pass
    for msg_line in run("git", "log", "--format=%H%x00%an <%ae>%x00%cn <%ce>%x00%B%x01", f"{base}..HEAD").split("\x01"):
        parts = msg_line.strip().split("\x00")
        if len(parts) < 4: continue
        sha, author, committer, body = parts[0][:10], parts[1], parts[2], parts[3]
        for who in (author, committer):
            if not who.endswith("@users.noreply.github.com>"): findings.append((f"commit {sha}", 0, "author/committer is not a noreply address"))
        if re.search(r"^(co-authored-by|signed-off-by):|generated with", body, re.I | re.M): findings.append((f"commit {sha}", 0, "commit trailer"))
        scan(f"commit {sha} message", 0, body)

if findings:
    seen = set()
    for f in findings:
        if f in seen: continue
        seen.add(f)
        print(f"{f[0]}:{f[1]}: {f[2]}" if f[1] else f"{f[0]}: {f[2]}")
    print(f"\n{len(seen)} finding(s). Nothing was published; fix them before committing or pushing.")
    sys.exit(1)
print("No private values found." + ("" if secrets else " (No local config values to check against.)"))
PY
