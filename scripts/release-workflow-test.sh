#!/usr/bin/env bash
# The broken copies below are literal workflow text, ${{ }} and $VARIABLES included.
# shellcheck disable=SC2016
#
# Holds .github/workflows/release.yml, and the workflows it calls, to the release policy, which
# no run can show before a release:
#
# - the check job runs scripts/release-check.sh, unconditionally, and its outputs are that step's;
# - publish runs only after check and build succeeded, in the maven-central environment, whose
#   reviewers approve every upload; no other job, here or in a called workflow, uses an
#   environment or reads a secret other than the job token;
# - publish restores no cache, and checks the tag again before the upload, as github-release
#   does before the release, which runs whenever publish succeeded.
#
# The jobs' if: conditions are evaluated over every combination of results, not compared as
# text. Then each rule is broken in a copy of the workflows, and the policy must refuse every
# copy, so a rule that stopped checking anything shows up too. next.yml runs it on every push.
# Needs python3 and yq.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat > "$work/policy.py" <<'EOF'
import itertools, json, os, re, subprocess, sys

root = sys.argv[1]
problems = []

def load(path):
    out = subprocess.run(["yq", "-o=json", ".", path], check=True, capture_output=True, text=True)
    return json.loads(out.stdout)

def check(ok, message):
    if not ok:
        problems.append(message)

w = load(os.path.join(root, ".github/workflows/release.yml"))
jobs = w["jobs"]
RESULTS = ["success", "failure", "cancelled", "skipped"]

def needs(job):
    n = jobs[job].get("needs", [])
    return {n} if isinstance(n, str) else set(n)

def condition(job):
    """the job's if: as a Python function of (results, final, cancelled); none is success()"""
    text = jobs[job].get("if")
    if text is None:
        return lambda results, final, cancelled: not cancelled and all(
            results.get(n) == "success" for n in needs(job))
    text = str(text).strip()
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2]
    text = re.sub(r"\bneeds\.check\.outputs\.final\b", "final", text)
    text = re.sub(r"\bneeds\.([\w-]+)\.result\b", lambda m: "results[%r]" % m.group(1), text)
    text = text.replace("cancelled()", "cancelled").replace("always()", "True")
    text = text.replace("&&", " and ").replace("||", " or ")
    text = re.sub(r"!(?!=)", " not ", text)
    code = compile("(" + " ".join(text.split()) + ")", job + ".if", "eval")
    unknown = set(code.co_names) - {"results", "final", "cancelled", "True"}
    if unknown:
        problems.append("%s: cannot evaluate if: %s (%s)" % (job, jobs[job]["if"], ", ".join(sorted(unknown))))
        return None
    return lambda results, final, cancelled: bool(eval(code, {"__builtins__": {}},
        {"results": results, "final": final, "cancelled": cancelled}))

def environment(job_def):
    env = job_def.get("environment")
    return env.get("name") if isinstance(env, dict) else env

def steps(job):
    return jobs[job].get("steps", [])

def index(job, needle):
    for i, step in enumerate(steps(job)):
        if needle in json.dumps(step):
            return i
    return -1

def unconditional(job, step, what):
    check("if" not in step, "%s: %s must not be conditional" % (job, what))
    check("continue-on-error" not in step, "%s: %s must not continue on error" % (job, what))

# triggers: v1.* tags and nothing else
check(w.get("on") == {"push": {"tags": ["v1.*"]}}, "release.yml must run on v1.* tags only")
check(w.get("permissions") == {"contents": "read"}, "the workflow default must be contents: read")

# check: the release check runs, always, and the outputs are its own
CHECK = './scripts/release-check.sh "$GITHUB_REF_NAME"'
check("if" not in jobs["check"], "check must not be conditional")
check(not jobs["check"].get("continue-on-error"), "check must not continue on error")
found = [s for s in steps("check") if str(s.get("run", "")).strip() == CHECK]
check(len(found) == 1, "check must run %s as a step of its own, once" % CHECK)
if len(found) == 1:
    step = found[0]
    unconditional("check", step, "the release check")
    outputs = jobs["check"].get("outputs", {})
    for name in ("version", "final", "commit"):
        check(outputs.get(name) == "${{ steps.%s.outputs.%s }}" % (step.get("id"), name),
              "check's %s must be the release check's own output, not %r" % (name, outputs.get(name)))

# publish: after check and build succeeded, in the environment whose reviewers approve it
check(environment(jobs["publish"]) == "maven-central", "publish must use maven-central")
check({"check", "build"} <= needs("publish"), "publish must need check and build")
check(not jobs["publish"].get("continue-on-error"), "publish must not continue on error")
publish_if = condition("publish")
if publish_if:
    for check_result, build, final, cancelled in itertools.product(RESULTS, RESULTS, ["true", "false"], [False, True]):
        results = {"check": check_result, "build": build}
        runs = publish_if(results, final, cancelled)
        allowed = not cancelled and check_result == "success" and build == "success"
        check(runs == allowed, "publish with check=%s build=%s final=%s cancelled=%s: runs=%s, should be %s"
              % (check_result, build, final, cancelled, runs, allowed))

# github-release: whenever publish succeeded, final or not
check("publish" in needs("github-release"), "github-release must need publish")
check(not jobs["github-release"].get("continue-on-error"), "github-release must not continue on error")
release_if = condition("github-release")
if release_if:
    for publish, final, cancelled in itertools.product(RESULTS, ["true", "false"], [False, True]):
        results = {"check": "success", "build": "success", "publish": publish}
        runs = release_if(results, final, cancelled)
        check(runs == (publish == "success" and not cancelled),
              "github-release with publish=%s final=%s cancelled=%s: runs=%s" % (publish, final, cancelled, runs))

# Secrets and environments: publish alone, here and in every workflow these jobs call, however
# deep. A called workflow runs on the tag too, so maven-central would admit it.
def isolated(where, job_def, allowed_environment=None):
    text = json.dumps(job_def)
    if allowed_environment is None:
        check(environment(job_def) is None, "%s must not use an environment" % where)
        others = set(re.findall(r"secrets\.(\w+)", text)) - {"GITHUB_TOKEN"}
        check(not others, "%s must not read secrets: %s" % (where, ", ".join(sorted(others))))
    check(not re.search(r"toJSON\(\s*secrets\s*\)", text, re.I), "%s must not hand out every secret" % where)
    check(job_def.get("secrets") is None, "%s must not pass secrets on" % where)

seen = set()
def called(where, job_def):
    uses = job_def.get("uses", "")
    if not uses.startswith("./.github/workflows/"):
        check(not uses.startswith(".") and "/.github/workflows/" not in uses,
              "%s calls a workflow this test cannot follow: %s" % (where, uses))
        return
    path = os.path.join(root, uses[2:])
    if path in seen:
        return
    seen.add(path)
    for name, nested in load(path).get("jobs", {}).items():
        isolated("%s > %s" % (uses, name), nested)
        called("%s > %s" % (uses, name), nested)

for name, job_def in jobs.items():
    isolated(name, job_def, "maven-central" if name == "publish" else None)
    called(name, job_def)

# publish: no cache another job could have written - no cache action in any form, no cache input
# on any setup action
for step in steps("publish"):
    uses = step.get("uses", "")
    check(not re.match(r"actions/cache([/@]|$)", uses), "publish must not restore a cache: %s" % uses)
    inputs = step.get("with", {}) or {}
    check(not any(key == "cache" or key.startswith("cache-") for key in inputs),
          "publish must not restore a cache: %s with %s" % (uses or step.get("name"), sorted(inputs)))

# the tag checked again before the upload and before the release, and a failed check stops the job
TAG_CHECK = './scripts/release-tag-check.sh "$GITHUB_REF_NAME" "$COMMIT"'
for job in ("publish", "github-release"):
    found = [step for step in steps(job) if "release-tag-check.sh" in json.dumps(step)]
    check(len(found) == 1, "%s must check the tag once, found %d" % (job, len(found)))
    for step in found:
        check(str(step.get("run", "")).strip() == TAG_CHECK,
              "%s: the tag check must run %s and nothing else, not %r" % (job, TAG_CHECK, step.get("run")))
        unconditional(job, step, "the tag check")
check(-1 < index("publish", "release-tag-check.sh") < index("publish", " deploy"),
      "publish must check the tag again before it deploys")
check(-1 < index("github-release", "release-tag-check.sh") < index("github-release", "gh release create"),
      "github-release must check the tag again before it creates the release")

for problem in problems:
    print("FAIL " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
EOF

policy() {
  python3 "$work/policy.py" "$1"
}

if ! policy "$root"; then
  echo "release.yml breaks the release policy" >&2
  exit 1
fi
echo "release.yml keeps its release policy"

# Each rule broken once, in a copy: the policy must refuse each copy, for that rule.
failures=0
# breaks <workflow file> <what to replace> <what with> <what the refusal says>
breaks() {
  local file=$1 old=$2 new=$3 reason=$4 copy=$work/copy
  rm -rf "$copy"
  mkdir -p "$copy/.github"
  cp -R "$root/.github/workflows" "$copy/.github/"
  if ! python3 - "$copy/.github/workflows/$file" "$old" "$new" <<'EOF'
import sys
path, old, new = sys.argv[1:]
text = open(path).read()
if old not in text:
    sys.exit("the text to replace is not in " + path + ": " + old)
open(path, "w").write(text.replace(old, new, 1))
EOF
  then
    echo "FAIL could not break the copy for \"$reason\"" >&2
    failures=$((failures + 1))
    return
  fi
  if policy "$copy" 2> "$work/err"; then
    echo "FAIL the policy accepted a copy that should fail with \"$reason\"" >&2
    failures=$((failures + 1))
  elif ! grep -qF -- "$reason" "$work/err"; then
    echo "FAIL the policy refused a copy, but not with \"$reason\": $(cat "$work/err")" >&2
    failures=$((failures + 1))
  else
    echo "ok   refused: $reason"
  fi
}

check_step='        run: ./scripts/release-check.sh "$GITHUB_REF_NAME"'
tag_step='      - name: Check the tag still names this commit
'
breaks release.yml "$check_step" '        run: echo "version=1.0.0" >> "$GITHUB_OUTPUT"' \
  'check must run ./scripts/release-check.sh "$GITHUB_REF_NAME" as a step of its own'
breaks release.yml "$check_step" "        if: false
$check_step" "check: the release check must not be conditional"
breaks release.yml "$check_step" "        continue-on-error: true
$check_step" "check: the release check must not continue on error"
breaks release.yml '      version: ${{ steps.tag.outputs.version }}' '      version: 1.0.0' \
  "check's version must be the release check's own output"
breaks release.yml '      final: ${{ steps.tag.outputs.final }}' "      final: 'false'" \
  "check's final must be the release check's own output"
breaks release.yml '    name: Check the tag
' '    name: Check the tag
    if: false
' "check must not be conditional"
breaks release.yml '    environment: maven-central' '    environment: production' "publish must use maven-central"
breaks release.yml '    needs: [check, build]
    runs-on: ubuntu-latest
    timeout-minutes: 90' '    needs: [check, build]
    if: ${{ always() }}
    runs-on: ubuntu-latest
    timeout-minutes: 90' "publish with check=failure"
breaks release.yml '    needs: [check, publish]' '    needs: [check, publish]
    environment: maven-central' "github-release must not use an environment"
breaks release.yml "if: \${{ !cancelled() && needs.publish.result == 'success' }}" \
  "if: \${{ !cancelled() && needs.publish.result == 'success' && needs.check.outputs.final == 'false' }}" \
  "github-release with publish=success final=true"
breaks release.yml '      revision: ${{ needs.check.outputs.version }}' '      revision: ${{ needs.check.outputs.version }}
    secrets: inherit' "build must not pass secrets on"
breaks release.yml '          GH_REPO: ${{ github.repository }}' '          GH_REPO: ${{ github.repository }}
          LEAK: ${{ secrets.MAVEN_GPG_KEY }}' "github-release must not read secrets: MAVEN_GPG_KEY"
breaks next.yml '    name: Build & Test
    runs-on: ubuntu-latest' '    name: Build & Test
    environment: maven-central
    runs-on: ubuntu-latest' "./.github/workflows/next.yml > build must not use an environment"
breaks next.yml '          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}' '          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          KEY: ${{ secrets.MAVEN_GPG_KEY }}' "./.github/workflows/next.yml > build must not read secrets: MAVEN_GPG_KEY"
breaks next.yml '          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}' '          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ALL: ${{ toJSON(secrets) }}' "./.github/workflows/next.yml > build must not hand out every secret"
breaks release.yml "$tag_step" "${tag_step}        continue-on-error: true
" "publish: the tag check must not continue on error"
breaks release.yml 'run: ./scripts/release-tag-check.sh "$GITHUB_REF_NAME" "$COMMIT"' \
  'run: ./scripts/release-tag-check.sh "$GITHUB_REF_NAME" "$COMMIT" || true' "publish: the tag check must run"
breaks release.yml '      - name: Sign and publish
' '      - name: Restore
        uses: actions/cache/restore@v4
        with:
          path: ~/.m2
          key: maven

      - name: Sign and publish
' "publish must not restore a cache: actions/cache/restore@v4"
breaks release.yml "          distribution: 'corretto'
          # No cache" "          distribution: 'corretto'
          cache: 'maven'
          # No cache" "publish must not restore a cache: actions/setup-java"

if [ "$failures" -ne 0 ]; then
  echo "$failures broken copies were not refused as they should be" >&2
  exit 1
fi
echo "every broken copy was refused for its rule"
