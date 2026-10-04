#!/usr/bin/env bash
#
# The checks a v1.* tag passes before release.yml builds or publishes anything. Central never
# takes a version back, and a release candidate goes out without anyone approving it, so these
# are the gate. scripts/release-check-test.sh runs them against a scratch repository on every
# push.
#
#   scripts/release-check.sh <tag>
#
# Run in a clone with every branch and tag (fetch-depth: 0). It reads the tagged commit from
# git, not from the working tree. Writes version=, final= and commit= to $GITHUB_OUTPUT when
# that is set, to stdout otherwise. Needs curl, python3 and yq (mikefarah's, on GitHub's
# runners). CENTRAL_URL replaces the Central repository and CENTRAL_TIMEOUT its time limit in
# seconds, for the test.
set -euo pipefail

tag=${1:?usage: release-check.sh <tag>}
central=${CENTRAL_URL:-https://repo1.maven.org/maven2}
timeout=${CENTRAL_TIMEOUT:-60}

fail() {
  echo "$tag: $*" >&2
  exit 1
}

# v1.2.3 is a final, v1.2.3-rc.4 a release candidate; nothing else is a release tag
if [[ ! "$tag" =~ ^v(1\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*))(-rc\.([1-9][0-9]*))?$ ]]; then
  fail "neither v1.MINOR.PATCH nor v1.MINOR.PATCH-rc.N"
fi
version=${tag#v}
if [ -n "${BASH_REMATCH[4]}" ]; then
  final=false
else
  final=true
fi

commit=$(git rev-parse --verify -q "refs/tags/$tag^{commit}") || fail "no such tag here"

# only what was reviewed and merged
on_branch=
for branch in next main; do
  ref=refs/remotes/origin/$branch
  if git rev-parse -q --verify "$ref" >/dev/null; then
    if git merge-base --is-ancestor "$commit" "$ref"; then
      on_branch=$branch
      break
    else
      # 1 means "not an ancestor"; anything else is git failing, which must not read as either
      status=$?
      [ "$status" -eq 1 ] || fail "git merge-base failed ($status) for $branch"
    fi
  fi
done
[ -n "$on_branch" ] || fail "$commit is on neither next nor main"

# one commit, one release (NEXT.md, section 9); any other tag is a question nobody answered
tags=$(git tag --points-at "$commit")
[ "$tags" = "$tag" ] || fail "other tags name $commit: $(echo "$tags" | grep -vxF "$tag" | tr '\n' ' ')"

# the GitHub Release is made from them after Central has the version; missing then, they would
# leave a published version without its release. A plain file: gh follows a link, and one to
# /proc/self/environ would put its token into the public release.
notes=release-notes/$version.md
entry=$(git ls-tree "$commit" -- "$notes")
[ -n "$entry" ] || fail "$notes is missing"
case "$entry" in
  "100644 blob "* | "100755 blob "*) ;;
  *) fail "$notes is not a plain file: ${entry%%$'\t'*}" ;;
esac
[ "$(git cat-file -s "$commit:$notes")" -gt 0 ] || fail "$notes is empty"

# release.yml must be the only workflow a tag starts: anything else would publish too, unchecked.
# Parsed, not grepped, so inline YAML counts too: a push trigger with tags, or with no branch
# filter at all (which runs on tags as well), and create and release events.
command -v yq >/dev/null || fail "needs yq to read the workflows"
others=$(git ls-tree --name-only "$commit" .github/workflows/ | grep -E '\.ya?ml$' | grep -vxF .github/workflows/release.yml || true)
for workflow in $others; do
  triggers=$(git show "$commit:$workflow" | yq -o=json '.on') || fail "could not read $workflow"
  if ! printf '%s' "$triggers" | python3 -c '
import json, sys
on = json.load(sys.stdin)
if isinstance(on, str):
    on = [on]
if isinstance(on, list):
    on = {event: None for event in on}
if not isinstance(on, dict):
    sys.exit(0)
if "create" in on or "release" in on:
    sys.exit(1)
if "push" in on:
    push = on["push"] or {}
    if "tags" in push or "tags-ignore" in push:
        sys.exit(1)
    if "branches" not in push and "branches-ignore" not in push:
        sys.exit(1)
'; then
    fail "$workflow reacts to tags as well; only release.yml may"
  fi
done

# 404 is the one answer that means "not there"; anything else stops the release
for artifact in odds-feed-parent odds-feed; do
  url=$central/gg/oddin/oddsfeed/$artifact/$version/$artifact-$version.pom
  status=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout "$timeout" --max-time "$timeout" "$url") \
    || status="no answer"
  case "$status" in
    404) ;;
    200) fail "Central already has $artifact $version" ;;
    *) fail "could not tell whether Central has $artifact $version ($status from $url)" ;;
  esac
done

echo "$tag: $version on $on_branch, final: $final" >&2
{
  echo "version=$version"
  echo "final=$final"
  echo "commit=$commit"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
