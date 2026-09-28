#!/usr/bin/env bash
#
# Open one issue per shape change that scripts/schema-drift.sh reported, unless this workflow
# already opened it.
#
#   scripts/schema-drift-issues.sh <report>      (lines: branch<TAB>schema commit<TAB>subject)
#
# Open or closed counts as asked: closing the issue records the decision, so it is asked once.
# Only issues the workflow itself opened count, so an issue anyone else opens under the same title
# cannot stand in for it. A lookup that fails stops the script rather than risk asking twice.
#
# Environment: GH_TOKEN, and REPO (owner/name) and GITHUB_RUN_ID from the workflow.
set -euo pipefail

report=${1:?usage: scripts/schema-drift-issues.sh <report>}
repo=${REPO:?REPO is the owner/name of this repository}
[ -s "$report" ] || exit 0

while IFS=$'\t' read -r branch commit subject; do
  title="Schema drift: $branch needs a decision on oddsfeedschema ${commit:0:7}"
  asked=$(gh issue list --repo "$repo" --state all --limit 100 \
    --search "\"$title\" in:title author:app/github-actions" --json title --jq '.[].title')
  if grep -Fxq -- "$title" <<<"$asked"; then
    echo "already asked: $title"
    continue
  fi
  body=$(printf '%s\n' \
    "oddsfeedschema commit $commit ($subject) changes the shape of the schema: it removes or changes a component, or adds a required attribute to one that exists." \
    "" \
    "The \`$branch\` line is pinned before it, and a change like this is not taken without a decision:" \
    "" \
    "- to take it, refresh the pin on \`$branch\` with \`scripts/refresh-schema.sh <commit>\` and adapt the models" \
    "- not to take it, close this issue and say why" \
    "" \
    "Either way this issue is the record; the scheduled drift job does not ask again." \
    "" \
    "Run: https://github.com/$repo/actions/runs/${GITHUB_RUN_ID:-unknown}")
  gh issue create --repo "$repo" --title "$title" --body "$body"
done <"$report"
