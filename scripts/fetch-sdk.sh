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
# or wrong. The files come from the version's public GitHub release, which needs no token: each
# 0.0.x release from 0.0.56 on carries the jar and POM, copied from the packages registry. Only a
# version whose release has no such file (0.0.54) falls back to the registry, which needs a
# GitHub token with read:packages in GITHUB_TOKEN; without one the script says so and stops. The
# digests are checked whichever served the file.
#
# The coordinates and the local repository come from Maven itself, so they are the ones the build
# will use: to test another SDK version, pass the same override to both, e.g.
#   MAVEN_ARGS=-Dsdk.version=0.0.57 ./scripts/fetch-sdk.sh && ./mvnw verify -Dsdk.version=0.0.57
# (Maven reads MAVEN_ARGS itself, so a settings file with its own localRepository works too.)
#
# scripts/fetch-sdk-test.sh sources this file and runs fetch_sdk against a local stub server.
set -euo pipefail

digest() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# get <url> <file> [token]: the HTTP status, with the body in file. https only, redirects included
# (a release file redirects to GitHub's storage). The token, when there is one, goes to curl as a
# config file on stdin, never on its command line, where any process could read it. Two calls, not
# an optional array: bash before 4.4 (macOS's /bin/bash) refuses an empty one under set -u.
get() {
  local options=(-sSL --proto '=https' --proto-redir '=https' --retry 3 -o "$2" -w '%{http_code}')
  if [ -n "${3:-}" ]; then
    curl "${options[@]}" --config - "$1" <<<"user = \"${GITHUB_ACTOR:-x}:$3\""
  else
    curl "${options[@]}" "$1"
  fi
}

# fetch_sdk <version> <directory> <release URL> <registry URL> <checksums file>: puts
# odds-feed-<version>.jar and .pom into the directory, each checked against its digest. Each file
# comes from the release, or from the registry when the release has none (404). Both are
# downloaded and checked before either is moved into place, so a failure installs nothing.
fetch_sdk() {
  local version=$1 dir=$2 release=$3 registry=$4 checksums=$5
  local kind name file expected source status actual fetched=() checked=
  mkdir -p "$dir"
  # a download an earlier run left behind is never trusted, and never moved into place
  discard "$dir" "$version"
  for kind in jar pom; do
    name=odds-feed-$version.$kind
    file=$dir/$name
    expected=$(sed -n "s/^$version\.$kind=//p" "$checksums")
    if [ -z "$expected" ]; then
      discard "$dir" "$version"
      echo "no digest recorded for odds-feed $version ($kind) in $checksums" >&2
      echo "add one once you know where the file came from" >&2
      return 1
    fi

    if [ -f "$file" ] && [ "$(digest "$file")" = "$expected" ]; then
      echo "ok $kind  $file"
      continue
    fi

    source=$release/$name
    status=$(get "$source" "$file.part") || status="no response"
    if [ "$status" = 404 ]; then
      # no such file on the release: the registry, which only a token can read
      source=$registry/$name
      if [ -z "${GITHUB_TOKEN:-}" ]; then
        discard "$dir" "$version"
        echo "the v$version GitHub release has no $name; fetching it from the packages registry" >&2
        echo "instead needs GITHUB_TOKEN (a GitHub token with read:packages): $source" >&2
        return 1
      fi
      status=$(get "$source" "$file.part" "$GITHUB_TOKEN") || status="no response"
    fi
    if [ "$status" != 200 ]; then
      discard "$dir" "$version"
      echo "could not fetch $source (status: $status)" >&2
      return 1
    fi

    actual=$(digest "$file.part")
    if [ "$actual" != "$expected" ]; then
      discard "$dir" "$version"
      echo "$kind for odds-feed $version from $source does not match the recorded digest" >&2
      echo "  expected $expected" >&2
      echo "  got      $actual" >&2
      return 1
    fi
    fetched+=("$kind  $file  (from $source)")
    checked="$checked $kind"
  done
  # both checked: only now does either file take the name Maven looks for
  for kind in $checked; do
    file=$dir/odds-feed-$version.$kind
    mv "$file.part" "$file"
  done
  # non-empty here whenever anything was fetched, and never expanded when empty
  if [ ${#fetched[@]} -gt 0 ]; then
    printf 'fetched %s\n' "${fetched[@]}"
  fi
}

# discard <directory> <version>: the downloads not yet checked, or refused
discard() {
  rm -f "$1/odds-feed-$2.jar.part" "$1/odds-feed-$2.pom.part"
}

main() {
  # log is not local: the EXIT trap reads it after main has returned
  local root pom checksums group version repository path
  root=$(cd "$(dirname "$0")/.." && pwd)
  pom=$root/pom.xml
  checksums=$root/system-tests/src/test/resources/sdk-jar-checksums.properties

  log=$(mktemp)
  trap 'rm -f "$log"' EXIT

  # Evaluates the root POM only (-N): it has no dependency on the SDK, so this resolves nothing
  # but the help plugin, from Central.
  evaluate() {
    "$root/mvnw" -q -N -f "$pom" org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
        -Dexpression="$1" -DforceStdout 2>"$log" \
      || { cat "$log" >&2; echo "could not ask Maven for $1" >&2; exit 1; }
  }

  group=$(evaluate sdk.groupId)
  version=$(evaluate sdk.version)
  repository=$(evaluate settings.localRepository)
  case "$group:$version" in
    *null*|:*|*:) echo "Maven did not report sdk.groupId/sdk.version (got '$group:$version')" >&2; exit 1 ;;
  esac

  # the 1.0 line is built here; there is nothing to fetch and nothing published to compare against
  if [ "$group" = "gg.oddin.oddsfeed" ]; then
    echo "sdk.groupId is $group: the SDK is built in this reactor, nothing to fetch"
    exit 0
  fi

  path=$(echo "$group" | tr . /)/odds-feed/$version
  fetch_sdk "$version" "$repository/$path" \
    "https://github.com/oddin-gg/javasdk/releases/download/v$version" \
    "https://maven.pkg.github.com/oddin-gg/javasdk/$path" \
    "$checksums"
}

# run, unless sourced by the test
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
