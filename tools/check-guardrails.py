#!/usr/bin/env python3
"""Guardrails that keep contributions inside the project's look and identity (CONTRIBUTING.md, "Design rules").

  python3 tools/check-guardrails.py                  theme scan of the working tree (CI on every push, test-all.sh)
  python3 tools/check-guardrails.py --base <ref>     also: what this change against <ref> may not touch (CI on pull
                                                     requests, with the pull request's base branch)

Theme scan, both apps:
  - no raw colours in code: Swift Color(red:/hue:/white:), UIColor(red:...), colour literals; Kotlin Color.parseColor,
    Color.rgb/argb, 0xAARRGGBB literals, Color.RED/BLUE/...; Android resources with #hex outside the palette files and
    the launcher artwork. The palette is Assets.xcassets/AccentColor (iPhone) and res/values*/colors.xml (Android).
  - no colours off the palette: purple, pink, cyan, mint, brown (iOS system colours used the way Apple's own apps use
    them stay allowed: red, green, orange, yellow, blue, indigo, gray, black, white, teal and the semantic styles).
  - no forcing light mode (the apps are dark first and follow the system).
  A line that needs an exception says why on the same line: `// theme: ok <reason>`; reviewers check the reason.

Protected (with --base), changed only by the maintainer's own commits, never in a contributor's pull request:
  the palette and themes, app icons and logo, app names and display names, application / bundle ids, the release
  signing setup, and the CI / release workflows.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MARK = "theme: ok"

SWIFT_DIRS = ["ios/Tether"]
KOTLIN_DIRS = ["android/app/src/main/java", "android/client/src/main/java"]
RES_DIRS = ["android/app/src/main/res", "android/client/src/main/res"]

SWIFT_RAW = re.compile(r"\bColor\(\s*(red|hue|white|\.sRGB|\.displayP3|\.sRGBLinear)\s*[:,]|\bUIColor\(\s*(red|hue|white|displayP3Red)\s*:|#colorLiteral|\bColor\(\s*hex")
SWIFT_OFF = re.compile(r"\b(Color|UIColor)\.(purple|pink|cyan|mint|brown|systemPurple|systemPink|systemCyan|systemMint|systemBrown)\b"
                       r"|\((?:\s*)\.(purple|pink|cyan|mint|brown)\s*\)")
SWIFT_LIGHT = re.compile(r"preferredColorScheme\(\s*\.light\s*\)|\\\.colorScheme\s*,\s*\.light")
KOTLIN_RAW = re.compile(r"Color\.parseColor|Color\.(rgb|argb)\(|\b0x[fF]{2}[0-9A-Fa-f]{6}\b|\bColor\.(RED|BLUE|GREEN|YELLOW|CYAN|MAGENTA|GRAY|DKGRAY|LTGRAY|WHITE|BLACK)\b")
RES_HEX = re.compile(r"#[0-9A-Fa-f]{3,8}\b")
# Palette and launcher artwork: the only resources that hold colour values.
RES_ALLOWED = re.compile(r"/res/values[^/]*/colors\.xml$|/res/drawable[^/]*/(ic_launcher_[a-z]+|ic_tether)\.xml$|/res/mipmap")

PROTECTED_PATHS = [
    re.compile(r"^android/(app|client)/src/main/res/values[^/]*/(colors|themes)\.xml$"),
    re.compile(r"^android/(app|client)/src/main/res/(drawable[^/]*/(ic_launcher_[a-z]+|ic_tether)\.xml|mipmap[^/]*/.*)$"),
    re.compile(r"^ios/Tether/Resources/Assets\.xcassets/(AccentColor|AppIcon|CallKitIcon)\.[a-z]+/"),
    re.compile(r"^docs/images/logo\.svg$"),
    re.compile(r"^ios/Config/Tether\.xcconfig$"),
    re.compile(r"^ios/project\.yml$"),
    re.compile(r"^android/(app|client)/build\.gradle\.kts$"),
    re.compile(r"^\.github/workflows/"),
    re.compile(r"^tools/release\.sh$"),
    # The checks themselves: a pull request may not switch off what is meant to stop it.
    re.compile(r"^tools/(check-guardrails\.py|check-private\.sh|test-all\.sh)$"),
]
# Lines whose change renames the product or an app, wherever they are.
IDENTITY_LINE = re.compile(r'name="app_name"|name="dialer_label"|CFBundleDisplayName|CFBundleName|TETHER_DISPLAY_NAME|'
                           r"applicationId|PRODUCT_BUNDLE_IDENTIFIER|TETHER_BUNDLE_ID|\bnamespace\s*=")


def files(dirs, suffix):
    for d in dirs:
        base = ROOT / d
        if base.exists():
            yield from (p for p in base.rglob(f"*{suffix}") if "/build/" not in p.as_posix())


def scan():
    problems = []

    def check(path, pattern, why):
        for n, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if pattern.search(line) and MARK not in line:
                problems.append(f"{path.relative_to(ROOT)}:{n}: {why}: {line.strip()[:120]}")

    for p in files(SWIFT_DIRS, ".swift"):
        check(p, SWIFT_RAW, "raw colour (use AccentColor or a system colour)")
        check(p, SWIFT_OFF, "colour off the palette")
        check(p, SWIFT_LIGHT, "forces light mode (the app follows the system, dark first)")
    for p in files(KOTLIN_DIRS, ".kt"):
        check(p, KOTLIN_RAW, "raw colour (use R.color.* from colors.xml)")
    for p in files(RES_DIRS, ".xml"):
        if not RES_ALLOWED.search(p.as_posix()):
            check(p, RES_HEX, "colour value outside colors.xml")
    return problems


def protected(base):
    try:
        names = subprocess.run(["git", "diff", "--name-only", f"{base}...HEAD"], cwd=ROOT, check=True,
                               capture_output=True, text=True).stdout.split()
        diff = subprocess.run(["git", "diff", "--unified=0", f"{base}...HEAD"], cwd=ROOT, check=True,
                              capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as e:
        return [f"cannot compare with {base}: {e.stderr.strip()}"]
    problems = [f"{n}: protected file (palette, icons, names, ids, signing or workflows): only the maintainer changes it"
                for n in names if any(r.search(n) for r in PROTECTED_PATHS)]
    current = "?"
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
        elif line[:1] in "+-" and not line.startswith(("+++", "---")) and IDENTITY_LINE.search(line):
            problems.append(f"{current}: renames the product or an app, or changes an id: {line[:120]}")
    return problems


def main(argv):
    base = None
    if "--base" in argv:
        i = argv.index("--base")
        if i + 1 >= len(argv):
            print("usage: check-guardrails.py [--base <ref>]", file=sys.stderr)
            return 2
        base = argv[i + 1]
    problems = scan() + (protected(base) if base else [])
    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} guardrail finding(s). See CONTRIBUTING.md, \"Design rules\" and \"Pull requests\".")
        return 1
    print("Guardrails ok" + (f" (theme, and nothing protected changed against {base})" if base else " (theme)"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
