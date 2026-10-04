#!/usr/bin/env bash
#
# Holds .github/workflows/release.yml to its release policy, which no run can show before a
# release: a final reaches Central only through the approval, a release candidate without it,
# the secrets only in the job that uploads, and that job checks the tag again first. The jobs'
# if: conditions are evaluated over every combination of results, not compared as text.
# next.yml runs it on every push. Needs python3 and yq.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
workflow=${1:-$root/.github/workflows/release.yml}

WORKFLOW_JSON=$(yq -o=json '.' "$workflow") python3 - <<'EOF'
import itertools, json, os, re, sys

w = json.loads(os.environ["WORKFLOW_JSON"])
jobs = w["jobs"]
problems = []

def check(ok, message):
    if not ok:
        problems.append(message)

def condition(job):
    """the job if: as a Python function of (results, final, cancelled)"""
    text = jobs[job].get("if")
    if text is None:
        return None
    text = str(text).strip()
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2]
    text = re.sub(r"\bneeds\.check\.outputs\.final\b", "final", text)
    text = re.sub(r"\bneeds\.([\w-]+)\.result\b", lambda m: "results[%r]" % m.group(1), text)
    text = text.replace("cancelled()", "cancelled")
    text = text.replace("&&", " and ").replace("||", " or ")
    text = re.sub(r"!(?!=)", " not ", text)
    text = text.replace("always()", "True")
    code = compile("(" + " ".join(text.split()) + ")", job + ".if", "eval")
    unknown = set(code.co_names) - {"results", "final", "cancelled", "True"}
    if unknown:
        problems.append("%s: cannot evaluate if: %s (%s)" % (job, jobs[job]["if"], ", ".join(sorted(unknown))))
        return None
    return lambda results, final, cancelled: bool(eval(code, {"__builtins__": {}},
        {"results": results, "final": final, "cancelled": cancelled}))

def environment(job):
    env = jobs[job].get("environment")
    return env.get("name") if isinstance(env, dict) else env

def needs(job):
    n = jobs[job].get("needs", [])
    return {n} if isinstance(n, str) else set(n)

RESULTS = ["success", "failure", "cancelled", "skipped"]

# triggers: v1.* tags and nothing else
check(w.get("on") == {"push": {"tags": ["v1.*"]}}, "release.yml must run on v1.* tags only")
check(w.get("permissions") == {"contents": "read"}, "the workflow default must be contents: read")

# approve: the environment with the reviewers, for finals only
check(environment("approve") == "maven-central-approval", "approve must use maven-central-approval")
check({"check", "build"} <= needs("approve"), "approve must need check and build")
approve_if = condition("approve")
check(approve_if is not None, "approve must have an if:")
if approve_if:
    for final in ["true", "false"]:
        runs = approve_if({}, final, False)
        check(runs == (final == "true"), "approve runs for final=%s: %s" % (final, runs))

# publish: after a successful build, and for a final only after an approval
check(environment("publish") == "maven-central", "publish must use maven-central")
check({"check", "build", "approve"} <= needs("publish"), "publish must need check, build and approve")
publish_if = condition("publish")
check(publish_if is not None, "publish must have an if:")
if publish_if:
    for build, approve, final, cancelled in itertools.product(RESULTS, RESULTS, ["true", "false"], [False, True]):
        results = {"check": "success", "build": build, "approve": approve}
        runs = publish_if(results, final, cancelled)
        allowed = (not cancelled and build == "success"
                   and (approve == "success" or (approve == "skipped" and final == "false")))
        check(runs == allowed, "publish with build=%s approve=%s final=%s cancelled=%s: runs=%s, should be %s"
              % (build, approve, final, cancelled, runs, allowed))

# github-release: whenever publish succeeded, final or not, whatever approve did
check("publish" in needs("github-release"), "github-release must need publish")
release_if = condition("github-release")
check(release_if is not None, "github-release needs an explicit if: the default skips it after a skipped approve")
if release_if:
    for publish, approve, final, cancelled in itertools.product(RESULTS, RESULTS, ["true", "false"], [False, True]):
        results = {"check": "success", "build": "success", "approve": approve, "publish": publish}
        runs = release_if(results, final, cancelled)
        check(runs == (publish == "success" and not cancelled),
              "github-release with publish=%s approve=%s final=%s cancelled=%s: runs=%s"
              % (publish, approve, final, cancelled, runs))

# the secrets and the maven-central environment belong to publish alone
for name, job in jobs.items():
    if name != "publish":
        check("secrets." not in json.dumps(job), "%s must not read secrets" % name)
        if name != "approve":
            check(environment(name) is None, "%s must not use an environment" % name)
    check(job.get("secrets") is None, "%s must not pass secrets on" % name)

def steps(job):
    return jobs[job].get("steps", [])

def index(job, needle):
    for i, step in enumerate(steps(job)):
        if needle in json.dumps(step):
            return i
    return -1

# publish: no cache another job could have written - no cache action in any form, no cache input
# on any setup action
for step in steps("publish"):
    uses = step.get("uses", "")
    check(not re.match(r"actions/cache([/@]|$)", uses), "publish must not restore a cache: %s" % uses)
    inputs = step.get("with", {}) or {}
    check(not any(key == "cache" or key.startswith("cache-") for key in inputs),
          "publish must not restore a cache: %s with %s" % (uses or step.get("name"), sorted(inputs)))

# the tag check again before the upload and before the release, and a failed one stops the job:
# the script call alone, unconditional, with no continue-on-error on the step or the job
TAG_CHECK = './scripts/release-tag-check.sh "$GITHUB_REF_NAME" "$COMMIT"'
for job in ("publish", "github-release"):
    check(not jobs[job].get("continue-on-error"), "%s must not continue on error" % job)
    found = [step for step in steps(job) if "release-tag-check.sh" in json.dumps(step)]
    check(len(found) == 1, "%s must check the tag once, found %d" % (job, len(found)))
    for step in found:
        check(str(step.get("run", "")).strip() == TAG_CHECK,
              "%s: the tag check must run %s and nothing else, not %r" % (job, TAG_CHECK, step.get("run")))
        check("if" not in step, "%s: the tag check must not be conditional" % job)
        check("continue-on-error" not in step, "%s: the tag check must not continue on error" % job)
check(-1 < index("publish", "release-tag-check.sh") < index("publish", " deploy"),
      "publish must check the tag again before it deploys")
check(-1 < index("github-release", "release-tag-check.sh") < index("github-release", "gh release create"),
      "github-release must check the tag again before it creates the release")

for problem in problems:
    print("FAIL " + problem, file=sys.stderr)
if problems:
    sys.exit(1)
print("release.yml keeps its release policy")
EOF
