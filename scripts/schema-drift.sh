#!/usr/bin/env bash
#
# Compare each release line's vendored oddsfeedschema pin with the schema repository's main.
#
#   scripts/schema-drift.sh [branch ...]        default: release/0.x next
#
# Each line vendors the schema at a pinned commit (vendor/oddsfeedschema/SOURCE on its branch)
# and decodes the fixtures in its own tests. This is the check that the pins keep up. For each
# line it walks the schema commits the line has not taken, oldest first, and sorts them:
#
#   - a shape change: an XSD component removed or changed, or a required attribute added to a
#     component that exists (scripts/schema_shape.py decides, from the XSDs as trees). A line
#     takes none of these without a decision, so they never fail this; each one is reported as
#     "needs a decision", and written to SHAPE_REPORT when that is set.
#   - everything else is additive. Additive changes before the first shape change can be taken by
#     refreshing to the commit just before it; once the first of them is older than MAX_LAG_DAYS
#     this fails, naming that refresh. Additive changes after it wait for the decision.
#
# Commits outside schema/ and test/fixtures/ never count. A missing pin, a pin that is not on the
# schema's main, a branch that cannot be fetched, or a git command that fails also fail this.
#
# The age of a lag is counted from the commit time, which on the schema's main is when the change
# was merged.
#
# Environment, all optional:
#   SCHEMA_REPO    schema repository to compare with (default: the public oddsfeedschema)
#   SDK_REMOTE     where the release branches are fetched from (default: origin)
#   PINS           "branch=commit ..." to use instead of reading the branches; for tests
#   NOW            the current time in epoch seconds; for tests
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
here=$(cd "$(dirname "$0")" && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Commit metadata is all most checks need; blobs arrive only for the files that are compared.
git clone -q --bare --filter=blob:none "$schema_repo" "$work/schema"
schema() { git -C "$work/schema" "$@"; }
head=$(schema rev-parse HEAD)
echo "oddsfeedschema main is at $head ($(schema log -1 --format=%s "$head"))"

broken() {
  echo "FAIL $*" >&2
  exit 1
}

# The pinned commit of a branch, empty if the branch has no pin; fails if it cannot be fetched.
pin_of() {
  local branch=$1 entry source
  for entry in ${PINS:-}; do
    if [ "${entry%%=*}" = "$branch" ]; then
      echo "${entry#*=}"
      return 0
    fi
  done
  git fetch -q --no-tags --depth 1 "$sdk_remote" "refs/heads/$branch" || return 3
  source=$(git show FETCH_HEAD:vendor/oddsfeedschema/SOURCE 2>/dev/null) || return 0
  awk '$1 == "commit" { print $2 }' <<<"$source"
}

day() { date -u -d "@$1" +%F 2>/dev/null || date -u -r "$1" +%F; }

# Whether a schema commit changes a shape: 0 it does, with what changed in $work/shape; 1 it is
# additive; 2 it cannot be compared, with why in $work/error.
changes_shape() {
  local commit=$1 status path renamed old new changed=1 verdict files
  : >"$work/shape"
  if ! files=$(schema diff --find-renames --name-status "$commit^" "$commit" -- schema); then
    echo "cannot diff schema commit $commit" >"$work/error"
    return 2
  fi
  while IFS=$'\t' read -r status path renamed; do
    [ -n "$path" ] || continue
    old=$work/old.xsd new=$work/new.xsd
    local before=$path after=$path
    case "$status" in
      R*) after=$renamed ;; # a move: its content before and after
      A) old=/dev/null ;;
      D) new=/dev/null ;;
    esac
    case "$before$after" in *.xsd*) ;; *) continue ;; esac
    if [ "$old" != /dev/null ] && ! schema show "$commit^:$before" >"$old"; then
      echo "cannot read $before before $commit" >"$work/error"
      return 2
    fi
    if [ "$new" != /dev/null ] && ! schema show "$commit:$after" >"$new"; then
      echo "cannot read $after at $commit" >"$work/error"
      return 2
    fi
    verdict=0
    python3 "$here/schema_shape.py" "$old" "$new" >"$work/one" || verdict=$?
    case "$verdict" in
      0) ;;
      # the file name is upstream data: passed to awk as a value, never spliced into a program
      1) changed=0; awk -v file="$after" '{ print file ": " $0 }' "$work/one" >>"$work/shape" ;;
      *) { echo "cannot compare $after at $commit:"; cat "$work/one"; } >"$work/error"; return 2 ;;
    esac
  done <<<"$files"
  return "$changed"
}

failed=0
for branch in "$@"; do
  status=0
  pin=$(pin_of "$branch") || status=$?
  if [ "$status" -eq 3 ]; then
    echo "FAIL $branch: cannot fetch it from $sdk_remote"
    failed=1
    continue
  fi
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
  # --first-parent: a merged pull request counts once, at the time it was merged
  behind=$(schema log --first-parent --reverse --format='%H %ct' "$pin..$head" -- schema test/fixtures) \
    || broken "cannot list the schema commits after $pin"
  if [ -z "$behind" ]; then
    echo "ok   $branch: up to date (pin $pin)"
    continue
  fi

  first_additive= first_time= first_shape= waiting=0
  unreadable=
  while read -r commit time; do
    verdict=0
    changes_shape "$commit" || verdict=$?
    if [ "$verdict" = 2 ]; then
      # this line cannot be judged; the others still are
      echo "FAIL $branch: $(cat "$work/error")"
      unreadable=1
      break
    fi
    if [ "$verdict" = 0 ]; then
      [ -n "$first_shape" ] || first_shape=$commit
      subject=$(schema log -1 --format=%s "$commit")
      echo "WARN $branch: needs a decision - $commit ($subject) changes the schema's shape:"
      awk 'NR <= 12 { print "       " $0 }' "$work/shape"
      if [ -n "${SHAPE_REPORT:-}" ]; then
        printf '%s\t%s\t%s\n' "$branch" "$commit" "$subject" >>"$SHAPE_REPORT"
      fi
    elif [ -n "$first_shape" ]; then
      waiting=$((waiting + 1))
    elif [ -z "$first_additive" ]; then
      first_additive=$commit first_time=$time
    fi
  done <<<"$behind"
  if [ -n "$unreadable" ]; then
    failed=1
    continue
  fi

  if [ "$waiting" -gt 0 ]; then
    echo "     $branch: $waiting additive change(s) after $first_shape wait for its decision"
  fi
  if [ -z "$first_additive" ]; then
    continue # behind shape changes only, each reported above
  fi
  # the newest commit this line can take without a decision
  target=$head
  if [ -n "$first_shape" ]; then
    target=$(schema rev-parse "$first_shape^")
  fi
  deadline=$((first_time + max_lag_days * 86400))
  if [ "$now" -gt "$deadline" ]; then
    echo "FAIL $branch: additive schema changes not taken for more than $max_lag_days days, the first"
    echo "     $first_additive ($(schema log -1 --format=%s "$first_additive")), merged $(day "$first_time")."
    echo "     Refresh on $branch: scripts/refresh-schema.sh $target"
    failed=1
  else
    echo "ok   $branch: behind additive schema changes since $(day "$first_time"); refresh due by $(day "$deadline")"
    echo "     (on $branch: scripts/refresh-schema.sh $target)"
  fi
done
exit "$failed"
