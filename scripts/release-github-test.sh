#!/usr/bin/env bash
#
# Runs scripts/release-github.sh with a stub gh and checks what it asks of GitHub: the release
# created as a draft with the notes and all four files, a release candidate marked as one, and
# only then published - an immutable release takes no assets once published. A bundle missing a
# file must stop it before gh is asked anything. next.yml runs it on every push.
# Needs python3 and unzip.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "gh $*" >> "$GH_STUB_CALLS"
EOF
chmod +x "$work/bin/gh"

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# run <version> <final> [files left out of the bundle]: in a fresh directory, as the job's checkout
run() {
  local version=$1 final=$2 dir=$work/run-$1-$2
  shift 2
  mkdir -p "$dir/release-notes"
  echo notes > "$dir/release-notes/$version.md"
  python3 - "$dir/central-bundle.zip" "$version" "$@" <<'EOF'
import sys, zipfile
bundle, version, missing = sys.argv[1], sys.argv[2], set(sys.argv[3:])
base = "gg/oddin/oddsfeed/odds-feed/%s/odds-feed-%s" % (version, version)
with zipfile.ZipFile(bundle, "w") as z:
    for suffix in (".jar", ".jar.asc", ".pom", ".pom.asc", "-sources.jar", "-javadoc.jar"):
        if suffix not in missing:
            z.writestr(base + suffix, "content")
EOF
  : > "$dir/calls"
  (cd "$dir" && PATH=$work/bin:$PATH GH_STUB_CALLS=$dir/calls VERSION=$version FINAL=$final \
    GITHUB_REF_NAME=v$version bash "$root/scripts/release-github.sh") 2> "$dir/err"
}

files() {
  local d=bundle/gg/oddin/oddsfeed/odds-feed/$1/odds-feed-$1
  echo "$d.jar $d.jar.asc $d.pom $d.pom.asc"
}

# expect <version> <final> <the gh calls, one per line>
expect() {
  local version=$1 final=$2 want=$3
  if ! run "$version" "$final"; then
    failed "$version: release-github.sh failed: $(cat "$work/run-$version-$final/err")"
  elif [ "$(cat "$work/run-$version-$final/calls")" != "$want" ]; then
    failed "$version: expected
$want
got
$(cat "$work/run-$version-$final/calls")"
  else
    echo "ok   $version (final: $final): a draft with its files, then published"
  fi
}

expect 1.0.0 true "gh release create v1.0.0 --verify-tag --draft --title 1.0.0 --notes-file release-notes/1.0.0.md $(files 1.0.0)
gh release edit v1.0.0 --draft=false"
expect 1.0.0-rc.1 false "gh release create v1.0.0-rc.1 --verify-tag --draft --title 1.0.0-rc.1 --notes-file release-notes/1.0.0-rc.1.md --prerelease $(files 1.0.0-rc.1)
gh release edit v1.0.0-rc.1 --draft=false"

for missing in .jar .jar.asc .pom .pom.asc; do
  if run 1.0.1 true "$missing"; then
    failed "1.0.1 without $missing: release-github.sh went on"
  elif [ -s "$work/run-1.0.1-true/calls" ]; then
    failed "1.0.1 without $missing: gh was asked: $(cat "$work/run-1.0.1-true/calls")"
  elif ! grep -qF "the bundle has no" "$work/run-1.0.1-true/err"; then
    failed "1.0.1 without $missing: stopped, but not because of the file: $(cat "$work/run-1.0.1-true/err")"
  else
    echo "ok   a bundle without $missing stops before gh"
  fi
  rm -rf "$work/run-1.0.1-true"
done

if [ "$failures" -ne 0 ]; then
  echo "$failures GitHub Release case(s) failed" >&2
  exit 1
fi
echo "every GitHub Release case passed"
