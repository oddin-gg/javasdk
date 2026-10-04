#!/usr/bin/env bash
#
# Creates the GitHub Release for a published version: the release notes as its text, and the jar
# and POM Central has, with their signatures, so the release page and Central agree.
#
# Releases are immutable once published, and an immutable release takes no more assets, so it is
# created as a draft with every asset attached, and only then published. A draft left behind by
# a failed run can be deleted (gh release delete <tag>, which keeps the tag) before a re-run.
#
# Run by release.yml's github-release job, in a checkout of the released commit holding
# central-bundle.zip, with VERSION, FINAL (true for a final), GITHUB_REF_NAME (the tag) and gh's
# GH_TOKEN and GH_REPO. Tested by scripts/release-github-test.sh with a stub gh.
set -euo pipefail

version=${VERSION:?}
final=${FINAL:?}
tag=${GITHUB_REF_NAME:?}

unzip -q central-bundle.zip -d bundle
dir=bundle/gg/oddin/oddsfeed/odds-feed/$version
files=()
for name in "odds-feed-$version.jar" "odds-feed-$version.pom"; do
  for file in "$dir/$name" "$dir/$name.asc"; do
    if [ ! -s "$file" ]; then
      echo "the bundle has no $file" >&2
      exit 1
    fi
    files+=("$file")
  done
done
prerelease=()
if [ "$final" != "true" ]; then
  prerelease=(--prerelease)
fi

gh release create "$tag" --verify-tag --draft --title "$version" \
  --notes-file "release-notes/$version.md" ${prerelease[@]+"${prerelease[@]}"} "${files[@]}"
gh release edit "$tag" --draft=false
