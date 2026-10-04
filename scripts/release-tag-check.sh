#!/usr/bin/env bash
#
# Whether the tag still names the commit the release was checked and built from. A release can
# wait days for its approval, and the tag can be moved or deleted meanwhile: release.yml runs
# this right before the upload to Central and again before the GitHub Release. Tested by
# scripts/release-check-test.sh.
#
#   scripts/release-tag-check.sh <tag> <commit> [remote]
#
# The remote defaults to origin and is asked, not the local clone.
set -euo pipefail

tag=${1:?usage: release-tag-check.sh <tag> <commit> [remote]}
commit=${2:?usage: release-tag-check.sh <tag> <commit> [remote]}
remote=${3:-origin}

# A remote that stalls counts as one that does not answer: git gives up on a transfer slower than
# a byte a second for 30 seconds, and timeout (coreutils, on the runners) on the whole question
# after a minute. Without timeout, as on a stock macOS, the transfer limit alone applies.
limit=()
if command -v timeout > /dev/null; then
  limit=(timeout 60)
fi
refs=$(${limit[@]+"${limit[@]}"} git -c http.lowSpeedLimit=1 -c http.lowSpeedTime=30 \
  ls-remote --tags "$remote" "refs/tags/$tag" "refs/tags/$tag^{}") || {
  echo "$tag: could not ask $remote for the tag" >&2
  exit 1
}
# an annotated tag lists the commit under ^{}; a lightweight one lists it directly
now=$(printf '%s\n' "$refs" | awk -v ref="refs/tags/$tag^{}" '$2 == ref { print $1 }')
if [ -z "$now" ]; then
  now=$(printf '%s\n' "$refs" | awk -v ref="refs/tags/$tag" '$2 == ref { print $1 }')
fi
if [ -z "$now" ]; then
  echo "$tag: no longer on $remote" >&2
  exit 1
fi
if [ "$now" != "$commit" ]; then
  echo "$tag: now names $now on $remote, not $commit, the commit this run is for" >&2
  exit 1
fi
echo "$tag: still names $commit" >&2
