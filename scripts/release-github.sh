#!/usr/bin/env bash
#
# Creates the GitHub Release for a published version: the release notes as its text, and the jar
# and POM Central has, with their signatures, so the release page and Central agree.
#
# Releases are immutable once published, and an immutable release takes no more assets, so it is
# created as a draft, the assets go onto that draft, and only then is it published. Everything
# after the creation addresses the release by the id GitHub gave it, never by the tag: a draft a
# failed run left for the same tag is never the one filled or published. Such a draft can be
# deleted (gh release delete <tag>, which keeps the tag) at leisure.
#
# Run by release.yml's github-release job, in a checkout of the released commit holding
# central-bundle.zip, with VERSION, FINAL (true for a final), GITHUB_REF_NAME (the tag) and gh's
# GH_TOKEN and GH_REPO. Tested by scripts/release-github-test.sh with a stub gh.
set -euo pipefail

version=${VERSION:?}
final=${FINAL:?}
tag=${GITHUB_REF_NAME:?}
repo=${GH_REPO:?}
notes=release-notes/$version.md

fail() {
  echo "$tag: $*" >&2
  exit 1
}

[ -s "$notes" ] || fail "$notes is missing or empty"
unzip -q central-bundle.zip -d bundle
dir=bundle/gg/oddin/oddsfeed/odds-feed/$version
files=()
for name in "odds-feed-$version.jar" "odds-feed-$version.pom"; do
  for file in "$dir/$name" "$dir/$name.asc"; do
    [ -s "$file" ] || fail "the bundle has no $file"
    files+=("$file")
  done
done
prerelease=true
if [ "$final" = "true" ]; then
  prerelease=false
fi

# the tag must exist: given a tag it does not know, GitHub would make one
gh api "repos/$repo/git/ref/tags/$tag" --silent || fail "GitHub has no tag $tag"

created=$(gh api -X POST "repos/$repo/releases" -f tag_name="$tag" -f name="$version" \
  -F body=@"$notes" -F draft=true -F prerelease="$prerelease" --jq '.id, .upload_url') \
  || fail "could not create the draft release"
id=$(printf '%s\n' "$created" | sed -n 1p)
upload=$(printf '%s\n' "$created" | sed -n 2p)
upload=${upload%%\{*}
[[ "$id" =~ ^[0-9]+$ ]] || fail "GitHub gave no release id: $created"
[ "$upload" = "https://uploads.github.com/repos/$repo/releases/$id/assets" ] \
  || fail "GitHub gave release $id an unexpected upload address: $upload"

for file in "${files[@]}"; do
  gh api -X POST "$upload?name=$(basename "$file")" -H "Content-Type: application/octet-stream" \
    --input "$file" --silent || fail "could not attach $(basename "$file") to draft release $id"
done
gh api -X PATCH "repos/$repo/releases/$id" -F draft=false --silent || fail "could not publish release $id"
echo "$tag: release $id published with ${#files[@]} files" >&2
