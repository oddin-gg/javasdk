#!/usr/bin/env bash
#
# The checks a v0.* tag passes before the 0.x line publishes it to GitHub Packages, which keeps
# a version once it has it. The publish job runs them again in its own checkout and hands Gradle
# the version they checked, so the two cannot differ. scripts/release-check-test.sh holds them
# to their cases on every push.
#
#   scripts/release-check.sh <tag>
#
# Run in a clone with every tag. Needs GITHUB_TOKEN with read:packages (and GITHUB_ACTOR).
# Writes version= to $GITHUB_OUTPUT when that is set, to stdout otherwise. For the test,
# REGISTRY_URL replaces the registry, REGISTRY_TIMEOUT its time limit in seconds, and
# RELEASE_REMOTE the remote asked for the tag.
set -euo pipefail

tag=${1:?usage: release-check.sh <tag>}
registry=${REGISTRY_URL:-https://maven.pkg.github.com/oddin-gg/javasdk}
timeout=${REGISTRY_TIMEOUT:-60}
remote=${RELEASE_REMOTE:-origin}

fail() {
  echo "$tag: $*" >&2
  exit 1
}

# the forms this line has released: v0.0.57, v0.0.56-rc2. Nothing else, so the version is the
# tag without its leading v, whichever way it is derived.
if [[ ! "$tag" =~ ^v0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc[1-9][0-9]*)?$ ]]; then
  fail "neither v0.MINOR.PATCH nor v0.MINOR.PATCH-rcN"
fi
version=${tag#v}

commit=$(git rev-parse --verify -q "refs/tags/$tag^{commit}") || fail "no such tag here"

# The tag as GitHub has it now must name the commit this run builds: a run for a tag deleted or
# moved since it was pushed would otherwise publish the old commit under the version.
refs=$(git ls-remote --tags "$remote" "refs/tags/$tag" "refs/tags/$tag^{}") || fail "could not ask $remote for the tag"
now=$(printf '%s\n' "$refs" | awk -v ref="refs/tags/$tag^{}" '$2 == ref { print $1 }')
if [ -z "$now" ]; then
  now=$(printf '%s\n' "$refs" | awk -v ref="refs/tags/$tag" '$2 == ref { print $1 }')
fi
[ -n "$now" ] || fail "no longer on $remote"
[ "$now" = "$commit" ] || fail "now names $now on $remote, not $commit, which this run builds"

# Gradle's version, when not given, is the last tag, which could be any tag on the commit
tags=$(git tag --points-at "$commit")
[ "$tags" = "$tag" ] || fail "other tags name $commit: $(echo "$tags" | grep -vxF "$tag" | tr '\n' ' ')"

# 404 is the one answer that means "not there"; the registry redirects to the file it has
url=$registry/com/oddin/oddsfeed/odds-feed/$version/odds-feed-$version.pom
# a registry that stalls is no answer either: give up after the time limit
status=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout "$timeout" --max-time "$timeout" \
  -u "${GITHUB_ACTOR:-x}:${GITHUB_TOKEN:-}" "$url") || status="no answer"
case "$status" in
  404) ;;
  200|302) fail "GitHub Packages already has odds-feed $version" ;;
  *) fail "could not tell whether GitHub Packages has odds-feed $version ($status from $url)" ;;
esac

echo "$tag: odds-feed $version is not published yet" >&2
echo "version=$version" >> "${GITHUB_OUTPUT:-/dev/stdout}"
