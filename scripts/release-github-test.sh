#!/usr/bin/env bash
#
# Runs scripts/release-github.sh with a stub gh and checks what it asks of GitHub, call for call:
# the tag looked up, the release created as a draft with the notes, each of the four files put on
# that draft by its id, and only then that release published by its id - an immutable release
# takes no assets once published, and a leftover draft for the same tag (the stub has one, 41)
# must never be the one touched. Every failure on the way must stop it before the publishing.
# next.yml runs it on every push. Needs python3 and unzip.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The stub answers as GitHub would and records every call. Creating a release answers
# $GH_STUB_CREATE - the id and the upload address, one per line - by default release 42's.
# $GH_STUB_FAIL names a call to fail: tag, create, upload or publish.
mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "gh $*" >> "$GH_STUB_CALLS"
case "$*" in
  "api repos/example/repo/git/ref/tags/"*) kind=tag ;;
  "api -X POST repos/example/repo/releases "*) kind=create ;;
  "api -X POST https://uploads.github.com/"*) kind=upload ;;
  "api -X PATCH repos/example/repo/releases/"*) kind=publish ;;
  *) kind=other ;;
esac
if [ "$kind" = "${GH_STUB_FAIL:-}" ]; then
  echo "gh: HTTP 502" >&2
  exit 1
fi
if [ "$kind" = create ]; then
  default='42
https://uploads.github.com/repos/example/repo/releases/42/assets{?name,label}'
  printf '%s\n' "${GH_STUB_CREATE:-$default}"
fi
EOF
chmod +x "$work/bin/gh"

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# run <name> <version> <final> [files left out of the bundle]: in a fresh directory, as the job's
# checkout; $GH_STUB_FAIL and $GH_STUB_CREATE pass through, $NOTES=missing or empty spoils the
# release notes, and $LEFTOVER=yes leaves a bundle/ directory behind
run() {
  local name=$1 version=$2 final=$3 dir=$work/$1
  shift 3
  mkdir -p "$dir/release-notes"
  case "${NOTES:-}" in
    missing) ;;
    empty) : > "$dir/release-notes/$version.md" ;;
    *) echo notes > "$dir/release-notes/$version.md" ;;
  esac
  if [ "${LEFTOVER:-}" = yes ]; then
    mkdir -p "$dir/bundle"
  fi
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
    GITHUB_REF_NAME=v$version GH_REPO=example/repo bash "$root/scripts/release-github.sh") 2> "$dir/err"
}

# the calls a successful run makes, for release <id>
calls() {
  local version=$1 prerelease=$2 id=$3 file
  echo "gh api repos/example/repo/git/ref/tags/v$version --silent"
  echo "gh api -X POST repos/example/repo/releases -f tag_name=v$version -f name=$version -F body=@release-notes/$version.md -F draft=true -F prerelease=$prerelease --jq .id, .upload_url"
  for file in odds-feed-$version.jar odds-feed-$version.jar.asc odds-feed-$version.pom odds-feed-$version.pom.asc; do
    echo "gh api -X POST https://uploads.github.com/repos/example/repo/releases/$id/assets?name=$file -H Content-Type: application/octet-stream --input bundle/gg/oddin/oddsfeed/odds-feed/$version/$file --silent"
  done
  echo "gh api -X PATCH repos/example/repo/releases/$id -F draft=false --silent"
}

# succeeds <version> <final> <prerelease> <id GitHub gives the release>
succeeds() {
  local version=$1 final=$2 prerelease=$3 id=$4 name=ok-$1-$4
  if ! GH_STUB_CREATE="$id
https://uploads.github.com/repos/example/repo/releases/$id/assets{?name,label}" run "$name" "$version" "$final"; then
    failed "$version: release-github.sh failed: $(cat "$work/$name/err")"
  elif [ "$(cat "$work/$name/calls")" != "$(calls "$version" "$prerelease" "$id")" ]; then
    failed "$version: expected
$(calls "$version" "$prerelease" "$id")
got
$(cat "$work/$name/calls")"
  elif grep -qE 'releases/41|gh release ' "$work/$name/calls"; then
    failed "$version: the leftover draft, or a release by tag, was touched: $(cat "$work/$name/calls")"
  else
    echo "ok   $version (final: $final): draft $id created, filled and published by that id"
  fi
}

# stops <name> <what the refusal says> [files left out]: the run fails, for that reason, and
# publishing is not even tried - unless publishing is what fails
stops() {
  local name=$1 reason=$2
  shift 2
  if run "$name" 1.0.1 true "$@"; then
    failed "$name: release-github.sh went on"
  elif [ "${GH_STUB_FAIL:-}" != publish ] && grep -q -- '-F draft=false' "$work/$name/calls"; then
    failed "$name: it published anyway: $(cat "$work/$name/calls")"
  elif ! grep -qF -- "$reason" "$work/$name/err"; then
    failed "$name: stopped, but not because \"$reason\": $(cat "$work/$name/err")"
  else
    echo "ok   $name: stopped, nothing published ($(tail -n 1 "$work/$name/err"))"
  fi
}

succeeds 1.0.0 true false 42
succeeds 1.0.0-rc.1 false true 42
succeeds 1.0.0 true false 7301

# stops before gh is asked anything
nothing_asked() {
  [ ! -s "$work/$1/calls" ] || failed "$1: gh was asked: $(cat "$work/$1/calls")"
}
NOTES=missing stops notes-missing "release-notes/1.0.1.md is missing or empty"
nothing_asked notes-missing
NOTES=empty stops notes-empty "release-notes/1.0.1.md is missing or empty"
nothing_asked notes-empty
LEFTOVER=yes stops leftover-bundle "bundle/ is left from an earlier run here"
nothing_asked leftover-bundle

# GitHub's answer to the creation is not what it should be: nothing is attached or published
nothing_attached() {
  ! grep -qE 'uploads|-F draft=false' "$work/$1/calls" || failed "$1: something was attached or published: $(cat "$work/$1/calls")"
}
GH_STUB_CREATE="not-an-id
https://uploads.github.com/repos/example/repo/releases/42/assets{?name,label}" stops bad-id "GitHub gave no release id"
nothing_attached bad-id
GH_STUB_CREATE="42
https://uploads.example.invalid/repos/example/repo/releases/42/assets{?name,label}" stops bad-upload-host \
  "GitHub gave release 42 an unexpected upload address"
nothing_attached bad-upload-host
GH_STUB_CREATE="42
https://uploads.github.com/repos/example/repo/releases/41/assets{?name,label}" stops other-release-upload \
  "GitHub gave release 42 an unexpected upload address"
nothing_attached other-release-upload

for missing in .jar .jar.asc .pom .pom.asc; do
  stops "without-$missing" "the bundle has no" "$missing"
  nothing_asked "without-$missing"
done
GH_STUB_FAIL=tag stops no-tag "GitHub has no tag v1.0.1"
GH_STUB_FAIL=create stops create-fails "could not create the draft release"
GH_STUB_FAIL=upload stops upload-fails "could not attach odds-feed-1.0.1.jar to draft release 42"
GH_STUB_FAIL=publish stops publish-fails "could not publish release 42"

if [ "$failures" -ne 0 ]; then
  echo "$failures GitHub Release case(s) failed" >&2
  exit 1
fi
echo "every GitHub Release case passed"
