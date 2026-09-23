#!/usr/bin/env bash
#
# Replace vendor/oddsfeedschema with schema/ and test/fixtures/ from the oddsfeedschema
# repository at the given tag, branch or commit.
#
#   scripts/refresh-schema.sh v1.0.0
#
# The build only ever reads the vendored copy. It never reaches out to another repository,
# so old releases stay rebuildable and a hotfix build does not depend on the schema repo
# being reachable. Run this by hand to move the pin, and commit the result.
#
# What it records depends only on the commit, never on how you named it, so running it again
# against the same commit changes nothing.
set -euo pipefail

ref=${1:?usage: scripts/refresh-schema.sh <tag, branch or commit>}
url=https://github.com/oddin-gg/oddsfeedschema.git
root=$(cd "$(dirname "$0")/.." && pwd)
dest=$root/vendor/oddsfeedschema

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git -C "$work" init -q
git -C "$work" fetch -q --depth 1 "$url" "$ref"
commit=$(git -C "$work" rev-parse 'FETCH_HEAD^{commit}')

# every tag pointing at this commit, annotated or not
releases=$(git ls-remote --tags "$url" \
  | awk -v c="$commit" '$1 == c { sub("refs/tags/", "", $2); sub("\\^\\{\\}$", "", $2); print $2 }' \
  | sort -u | paste -sd ' ' -)

# Build the new copy on the side and swap it in only once it is complete, so a ref that lacks
# one of the directories leaves the current copy exactly as it was.
out=$work/out
mkdir -p "$out"
if ! git -C "$work" archive -o "$work/vendored.tar" FETCH_HEAD schema test/fixtures 2>/dev/null; then
  echo "$ref ($commit) has no schema/ or no test/fixtures/; nothing was changed" >&2
  exit 1
fi
# git archive gives exactly the tracked files, with their modes, and nothing else
tar -x -C "$out" -f "$work/vendored.tar"

cat > "$out/SOURCE" <<SOURCE
# Vendored from the oddsfeedschema repository. Do not edit these files by hand:
# run scripts/refresh-schema.sh <tag or commit> and commit the result.
repository $url
commit $commit
release ${releases:-none}
SOURCE

rm -rf "$dest"
mkdir -p "$(dirname "$dest")"
mv "$out" "$dest"

echo "vendored $url at $commit${releases:+ ($releases)}"
