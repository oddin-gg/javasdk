#!/usr/bin/env bash
#
# Checks that library/build.gradle publishes the version the publish job hands it as
# -PreleaseVersion, and falls back to the last tag without it. The release check decides which
# version may be published; this is what makes it the one that is. CI runs it on every push.
#
# Needs JDK 8, and a clone with its tags (git-version cannot read a linked worktree).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root/library"

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# the version the project and its POM carry with these arguments
project_version() {
  ./gradlew -q properties "$@" | sed -n 's/^version: //p'
}
pom_version() {
  ./gradlew -q generatePomFileForMavenJavaPublication "$@" > /dev/null
  python3 -c 'import sys, xml.etree.ElementTree as E; print(E.parse(sys.argv[1]).getroot().findtext("{http://maven.apache.org/POM/4.0.0}version"))' \
    build/publications/mavenJava/pom-default.xml
}

# distinct from anything a tag gives
given=0.0.0-release-version-test
[ "$(project_version "-PreleaseVersion=$given")" = "$given" ] || failed "the project ignores -PreleaseVersion=$given"
[ "$(pom_version "-PreleaseVersion=$given")" = "$given" ] || failed "the published POM ignores -PreleaseVersion=$given"

tagged=$(git describe --tags --abbrev=0)
tagged=${tagged//v/}
[ "$(project_version)" = "$tagged" ] || failed "without -PreleaseVersion the project is not $tagged, the last tag's"
[ "$(pom_version)" = "$tagged" ] || failed "without -PreleaseVersion the published POM is not $tagged"

if [ "$failures" -ne 0 ]; then
  exit 1
fi
echo "the publication carries -PreleaseVersion when given, $tagged from the last tag otherwise"
