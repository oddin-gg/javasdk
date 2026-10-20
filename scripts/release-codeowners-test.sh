#!/usr/bin/env bash
#
# Checks that .github/CODEOWNERS makes the release managers' team the owner of everything the
# release build runs with the signing key and the Central token. GitHub reports a file it cannot
# read (next.yml asks it), but not an ownership line that was removed or no longer matches: the
# file stays valid and the path is simply owned by nobody. So the owner of each path is resolved
# here, the way GitHub does, and must be the team: a list of paths kept here, and the scripts the
# workflows run and the files those scripts read, derived, so that none added later is missed.
# The whole repository is owned by the team too (the first rule, *, and scripts/ again after it):
# those two are backstops, kept out of what the derivation is judged by and checked apart.
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

def resolve(tree, path):
    """Where a path leads, the way the file system takes it: component by component, a symlink
    replaced by what it holds where it stands, so that a .. after a link is a step up from the
    link's target and not from the link. Returns the real path from the repository root, or None
    when it is the repository itself or leaves it (by .., or by a link to elsewhere), with every
    symlink passed on the way, as paths from the repository root. A component that does not exist
    (yet) is taken as it is. A doubled slash is none of these: the shell reads a//b as a/b."""
    real = os.path.realpath(tree)
    current, links, steps = [], set(), 0
    pending = path.split("/")[::-1]
    while pending:
        part = pending.pop()
        if part in ("", "."):
            continue
        if part == "..":
            if not current:
                return None, links
            current.pop()
            continue
        at = os.path.join(real, *current, part)
        if not os.path.islink(at):
            current.append(part)
            continue
        steps += 1
        if steps > 40:
            return None, links # a loop of links, or as good as one
        links.add("/".join(current + [part]))
        target = os.readlink(at)
        if os.path.isabs(target):
            if not (target + os.sep).startswith(real + os.sep):
                return None, links
            current = []
            target = os.path.relpath(target, real) if target != real else ""
        pending.extend(target.split("/")[::-1])
    return "/".join(current) or None, links

def inside(tree, base, path, trim=False):
    """What a script names from a base directory, as paths from the repository root: the file it
    really is, and every symlink passed on the way to it - none when it is not a file inside the
    repository."""
    if trim:
        # a full stop that ends a sentence is no part of the name
        head, _, last = path.rpartition("/")
        path = head + "/" + (last if last in (".", "..") else last.rstrip("."))
    final, links = resolve(tree, os.path.join(base, path.lstrip("/")))
    return links | {final} if final else set()

def chain(tree, path):
    """What a path really is: every symlink on the way to it, and the file it leads to (the path
    itself, when nothing on the way is a link). A link owned by the release managers that leads to a
    file nobody owns runs that file."""
    return inside(tree, "", path)

def script_reads(tree, script):
    found = set()
    if not contained(tree, script):
        return found # a symlink that leads out of the tree is not followed
    text = open(os.path.join(tree, script)).read()
    # $root is the repository, and $here the directory the script sets it to: its own
    for variable, path in re.findall(r"(\$\{?root\}?|\$\{?here\}?)/+(\.?[A-Za-z0-9_][A-Za-z0-9_./-]*)", text):
        found |= inside(tree, os.path.dirname(script) if "here" in variable else "", path, trim=True)
    # a helper called through the script's own directory (dirname of $0), quoted or not
    for path in re.findall(r"\$\(dirname \"?\$0\"?\)/+([A-Za-z0-9_./-]+)", text):
        found |= inside(tree, os.path.dirname(script), path)
    return found

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
    # everything the workflows hold is owned by the pattern for .github/, and a link in it is no
    # exception: what it leads to runs or is read in its place
    for directory, directories, names in os.walk(os.path.join(tree, ".github")):
        for name in names + [d for d in directories if os.path.islink(os.path.join(directory, d))]:
            path = os.path.relpath(os.path.join(directory, name), tree)
            if path not in owned:
                owned.append(path)
    # a path that is, or passes through, a symlink is the file it leads to as well: both must be owned
    for path in list(owned):
        owned += [p for p in sorted(chain(tree, path)) if p not in owned]
    return owned, reactor, derived, reads

# what the release only compiles is named by no line: it is owned by the backstop alone (below),
# and these must stay so, or the specific lines would say nothing about what the release depends on
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

# The whole repository is owned by the team (*), and scripts/ again after it, as backstops for what
# the derivation does not see (it is lexical: only the forms named above). They are left out of the
# lines the derivation is judged by, or they would own everything it finds, and the test could never
# see a pattern that misses one. They are checked apart, below, under the whole file.
BACKSTOPS = ["*", "/scripts/"]
full = open(os.path.join(root, ".github/CODEOWNERS")).read().splitlines()

def pattern_of(line):
    text = line.split("#", 1)[0].split()
    return text[0] if text else None

lines = [l for l in full if pattern_of(l) not in BACKSTOPS]

def backstop_problems(all_lines):
    """Every file of the repository must be owned by the team under the whole file, the last matching
    line winning: a file at the root, one under each directory there is (and a new one under it),
    a script of any depth that no other line names, every file in scripts/, and every path under
    scripts/ that a line of the file names itself, so that a later line giving any of them, by
    a directory or a wildcard too, to another owner is found."""
    found = []
    here = os.path.join(root, "scripts")
    present = [os.path.relpath(os.path.join(directory, name), root)
               for directory, _, names in os.walk(here) for name in names]
    named = [pattern_of(line)[1:] for line in all_lines
             if (pattern_of(line) or "").startswith("/scripts/") and not pattern_of(line).endswith("/") and "*" not in pattern_of(line)]
    top = [entry if os.path.isfile(os.path.join(root, entry)) else entry + "/new-file.txt"
           for entry in sorted(os.listdir(root)) if entry != ".git"]
    for path in sorted({"README.md", "new-file-at-the-root.txt", "odds-feed/src/main/java/X.java", "scripts/gh-new.sh",
                        "scripts/brand-new.sh", "scripts/brand-new/deeper/tool.py", *top, *present, *named}):
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
def without(pattern):
    return [l for l in full if pattern_of(l) != pattern]

def given_away(pattern):
    return [pattern + " @someone-else" if pattern_of(l) == pattern else l for l in full]

def matching_nothing(pattern):
    return ["/no-such-path-at-all/ " + " ".join(TEAM) if pattern_of(l) == pattern else l for l in full]

# (the scripts/ line removed, or matching nothing, changes no owner while * is there: it is a second
# backstop, and what overrides it is what is refused)
for what, changed in {
    "the * line removed": without("*"),
    "the * line given to another owner": given_away("*"),
    "the * line that matches nothing": matching_nothing("*"),
    "a later rule giving a directory to another owner": full + ["/odds-feed/ @someone-else"],
    "a later rule giving a wildcard of scripts/ to another owner": full + ["/scripts/gh-*.sh @someone-else"],
    "a later rule giving a kind of file to another owner": full + ["*.md @someone-else"],
    "a later rule giving a file at the root to another owner": full + ["/README.md @someone-else"],
    "the scripts/ backstop given to another owner": given_away("/scripts/"),
    "a later line giving a script under scripts/ to another owner": full + ["/scripts/gh-other.sh @someone-else"],
    "a later line giving a script that exists to another owner": full + ["/scripts/refresh-schema.sh @someone-else"],
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

def link_to_unowned(tree):
    # a link the release managers own (release-*), to a helper nobody owns, in the repository root
    append(tree, "gh-linked-target.sh", "#!/usr/bin/env bash\n")
    os.symlink("../gh-linked-target.sh", os.path.join(tree, "scripts/release-linked.sh"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/release-linked.sh"\n')

def link_by_absolute_path(tree):
    # the same by a link that holds the absolute path of its target, inside the tree
    append(tree, "gh-abs-target.sh", "#!/usr/bin/env bash\n")
    os.symlink(os.path.join(os.path.realpath(tree), "gh-abs-target.sh"), os.path.join(tree, "scripts/release-abs.sh"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/release-abs.sh"\n')

def link_directory_to_unowned(tree):
    # a directory of release-* that is a link to one nobody owns, with a helper beneath it
    os.makedirs(os.path.join(tree, "gh-dir"))
    append(tree, "gh-dir/tool.sh", "#!/usr/bin/env bash\n")
    os.symlink("../gh-dir", os.path.join(tree, "scripts/release-linkdir"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/release-linkdir/tool.sh"\n')

def link_chain(tree):
    # a link to a link: the one in the middle can be pointed anywhere, so it is owned as well
    append(tree, "gh-chain-end.sh", "#!/usr/bin/env bash\n")
    os.symlink("../gh-chain-end.sh", os.path.join(tree, "scripts/gh-hop.sh"))
    os.symlink("gh-hop.sh", os.path.join(tree, "scripts/release-hop.sh"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/release-hop.sh"\n')

def link_in_workflows(tree):
    # a workflow file that is a link to a file nobody owns
    append(tree, "gh-wf-target.yml", "name: x\n")
    os.symlink("../../gh-wf-target.yml", os.path.join(tree, ".github/workflows/gh-link.yml"))

def link_module_pom(tree):
    # the POM of a module of the reactor that is a link to a file under another name
    pom = os.path.join(tree, "examples/odds-feed-1.0/pom.xml")
    os.rename(pom, os.path.join(tree, "gh-module.xml"))
    os.symlink("../../gh-module.xml", pom)

def link_then_up(tree, variable):
    # scripts/release-link is a link to a directory at the root: a .. after it is a step up from
    # that directory, so the file is the one at the root, not the owned decoy beside the link
    os.makedirs(os.path.join(tree, "gh-dir"))
    append(tree, "release-evil.sh", "#!/usr/bin/env bash\n")
    append(tree, "scripts/release-evil.sh", "#!/usr/bin/env bash\n")
    os.symlink("../gh-dir", os.path.join(tree, "scripts/release-link"))
    return {
        "dirname": ("scripts/release-check.sh", '\n: "$(dirname "$' + '0")/release-link/../release-evil.sh"\n'),
        "here": ("scripts/schema-drift.sh", '\n: "$' + 'here/release-link/../release-evil.sh"\n'),
        "root": ("scripts/fetch-sdk.sh", '\n: "$' + 'root/scripts/release-link/../release-evil.sh"\n'),
    }[variable]

def link_then_up_dirname(tree):
    append(tree, *link_then_up(tree, "dirname"))

def link_then_up_here(tree):
    append(tree, *link_then_up(tree, "here"))

def link_then_up_root(tree):
    append(tree, *link_then_up(tree, "root"))

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
    ("an owned link to a helper nobody owns", link_to_unowned, "gh-linked-target.sh", lines),
    ("an owned link that holds an absolute path to a helper nobody owns", link_by_absolute_path, "gh-abs-target.sh", lines),
    ("an owned link to a directory nobody owns", link_directory_to_unowned, "gh-dir/tool.sh", lines),
    ("a link to a link, the one in the middle", link_chain, "scripts/gh-hop.sh", lines),
    ("a link to a link, the end of it", link_chain, "gh-chain-end.sh", lines),
    ("a workflow file that is a link to a file nobody owns", link_in_workflows, "gh-wf-target.yml", lines),
    ("a POM of the reactor that is a link to a file nobody owns", link_module_pom, "gh-module.xml", lines),
    ("a .. after a link to a directory, through dirname", link_then_up_dirname, "release-evil.sh", lines),
    ("a .. after a link to a directory, through $here", link_then_up_here, "release-evil.sh", lines),
    ("a .. after a link to a directory, through $root", link_then_up_root, "release-evil.sh", lines),
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

# and a link to a file that is owned changes nothing: the check does not refuse what it should accept
with tempfile.TemporaryDirectory() as tree:
    copy_of_tree(tree)
    os.symlink("release-github.sh", os.path.join(tree, "scripts/release-linked-ok.sh"))
    append(tree, "scripts/release-check.sh", '\n: "$(dirname "$' + '0")/release-linked-ok.sh"\n')
    owned_now, _, _, reads_now = survey(tree)
    found = problems(lines, owned_now)
    if "scripts/release-linked-ok.sh" not in reads_now or found:
        failed.append("a link to an owned helper")
        print("FAIL a link to a helper that is owned was refused, or not derived: %s" % found, file=sys.stderr)
    else:
        print("ok   accepted a link to a helper that is owned")

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
