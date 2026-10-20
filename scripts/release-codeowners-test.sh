#!/usr/bin/env bash
#
# Checks that .github/CODEOWNERS makes the release managers' team the owner of everything the
# release build runs with the signing key and the Central token. GitHub reports a file it cannot
# read (next.yml asks it), but not an ownership line that was removed or no longer matches: the
# file stays valid and the path is simply owned by nobody. So the owner of each path is resolved
# here, the way GitHub does, and must be the team: a list of paths kept here, and the scripts the
# workflows run and the files those scripts read, derived, so that none added later is missed.
# next.yml runs it on every push. Needs python3 and yq.
#
#   scripts/release-codeowners-test.sh
#
# Only patterns whose meaning is the same in .gitignore and in CODEOWNERS are resolved, and the
# test refuses any other, so that it never guesses: a leading / (from the root), a trailing / (the
# directory and all below it), * within one path segment, and a leading **/ (at any depth). A
# pattern without a / is read as at any depth; an owner list is the whole of the last pattern that
# matches, as GitHub has it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
python3 -I - "$root" <<'PY'
import json, os, re, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

root = sys.argv[1]
TEAM = ["@oddin-gg/ragnar-admin"]

def regex(pattern):
    """The regular expression for one CODEOWNERS pattern, or a refusal for what is not unambiguous."""
    if re.search(r"[^A-Za-z0-9._*/@+-]", pattern) or "***" in pattern or "[" in pattern:
        raise ValueError("pattern %r has syntax this test does not resolve" % pattern)
    directory = pattern.endswith("/")
    body = pattern.strip("/")
    anchored = pattern.startswith("/") or "/" in body
    if body.startswith("**/"):
        anchored, body = False, body[3:]
    if "**" in body or not body:
        raise ValueError("pattern %r has a ** this test does not resolve" % pattern)
    expression = "/".join(re.escape(part).replace(r"\*", "[^/]*") for part in body.split("/"))
    prefix = "" if anchored else "(?:.*/)?"
    # a path is matched by its own pattern or by one of its parent directories, as in gitignore
    return re.compile("^" + prefix + expression + "/.*$" if directory else "^" + prefix + expression + "(?:/.*)?$")

def owners(lines, path):
    """The owners of a path: those of the last pattern that matches it, [] when none does."""
    found = []
    for number, line in enumerate(lines, 1):
        text = line.split("#", 1)[0].split()
        if not text:
            continue
        if regex(text[0]).match(path):
            found = text[1:]
    return found

def modules(tree, directory):
    """Every module of the reactor, from <modules> of the root POM down, as paths from the root."""
    pom = ET.parse(os.path.join(tree, directory, "pom.xml"))
    names = [m.text.strip() for m in pom.getroot().iter() if m.tag.rsplit("}", 1)[-1] == "module"]
    found = []
    for name in names:
        path = os.path.normpath(os.path.join(directory, name))
        found.append(path)
        found.extend(modules(tree, path))
    return found

# What the workflows run and what those scripts read is derived, so that a script or a file added
# to the build cannot be missed by a list kept by hand: every scripts/ path in a run: of a
# workflow, and every file such a script names by a literal path from the repository root
# ($root/..., or $here/... in the scripts that set it to their own directory) or from its own
# directory (a helper called through dirname of $0). A script or helper among those files is
# read in turn, to the end, so a helper's helper is owned too.
def workflow_scripts(tree):
    found = {}
    directory = os.path.join(tree, ".github/workflows")
    for name in sorted(os.listdir(directory)):
        if not name.endswith((".yml", ".yaml")):
            continue
        runs = subprocess.run(["yq", "-o=json", '[.. | select(tag == "!!map" and has("run")) | .run]',
                               os.path.join(directory, name)], check=True, capture_output=True, text=True).stdout
        for run in json.loads(runs):
            for path in re.findall(r"(?<![\w/.-])(?:\./)?(scripts/[A-Za-z0-9_.-]+)", str(run)):
                found.setdefault(path, name)
    return found

def contained(tree, path):
    """Whether what is at the path, once every symlink on the way is followed, is inside the tree."""
    full = os.path.join(tree, path)
    return not os.path.lexists(full) or (os.path.realpath(full) + os.sep).startswith(os.path.realpath(tree) + os.sep)

def inside(tree, base, path, trim=False):
    """The path from the repository root of what a script names from a base directory, or None when
    it is not a file inside the repository: the repository itself, or a path that leaves it, written
    as it may be - with a .. in the middle of it too - or by a symlink. A doubled slash is none of
    these: the shell reads a//b as a/b."""
    resolved = os.path.normpath(os.path.join(base, path.lstrip("/")))
    if os.path.isabs(resolved) or resolved == "." or resolved == ".." or resolved.startswith("../"):
        return None
    if trim:
        resolved = resolved.rstrip(".")
    return resolved if resolved and contained(tree, resolved) else None

def script_reads(tree, script):
    found = set()
    if not contained(tree, script):
        return found # a symlink that leads out of the tree is not followed
    text = open(os.path.join(tree, script)).read()
    # $root is the repository, and $here the directory the script sets it to: its own
    for variable, path in re.findall(r"(\$\{?root\}?|\$\{?here\}?)/+(\.?[A-Za-z0-9_][A-Za-z0-9_./-]*)", text):
        # (a full stop that ends a sentence is no part of the name)
        found.add(inside(tree, os.path.dirname(script) if "here" in variable else "", path, trim=True))
    # a helper called through the script's own directory (dirname of $0), quoted or not
    for path in re.findall(r"\$\(dirname \"?\$0\"?\)/+([A-Za-z0-9_./-]+)", text):
        found.add(inside(tree, os.path.dirname(script), path))
    return found - {None}

def reads_of(tree, scripts):
    """What the scripts read, and what the scripts among those read in turn, to the end. Returns the
    paths with the first script that names each; a script seen once is not read again (a cycle)."""
    reads = {}
    queue = list(scripts)
    seen = set(queue)
    while queue:
        script = queue.pop(0)
        for path in sorted(script_reads(tree, script)):
            reads.setdefault(path, script)
            helper = path.startswith("scripts/") and path.endswith((".sh", ".py"))
            if helper and os.path.isfile(os.path.join(tree, path)) and path not in seen:
                seen.add(path)
                queue.append(path)
    return reads

def survey(tree):
    """The paths that must be owned in a tree: the list kept here, the reactor's POMs, and what is
    derived from the workflows. Returns them with the reactor, the derived scripts and the reads."""
    owned = [
        ".github/workflows/release.yml", ".github/workflows/next.yml", ".github/CODEOWNERS",
        "RELEASING.md", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties",
        "pom.xml", "scripts/release-check.sh", "scripts/release-codeowners-test.sh",
        "scripts/fetch-sdk.sh", "scripts/next-workflow.json", "release-notes/x.md",
    ]
    reactor = modules(tree, ".")
    if not reactor:
        sys.exit("FAIL the root pom.xml names no modules, so the POMs of the reactor were not checked")
    owned += [os.path.join(module, "pom.xml") for module in reactor]
    derived = workflow_scripts(tree)
    for script in derived:
        if not os.path.isfile(os.path.join(tree, script)):
            sys.exit("FAIL %s, run by %s, is not a file" % (script, derived[script]))
    reads = reads_of(tree, derived)
    # the derivation must have found what it is for, or an empty one would pass
    expected_derived = ["scripts/fetch-sdk.sh", "scripts/fetch-sdk-test.sh", "scripts/release-check.sh", "scripts/schema-drift.sh"]
    expected_reads = ["system-tests/src/test/resources/sdk-jar-checksums.properties", "pom.xml", "mvnw"]
    if [p for p in expected_derived if p not in derived] or [p for p in expected_reads if p not in reads]:
        sys.exit("FAIL the workflows' scripts were not derived: %s; their reads: %s" % (sorted(derived), sorted(reads)))
    for path in reads:
        if not os.path.exists(os.path.join(tree, path)):
            sys.exit("FAIL %s, read by %s, does not exist: the derivation read a path wrong" % (path, reads[path]))
    owned += [p for p in derived if p not in owned] + [p for p in reads if p not in owned]
    return owned, reactor, derived, reads

# what the release only compiles belongs to nobody in particular: owning everything would make
# the release managers review every change
unowned = ["odds-feed/src/main/java/X.java", "README.md"]

def problems(lines, owned):
    found = []
    for path in owned:
        got = owners(lines, path)
        if got != TEAM:
            found.append("%s is owned by %s, not by %s" % (path, " ".join(got) or "nobody", " ".join(TEAM)))
    for path in unowned:
        got = owners(lines, path)
        if got:
            found.append("%s must stay unowned, but %s own it" % (path, " ".join(got)))
    return found

# The whole of scripts/ is owned too, as a backstop for what the derivation does not see (it is
# lexical: only the forms named above). It is left out of the lines the derivation is judged by,
# or it would own every script it finds, and the test could never see a pattern that misses one.
BACKSTOP = "/scripts/"
full = open(os.path.join(root, ".github/CODEOWNERS")).read().splitlines()
lines = [l for l in full if l.split("#", 1)[0].split()[:1] != [BACKSTOP]]

def backstop_problems(all_lines):
    """Everything under scripts/ must be owned by the team under the whole file, the last matching
    line winning: a script of any depth that no other line names, every file there is, and every
    path under scripts/ that a line of the file names itself, so that a later line giving one of
    them to another owner is found."""
    found = []
    here = os.path.join(root, "scripts")
    present = [os.path.relpath(os.path.join(directory, name), root)
               for directory, _, names in os.walk(here) for name in names]
    named = [text[0][1:] for text in (line.split("#", 1)[0].split() for line in all_lines)
             if text and text[0].startswith("/scripts/") and not text[0].endswith("/") and "*" not in text[0]]
    for path in sorted({"scripts/brand-new.sh", "scripts/brand-new/deeper/tool.py", *present, *named}):
        got = owners(all_lines, path)
        if got != TEAM:
            found.append("%s is owned by %s, not by %s" % (path, " ".join(got) or "nobody", " ".join(TEAM)))
    return found

owned, reactor, derived, reads = survey(root)
failed = problems(lines, owned) + backstop_problems(full)
for problem in failed:
    print("FAIL " + problem, file=sys.stderr)
if not failed:
    print("ok   %d paths owned by %s, %d unowned; %d modules of the reactor: %s; %d scripts run by workflows, "
          "%d files they read" % (len(owned), TEAM[0], len(unowned), len(reactor), ", ".join(reactor),
                                  len(derived), len(reads)))

# the check must see what it is for: a line removed, a line another owner overrides, a line
# that matches nothing, and an owner it does not know all have to be found
cases = {
    "a removed line": [l for l in lines if not l.startswith("/mvnw ")],
    "the POM line removed": [l for l in lines if not l.startswith("**/pom.xml")],
    "a later line for another owner": lines + ["/RELEASING.md @someone-else"],
    "the fetch-sdk line removed": [l for l in lines if not l.startswith("/scripts/fetch-sdk")],
    "the checksums line removed": [l for l in lines if not l.startswith("/system-tests/src/test/resources/sdk-jar")],
    "a line that no longer matches": [l.replace("/.mvn/ ", "/.mvm/ ") for l in lines],
    "everything owned": lines + ["* @oddin-gg/ragnar-admin"],
}
for what, changed in {
    "the backstop removed": [l for l in full if l.split("#", 1)[0].split()[:1] != [BACKSTOP]],
    "the backstop given to another owner": [l.replace(BACKSTOP + " ", BACKSTOP + "x ").replace(BACKSTOP + "x", BACKSTOP + " @someone-else #") for l in full],
    "a later line giving a script under scripts/ to another owner": full + ["/scripts/gh-other.sh @someone-else"],
    "a later line giving a script that exists to another owner": full + ["/scripts/refresh-schema.sh @someone-else"],
    "the backstop that no longer matches": [l.replace(BACKSTOP + " ", "/script/ ") for l in full],
}.items():
    if changed == full:
        failed.append("the case %r changed nothing" % what)
        print("FAIL the case %r changed nothing" % what, file=sys.stderr)
    elif not backstop_problems(changed):
        failed.append(what)
        print("FAIL the test accepted CODEOWNERS with %s" % what, file=sys.stderr)
    else:
        print("ok   refused CODEOWNERS with %s" % what)
for what, changed in cases.items():
    if changed == lines:
        failed.append("the case %r changed nothing" % what)
        print("FAIL the case %r changed nothing" % what, file=sys.stderr)
    elif not problems(changed, owned):
        failed.append(what)
        print("FAIL the test accepted CODEOWNERS with %s" % what, file=sys.stderr)
    else:
        print("ok   refused CODEOWNERS with %s" % what)

# and what the derivation is for: a workflow that runs a script nobody owns yet, and a script that
# reads a file nobody owns yet, in a copy of the tree with the real CODEOWNERS, must each be found,
# and the path named
def copy_of_tree(into):
    shutil.copytree(os.path.join(root, ".github"), os.path.join(into, ".github"))
    shutil.copytree(os.path.join(root, "scripts"), os.path.join(into, "scripts"))
    for path in ["mvnw"] + [os.path.join(m, "pom.xml") for m in ["."] + reactor] + sorted(reads):
        if os.path.isfile(os.path.join(root, path)) and not os.path.exists(os.path.join(into, path)):
            os.makedirs(os.path.dirname(os.path.join(into, path)) or into, exist_ok=True)
            shutil.copy(os.path.join(root, path), os.path.join(into, path))

def append(tree, path, text):
    with open(os.path.join(tree, path), "a") as handle:
        handle.write(text)

def new_script(tree):
    # a step in a workflow that calls a script no pattern of CODEOWNERS names
    append(tree, ".github/workflows/next.yml", "\n      - name: Brand new\n        run: ./scripts/brand-new.sh\n")
    append(tree, "scripts/brand-new.sh", "#!/usr/bin/env bash\n")

def new_read(tree):
    # a script a workflow runs, now reading a file no pattern names
    os.makedirs(os.path.join(tree, "odds-feed/src/main/resources"), exist_ok=True)
    append(tree, "odds-feed/src/main/resources/brand-new.properties", "x=1\n")
    # (the variable is written apart: this script is run by a workflow too, and is read the same way)
    append(tree, "scripts/fetch-sdk.sh", '\n: "$' + 'root/odds-feed/src/main/resources/brand-new.properties"\n')

def new_here_read(tree):
    # the same through $here, which schema-drift.sh sets to its own directory: scripts/, not the root
    append(tree, "scripts/brand-new-helper.py", "print()\n")
    append(tree, "scripts/schema-drift.sh", '\n: "$' + 'here/brand-new-helper.py"\n')

def new_dirname_helper(tree):
    # a release script calling a helper by its own directory, which no release-* pattern names
    append(tree, "scripts/gh-helpers.sh", "#!/usr/bin/env bash\n")
    append(tree, "scripts/release-check.sh", '\n"$(dirname "$' + '0")/gh-helpers.sh"\n')

def new_helper_chain(tree):
    # that helper calling another one, and a third calling the first again: followed to the end,
    # and the cycle left
    new_dirname_helper(tree)
    append(tree, "scripts/gh-helpers.sh", '\n$(dirname "$' + '0")/gh-more.sh\n')
    append(tree, "scripts/gh-more.sh", '\n"$' + 'here/gh-helpers.sh" "$' + 'root/scripts/gh-last.sh"\n')
    append(tree, "scripts/gh-last.sh", "#!/usr/bin/env bash\n")

def new_bare_helper(tree):
    # the same with the substitution not quoted, and with the argument of dirname not quoted
    append(tree, "scripts/gh-plain.sh", "#!/usr/bin/env bash\n")
    append(tree, "scripts/gh-bare.sh", "#!/usr/bin/env bash\n")
    append(tree, "scripts/release-check.sh", '\n: $(dirname "$' + '0")/gh-plain.sh $(dirname $' + '0)/gh-bare.sh\n')

def new_double_slash(tree):
    # the shell reads a//b as a/b: a helper called through a doubled slash, in each of the three forms
    for name in ["gh-slash-dirname.sh", "gh-slash-root.sh", "gh-slash-here.sh"]:
        append(tree, "scripts/" + name, "#!/usr/bin/env bash\n")
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")//gh-slash-dirname.sh"\n')
    append(tree, "scripts/release-check.sh", ': "$' + 'root//scripts/gh-slash-root.sh"\n')
    append(tree, "scripts/schema-drift.sh", '\n: "$' + 'here//gh-slash-here.sh"\n')

def new_nested_helper(tree):
    # a helper in a directory of its own sets here to that directory, and reaches the repository
    # root from it: the file there is owned by nobody
    os.makedirs(os.path.join(tree, "scripts/gh-tools"))
    append(tree, "gh-evil.sh", "#!/usr/bin/env bash\n")
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/gh-tools/check.sh"\n')
    append(tree, "scripts/gh-tools/check.sh", 'here=$(cd "$(dirname "$' + '0")" && pwd)\n: "$' + 'here/x/../../../gh-evil.sh"\n')

def new_python_helper(tree):
    # a Python helper, called through the script's own directory, naming a script in turn
    append(tree, "scripts/gh-from-py.sh", "#!/usr/bin/env bash\n")
    append(tree, "scripts/gh-x.py", '# runs "$' + 'root/scripts/gh-from-py.sh"\n')
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/gh-x.py"\n')

def nested_module(tree):
    # a module listed in a child POM's <modules>, not in the root POM's, as a nested reactor has it
    pom = os.path.join(tree, "test-fakes/pom.xml")
    text = open(pom).read()
    assert "</project>" in text
    open(pom, "w").write(text.replace("</project>", "<modules><module>nested</module></modules></project>"))
    os.makedirs(os.path.join(tree, "test-fakes/nested"))
    append(tree, "test-fakes/nested/pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>\n")

# what makes the change, the path that must be named, and the lines of CODEOWNERS it is judged by
cases = [
    ("a workflow running a new script", new_script, "scripts/brand-new.sh", lines),
    ("a workflow script reading a new file", new_read, "odds-feed/src/main/resources/brand-new.properties", lines),
    ("a workflow script reading a new file through $here", new_here_read, "scripts/brand-new-helper.py", lines),
    ("a release script calling a new helper through its own directory", new_dirname_helper,
     "scripts/gh-helpers.sh", lines),
    ("a helper calling another, and that one the first again", new_helper_chain, "scripts/gh-more.sh", lines),
    ("the end of a chain of helpers", new_helper_chain, "scripts/gh-last.sh", lines),
    ("a release script calling a helper through an unquoted dirname", new_bare_helper, "scripts/gh-plain.sh", lines),
    ("a release script calling a helper through dirname of an unquoted $0", new_bare_helper, "scripts/gh-bare.sh", lines),
    ("a helper called through a doubled slash after dirname", new_double_slash, "scripts/gh-slash-dirname.sh", lines),
    ("a script named through a doubled slash after $root", new_double_slash, "scripts/gh-slash-root.sh", lines),
    ("a script named through a doubled slash after $here", new_double_slash, "scripts/gh-slash-here.sh", lines),
    ("a nested helper whose $here reaches an unowned file in the repository root", new_nested_helper, "gh-evil.sh", lines),
    ("a Python helper called through dirname", new_python_helper, "scripts/gh-x.py", lines),
    ("a script a Python helper names", new_python_helper, "scripts/gh-from-py.sh", lines),
    ("a nested module whose POM CODEOWNERS gives to another owner", nested_module, "test-fakes/nested/pom.xml",
     lines + ["/test-fakes/nested/pom.xml @someone-else"]),
]
for what, change, path, judged_by in cases:
    with tempfile.TemporaryDirectory() as tree:
        copy_of_tree(tree)
        change(tree)
        found = problems(judged_by, survey(tree)[0])
        if not any(line.startswith(path + " is owned by ") for line in found):
            failed.append(what)
            print("FAIL the test did not name %s for %s: %s" % (path, what, found), file=sys.stderr)
        else:
            print("ok   refused %s: %s" % (what, [line for line in found if line.startswith(path)][0]))

# a path that is no file of the repository must be dropped, not derived: it would be joined to the
# tree, and to its copy, and read or written there. Each is written into a script a workflow runs,
# for a file that exists outside the tree, and nothing derived may lead out of it
# Each change returns the paths that must be derived and those that must not.
def absolute_looking_by_dirname(tree, outside):
    # a leading slash after dirname does not start from the filesystem root: this is scripts/ + the path
    inner = os.path.join("scripts", outside.lstrip("/"))
    os.makedirs(os.path.join(tree, os.path.dirname(inner)))
    append(tree, inner, "x\n")
    append(tree, "scripts/release-check.sh", '\n"$(dirname "$' + '0")/' + outside + '"\n')
    return [inner], []

def traversal_by_here(tree, outside):
    os.makedirs(os.path.join(tree, "scripts/x"))
    away = os.path.relpath(outside, os.path.join(tree, "scripts"))
    append(tree, "scripts/schema-drift.sh", '\n: "$' + 'here/x/../' + away + '"\n')
    return [], []

def traversal_by_root(tree, outside):
    append(tree, "scripts/fetch-sdk.sh", '\n: "$' + 'root/scripts/../' + os.path.relpath(outside, tree) + '"\n')
    return [], []

def traversal_by_dirname(tree, outside):
    os.makedirs(os.path.join(tree, "scripts/x"))
    away = os.path.relpath(outside, os.path.join(tree, "scripts"))
    append(tree, "scripts/release-check.sh", '\n"$(dirname "$' + '0")/x/../' + away + '"\n')
    return [], []

def symlink_out_of_the_tree(tree, outside):
    # a symlink in scripts/ to a file elsewhere, which names a script of the tree: the link is not
    # followed, so neither it nor what it names is derived
    append(tree, "scripts/gh-behind-link.sh", "#!/usr/bin/env bash\n")
    open(outside, "w").write(': "$' + 'root/scripts/gh-behind-link.sh"\n')
    os.symlink(outside, os.path.join(tree, "scripts/gh-link.sh"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/gh-link.sh"\n')
    return [], ["scripts/gh-link.sh", "scripts/gh-behind-link.sh"]

def symlinked_directory(tree, outside):
    # a directory of scripts/ that is a symlink to one elsewhere, holding a helper that names a
    # script of the tree: nothing beneath the link is followed, and what it names is not derived
    append(tree, "scripts/gh-behind-dir.sh", "#!/usr/bin/env bash\n")
    open(outside, "w").write(': "$' + 'root/scripts/gh-behind-dir.sh"\n')
    os.symlink(os.path.dirname(outside), os.path.join(tree, "scripts/gh-linkdir"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/gh-linkdir/outside.txt"\n')
    return [], ["scripts/gh-linkdir/outside.txt", "scripts/gh-behind-dir.sh"]

for what, change in [
    ("an absolute-looking path through dirname", absolute_looking_by_dirname),
    ("a .. traversal through $here", traversal_by_here),
    ("a .. traversal through $root", traversal_by_root),
    ("a .. traversal through dirname", traversal_by_dirname),
    ("a symlink out of the tree", symlink_out_of_the_tree),
    ("a symlinked directory out of the tree", symlinked_directory),
]:
    with tempfile.TemporaryDirectory() as tree, tempfile.TemporaryDirectory() as elsewhere:
        copy_of_tree(tree)
        outside = os.path.join(elsewhere, "outside.txt")
        append(elsewhere, "outside.txt", "x\n")
        wanted, unwanted = change(tree, outside)
        got = survey(tree)[3]
        inner = os.path.realpath(tree) + os.sep
        leaving = [p for p in got if os.path.isabs(p) or not (os.path.realpath(os.path.join(tree, p)) + os.sep).startswith(inner)]
        wrong = leaving + [p for p in unwanted if p in got] + [p for p in wanted if p not in got]
        if wrong:
            failed.append(what)
            print("FAIL the test derived %s, or missed what it should, for %s" % (wrong, what), file=sys.stderr)
        else:
            print("ok   %s: derived inside the repository only" % what)
sys.exit(1 if failed else 0)
PY
