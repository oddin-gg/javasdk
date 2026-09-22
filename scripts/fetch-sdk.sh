#!/usr/bin/env bash
#
# Put the SDK the system tests run against into the local Maven repository, checked against
# the digests in system-tests/src/test/resources/sdk-jar-checksums.properties.
#
# Why this exists: the module POM asks Maven Central before the packages registry, so that a
# public coordinate cannot be shadowed from a registry that only needs to serve one artifact.
# That order applies to the SDK coordinate too, and the 0.0.x group id is one we cannot claim
# on Central. Once the verified files are in the local repository Maven uses them and asks
# nobody, so the question never arises - and this runs before Maven, rather than inside a test
# that a substituted jar could have influenced by the time it runs.
#
# Safe to run at any time: it verifies what is already there and downloads only what is missing
# or wrong. Needs a GitHub token with read:packages.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
pom=$root/pom.xml
checksums=$root/system-tests/src/test/resources/sdk-jar-checksums.properties

value_of() { sed -n "s|.*<$1>\(.*\)</$1>.*|\1|p" "$pom"; }

group=$(value_of sdk.groupId)
version=$(value_of sdk.version)
[ -n "$group" ] && [ -n "$version" ] || { echo "cannot read sdk.groupId/sdk.version from $pom" >&2; exit 1; }

# the 1.0 line is built here; there is nothing to fetch and nothing published to compare against
if [ "$group" = "gg.oddin.oddsfeed" ]; then
  echo "sdk.groupId is $group: the SDK is built in this reactor, nothing to fetch"
  exit 0
fi

digest() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

path=$(echo "$group" | tr . /)/odds-feed/$version
dir=${MAVEN_USER_HOME:-$HOME/.m2}/repository/$path
base=https://maven.pkg.github.com/oddin-gg/javasdk/$path
mkdir -p "$dir"

for kind in jar pom; do
  file=$dir/odds-feed-$version.$kind
  expected=$(sed -n "s/^$version\.$kind=//p" "$checksums")
  if [ -z "$expected" ]; then
    echo "no digest recorded for $group:odds-feed:$version ($kind) in $checksums" >&2
    echo "add one once you know where the file came from" >&2
    exit 1
  fi

  if [ -f "$file" ] && [ "$(digest "$file")" = "$expected" ]; then
    echo "ok $kind  $file"
    continue
  fi

  if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "need GITHUB_TOKEN (a GitHub token with read:packages) to fetch $base/odds-feed-$version.$kind" >&2
    exit 1
  fi
  curl -fsSL -u "${GITHUB_ACTOR:-x}:$GITHUB_TOKEN" "$base/odds-feed-$version.$kind" -o "$file.part"

  actual=$(digest "$file.part")
  if [ "$actual" != "$expected" ]; then
    rm -f "$file.part"
    echo "$kind for $group:odds-feed:$version does not match the recorded digest" >&2
    echo "  expected $expected" >&2
    echo "  got      $actual" >&2
    exit 1
  fi
  mv "$file.part" "$file"
  echo "fetched $kind  $file"
done
