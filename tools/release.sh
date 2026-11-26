#!/bin/zsh
# Cuts a release: tools/release.sh <version> [--dry-run]   (version like 1.2.0 or 1.2.0-rc.1)
# Checks a clean tree on main, runs tools/test-all.sh --quick, writes VERSION and the iOS version (ios/project.yml),
# moves the CHANGELOG's "## Unreleased" entries under "## <version> — <date>", commits "Release <version>" and makes
# the annotated tag v<version>. It never pushes: it prints the push commands. --dry-run changes nothing and shows
# what would be written. See docs/RELEASING.md.
#   tools/release.sh --notes <version>   prints that version's CHANGELOG section (used by the release workflow)
#   tools/release.sh --code <version>    prints the Android versionCode / iOS build number of a version
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
AUTHOR_NAME="Amir Mohammad Rezaei"
AUTHOR_EMAIL="73078630+amirwmr@users.noreply.github.com"

version_code() {   # same rule as android/app/build.gradle.kts
  local v=$1 base pre n
  [[ $v =~ '^([0-9]+)\.([0-9]+)\.([0-9]+)(-[A-Za-z]+\.?([0-9]+)?)?$' ]] || { print -u2 "bad version '$v' (want 1.2.3 or 1.2.3-rc.1)"; return 2 }
  base=$(( match[1] * 1000000 + match[2] * 10000 + match[3] * 100 ))
  if [[ -n ${match[4]} ]]; then n=${match[5]:-0}; (( n > 98 )) && n=98; print $(( base + n )); else print $(( base + 99 )); fi
}
notes() {   # notes <version>: the lines between "## <version>" and the next "## "
  awk -v v="$1" '/^## / { if (on) exit; on = ($2 == v) ; next } on' CHANGELOG.md
}

dry=0; mode=release; ver=
for a in "$@"; do
  case $a in
    --dry-run) dry=1 ;;
    --notes) mode=notes ;;
    --code) mode=code ;;
    -h|--help) sed -n 2,8p "$0"; exit 0 ;;
    -*) print -u2 "unknown option $a"; exit 2 ;;
    *) ver=${a#v} ;;
  esac
done
[[ -n $ver ]] || { sed -n 2,8p "$0"; exit 2 }
code=$(version_code "$ver")
case $mode in
  code) print $code; exit 0 ;;
  notes) out=$(notes "$ver"); [[ -n ${out//[[:space:]]/} ]] || { print -u2 "no CHANGELOG section for $ver"; exit 1 }; print -r -- "$out"; exit 0 ;;
esac

die() { print -u2 "release: $*"; exit 1 }
marketing=${ver%%-*}
date=$(date +%Y-%m-%d)

# Checks (in a dry run they only warn).
check() { if (( dry )); then print -u2 "warning (dry run): $*"; else die "$*"; fi }
[[ $(git rev-parse --abbrev-ref HEAD) == main ]] || check "not on main"
[[ -z $(git status --porcelain) ]] || check "the working tree is not clean"
git rev-parse -q --verify "refs/tags/v$ver" >/dev/null && check "tag v$ver exists already"
old=$(tr -d '[:space:]' < VERSION)
(( code > $(version_code "$old") )) || [[ $old == "$ver" ]] || check "$ver is not newer than $old"
grep -q '^## Unreleased' CHANGELOG.md || die "CHANGELOG.md has no '## Unreleased' heading"
unreleased=$(awk '/^## / { if (on) exit; on = ($0 ~ /^## Unreleased/); next } on' CHANGELOG.md)
[[ -n ${unreleased//[[:space:]]/} ]] || check "nothing under '## Unreleased' in CHANGELOG.md"

# New CHANGELOG: an empty Unreleased, then the moved entries under the version.
changelog=$(awk -v h="## $ver — $date" '
  /^## Unreleased/ && !done { print; print ""; print h; done = 1; next }
  { print }' CHANGELOG.md)

if (( dry )); then
  print "dry run: version $ver (versionCode / build $code, iOS MARKETING_VERSION $marketing)"
  print "would run: tools/test-all.sh --quick"
  print "would write VERSION = $ver, ios/project.yml and project.pbxproj MARKETING_VERSION \"$marketing\", CURRENT_PROJECT_VERSION \"$code\""
  print "would write CHANGELOG.md headings:"; print -r -- "$changelog" | grep '^## ' | head -4 | sed 's/^/    /'
  print "would commit \"Release $ver\" as $AUTHOR_NAME and tag v$ver (annotated)"
  print "release notes would be:"; print -r -- "$unreleased" | head -5 | sed 's/^/    /'; print "    …"
  exit 0
fi

tools/test-all.sh --quick || die "tests failed; nothing changed"
print $ver > VERSION
M=$marketing C=$code perl -pi -e 's/^    MARKETING_VERSION: .*/    MARKETING_VERSION: "$ENV{M}"/;
  s/^    CURRENT_PROJECT_VERSION: .*/    CURRENT_PROJECT_VERSION: "$ENV{C}"/' ios/project.yml
# The generated project is committed too; keep it in step without needing xcodegen here.
M=$marketing C=$code perl -pi -e 's/(\bMARKETING_VERSION = )[^;]*;/$1$ENV{M};/; s/(\bCURRENT_PROJECT_VERSION = )[^;]*;/$1$ENV{C};/' \
  ios/Tether.xcodeproj/project.pbxproj
print -r -- "$changelog" > CHANGELOG.md
git add VERSION ios/project.yml ios/Tether.xcodeproj/project.pbxproj CHANGELOG.md
export GIT_COMMITTER_NAME=$AUTHOR_NAME GIT_COMMITTER_EMAIL=$AUTHOR_EMAIL
git -c user.name="$AUTHOR_NAME" -c user.email="$AUTHOR_EMAIL" commit -q -m "Release $ver"
git -c user.name="$AUTHOR_NAME" -c user.email="$AUTHOR_EMAIL" tag -a "v$ver" -m "Tether $ver"
print "Release $ver committed and tagged v$ver. To publish (the workflow builds and creates the GitHub Release):"
print "    git push origin main"
print "    git push origin v$ver"
