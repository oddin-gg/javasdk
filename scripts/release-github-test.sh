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

# The stub answers as GitHub would, for release 42, and records every call. $GH_STUB_FAIL names a
# call to fail: tag, create, upload or publish.
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
  printf '42\nhttps://uploads.github.com/repos/example/repo/releases/42/assets{?name,label}\n'
fi
EOF
chmod +x "$work/bin/gh"

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# run <name> <version> <final> [files left out of the bundle]: in a fresh directory, as the job's
# checkout; $GH_STUB_FAIL passes through
run() {
  local name=$1 version=$2 final=$3 dir=$work/$1
  shift 3
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
    GITHUB_REF_NAME=v$version GH_REPO=example/repo bash "$root/scripts/release-github.sh") 2> "$dir/err"
}

# the calls a successful run makes
calls() {
  local version=$1 prerelease=$2 file
  echo "gh api repos/example/repo/git/ref/tags/v$version --silent"
  echo "gh api -X POST repos/example/repo/releases -f tag_name=v$version -f name=$version -F body=@release-notes/$version.md -F draft=true -F prerelease=$prerelease --jq .id, .upload_url"
  for file in odds-feed-$version.jar odds-feed-$version.jar.asc odds-feed-$version.pom odds-feed-$version.pom.asc; do
    echo "gh api -X POST https://uploads.github.com/repos/example/repo/releases/42/assets?name=$file -H Content-Type: application/octet-stream --input bundle/gg/oddin/oddsfeed/odds-feed/$version/$file --silent"
  done
  echo "gh api -X PATCH repos/example/repo/releases/42 -F draft=false --silent"
}

# succeeds <version> <final> <prerelease>
succeeds() {
  local version=$1 final=$2 prerelease=$3 name=ok-$1
  if ! run "$name" "$version" "$final"; then
    failed "$version: release-github.sh failed: $(cat "$work/$name/err")"
  elif [ "$(cat "$work/$name/calls")" != "$(calls "$version" "$prerelease")" ]; then
    failed "$version: expected
$(calls "$version" "$prerelease")
got
$(cat "$work/$name/calls")"
  elif grep -qE 'releases/41|gh release ' "$work/$name/calls"; then
    failed "$version: the leftover draft, or a release by tag, was touched: $(cat "$work/$name/calls")"
  else
    echo "ok   $version (final: $final): draft 42 created, filled and published by id"
  fi
}

# stops <name> <what the refusal says> [files left out]: the run fails, for that reason, and
# publishing is not even tried - unless publishing is what fails
stops() {
  local name=$1 reason=$2
  shift 2
  if run "$name" 1.0.1 true "$@"; then
    failed "$name: release-github.sh went on"
  elif [ "${GH_STUB_FAIL:-}" != publish ] && grep -q 'releases/42 -F draft=false' "$work/$name/calls"; then
    failed "$name: it published anyway: $(cat "$work/$name/calls")"
  elif ! grep -qF -- "$reason" "$work/$name/err"; then
    failed "$name: stopped, but not because \"$reason\": $(cat "$work/$name/err")"
  else
    echo "ok   $name: stopped, nothing published ($(tail -n 1 "$work/$name/err"))"
  fi
}

succeeds 1.0.0 true false
succeeds 1.0.0-rc.1 false true

for missing in .jar .jar.asc .pom .pom.asc; do
  stops "without-$missing" "the bundle has no" "$missing"
  [ ! -s "$work/without-$missing/calls" ] || failed "without $missing: gh was asked: $(cat "$work/without-$missing/calls")"
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
