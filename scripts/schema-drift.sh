#!/usr/bin/env bash
#
# Compare each release line's vendored oddsfeedschema pin with the schema repository's main.
#
#   scripts/schema-drift.sh [branch ...]        default: release/0.x next
#
# Each line vendors the schema at a pinned commit (vendor/oddsfeedschema/SOURCE on its branch)
# and decodes the fixtures in its own tests. This is the check that the pins keep up:
#
#   - at the schema head, or behind only by commits outside schema/ and test/fixtures/: fine
#   - behind an additive change for up to MAX_LAG_DAYS: fine, the refresh is due by a date
#   - behind an additive change for longer: fails
#   - behind a shape change - anything the XSDs remove or rewrite, or a new required attribute:
#     never fails. A shape change is not taken into a line without a decision, so it is reported
#     as "needs a decision" instead, and written to SHAPE_REPORT when that is set.
#   - no pin, or a pin that is not on the schema's main: fails
#
# The age of a lag is counted from the first schema commit after the pin that touched schema/ or
# test/fixtures/, by its commit time, which on the schema's main is when it was merged.
#
# Environment, all optional:
#   SCHEMA_REPO    schema repository to compare with (default: the public oddsfeedschema)
#   SDK_REMOTE     where the release branches are fetched from (default: origin)
#   PINS           "branch=commit ..." to use instead of reading the branches; for trying it out
#   NOW            the current time in epoch seconds; for trying it out
#   MAX_LAG_DAYS   default 7
#   SHAPE_REPORT   file to append "branch<TAB>schema commit<TAB>subject" to for each shape change
set -euo pipefail

schema_repo=${SCHEMA_REPO:-https://github.com/oddin-gg/oddsfeedschema.git}
sdk_remote=${SDK_REMOTE:-origin}
now=${NOW:-$(date +%s)}
max_lag_days=${MAX_LAG_DAYS:-7}
if [ "$#" -eq 0 ]; then
  set -- release/0.x next
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Commit metadata is all the checks need; blobs arrive only for the diffs that ask for them.
git clone -q --bare --filter=blob:none "$schema_repo" "$work/schema"
schema() { git -C "$work/schema" "$@"; }
head=$(schema rev-parse HEAD)
echo "oddsfeedschema main is at $head ($(schema log -1 --format=%s "$head"))"

pin_of() {
  local branch=$1 entry
  for entry in ${PINS:-}; do
    if [ "${entry%%=*}" = "$branch" ]; then
      echo "${entry#*=}"
      return
    fi
  done
  git fetch -q --no-tags --depth 1 "$sdk_remote" "refs/heads/$branch" 2>/dev/null || return 0
  local source
  source=$(git show FETCH_HEAD:vendor/oddsfeedschema/SOURCE 2>/dev/null) || return 0
  awk '$1 == "commit" { print $2 }' <<<"$source"
}

day() { date -u -d "@$1" +%F 2>/dev/null || date -u -r "$1" +%F; }

# Whether a schema commit changes a shape. Removed or rewritten lines in an XSD change what a
# generated class looks like, and so does a required attribute added to a type that exists, which
# old payloads do not carry. Removed comment-only lines do not count, and a new file is additive
# however much of it is required.
changes_shape() {
  schema diff "$1^" "$1" -- schema \
    | grep -E '^-[^-]' | grep -qvE '^-[[:space:]]*(<!--.*-->)?[[:space:]]*$' && return 0
  schema diff --diff-filter=M "$1^" "$1" -- schema | grep -qE '^\+[^+].*use="required"'
}

failed=0
for branch in "$@"; do
  pin=$(pin_of "$branch")
  if [ -z "$pin" ]; then
    echo "FAIL $branch: no pin (vendor/oddsfeedschema/SOURCE is missing or names no commit)"
    failed=1
    continue
  fi
  if ! schema cat-file -e "$pin^{commit}" 2>/dev/null || ! schema merge-base --is-ancestor "$pin" "$head"; then
    echo "FAIL $branch: its pin $pin is not on the schema's main"
    failed=1
    continue
  fi

  # the schema commits this line has not taken, oldest first
  behind=$(schema log --reverse --format='%H %ct %s' "$pin..$head" -- schema test/fixtures)
  if [ -z "$behind" ]; then
    echo "ok   $branch: up to date (pin $pin)"
    continue
  fi
  first=${behind%%$'\n'*}
  read -r first_commit first_time _ <<<"$first"
  count=$(wc -l <<<"$behind" | tr -d ' ')

  # The first schema commit after the pin that changes a shape, if any.
  shape_commit=
  for commit in $(schema log --reverse --format=%H "$pin..$head" -- schema); do
    if changes_shape "$commit"; then
      shape_commit=$commit
      break
    fi
  done
  if [ -n "$shape_commit" ]; then
    subject=$(schema log -1 --format=%s "$shape_commit")
    echo "WARN $branch: needs a decision - the schema changed shape since its pin $pin,"
    echo "     first in $shape_commit ($subject). Not failing: a shape change is not taken without a decision."
    # the lines that make it one: what it removed, and required attributes it added to existing types
    { schema diff "$shape_commit^" "$shape_commit" -- schema | grep -E '^-[^-]' || true
      schema diff --diff-filter=M "$shape_commit^" "$shape_commit" -- schema | grep -E '^\+[^+].*use="required"' || true
    } | awk 'NR <= 12 { print "       " $0 }'
    if [ -n "${SHAPE_REPORT:-}" ]; then
      printf '%s\t%s\t%s\n' "$branch" "$shape_commit" "$subject" >>"$SHAPE_REPORT"
    fi
    continue
  fi

  deadline=$(( first_time + max_lag_days * 86400 ))
  due=$(day "$deadline")
  if [ "$now" -gt "$deadline" ]; then
    echo "FAIL $branch: $count additive schema change(s) not taken for more than $max_lag_days days, the first"
    echo "     $first_commit ($(schema log -1 --format=%s "$first_commit")), merged $(day "$first_time")."
    echo "     Refresh on $branch: scripts/refresh-schema.sh $head"
    failed=1
  else
    echo "ok   $branch: behind by $count additive schema change(s) since $(day "$first_time"); refresh due by $due"
    echo "     (on $branch: scripts/refresh-schema.sh $head)"
  fi
done
exit "$failed"
