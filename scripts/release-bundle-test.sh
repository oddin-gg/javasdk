#!/usr/bin/env bash
#
# Runs the release build as release.yml does - sources, javadoc, signatures, the Central bundle,
# the upload - with a throwaway signing key and a stand-in for the Central Portal on 127.0.0.1,
# and checks the bundle: the parent POM and odds-feed's jar, sources, javadoc and POM, each
# signed by that key and filed under the version built, and nothing else - no test-fakes, no
# other module. next.yml runs it on every push, so broken signing or bundling shows up at review,
# not once a tag is pushed.
#
# central-publishing-maven-plugin has no dry run: it builds the bundle in the deploy phase and
# uploads it there. So it uploads here too, to the stand-in, through centralBaseUrl. Nothing
# leaves the machine: no real token, no real key, and the stand-in must have seen the upload.
#
# Needs gpg, python3, unzip and JDK 25. Leaves nothing in the local Maven repository.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=0.0.0-bundle-test
work=$(mktemp -d)
# gpg-agent's socket lives in its home, whose path must stay short
export GNUPGHOME
GNUPGHOME=$(mktemp -d /tmp/bundle-gpg.XXXXXX)
server=
cleanup() {
  if [ -n "$server" ]; then
    kill "$server" 2>/dev/null || true
    wait "$server" 2>/dev/null || true
  fi
  gpgconf --kill gpg-agent 2>/dev/null || true
  rm -rf "$work" "$GNUPGHOME"
}
trap cleanup EXIT

# a key for this run only, never exported anywhere but the build below
gpg --batch --pinentry-mode loopback --passphrase bundle-test \
  --quick-gen-key 'Bundle Test <bundle-test@example.invalid>' ed25519 sign 1d > "$work/gpg.log" 2>&1 \
  || { cat "$work/gpg.log"; echo "could not make the test key" >&2; exit 1; }
fingerprint=$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')

# The stand-in portal: it keeps the upload and answers with a deployment id. Asked for the
# deployment's state, it answers as Central would: PUBLISHED for an upload that asked to be
# published (publishingType AUTOMATIC), VALIDATED - waiting for someone to press Publish - for any
# other. Every request is recorded, with the publishing type the upload asked for.
python3 - "$work/port" "$work" <<'EOF' &
import http.server, json, os, sys, urllib.parse
port_file, out = sys.argv[1], sys.argv[2]
state = {'type': None}
class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length') or 0))
        url = urllib.parse.urlparse(self.path)
        path, query = url.path, urllib.parse.parse_qs(url.query)
        with open(os.path.join(out, 'requests'), 'a') as f:
            f.write(path + '\n')
        if path == '/api/v1/publisher/upload':
            state['type'] = (query.get('publishingType') or [''])[0]
            with open(os.path.join(out, 'publishing-type'), 'w') as f:
                f.write(state['type'])
            with open(os.path.join(out, 'upload'), 'wb') as f:
                f.write(body)
            self.send_response(201)
            self.end_headers()
            self.wfile.write(b'bundle-test-deployment')
        elif path == '/api/v1/publisher/status':
            published = 'PUBLISHED' if state['type'] == 'AUTOMATIC' else 'VALIDATED'
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'deploymentId': 'bundle-test-deployment', 'deploymentName': 'test',
                'deploymentState': published, 'purls': [], 'errors': {}, 'warnings': []}).encode())
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, *args):
        pass
server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
with open(port_file + '.part', 'w') as f:
    f.write(str(server.server_port))
os.rename(port_file + '.part', port_file)
server.serve_forever()
EOF
server=$!
for _ in $(seq 100); do
  [ -s "$work/port" ] && break
  sleep 0.1
done
[ -s "$work/port" ] || { echo "the stand-in portal did not start" >&2; exit 1; }
portal=http://127.0.0.1:$(cat "$work/port")

# only the <server> the plugin asks for, with made-up credentials: no other settings, no token
cat > "$work/settings.xml" <<'EOF'
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>bundle-test</username>
      <password>bundle-test</password>
    </server>
  </servers>
</settings>
EOF

cd "$root"
rm -rf target/central-publishing target/central-staging
MAVEN_GPG_KEY=$(gpg --batch --pinentry-mode loopback --passphrase bundle-test --armor --export-secret-keys "$fingerprint" 2>> "$work/gpg.log") \
MAVEN_GPG_PASSPHRASE=bundle-test \
  ./mvnw --batch-mode --no-transfer-progress -s "$work/settings.xml" -Prelease -Drevision="$version" \
    -Dcentral.autoPublish=true -Dcentral.waitUntil=published -DcentralBaseUrl="$portal" \
    -Dmaven.install.skip=true -DskipTests -pl odds-feed -am deploy > "$work/build.log" 2>&1 \
  || { cat "$work/build.log"; echo "the release build failed" >&2; exit 1; }
grep -F "Using Central baseUrl: $portal" "$work/build.log" > /dev/null \
  || { cat "$work/build.log"; echo "the build did not use the stand-in portal" >&2; exit 1; }

bundle=target/central-publishing/central-bundle.zip
python3 - "$bundle" "$work" "$version" <<'EOF'
import hashlib, os, re, subprocess, sys, zipfile
bundle, work, version = sys.argv[1:]
problems = []
def check(ok, message):
    if not ok:
        problems.append(message)

data = open(bundle, 'rb').read()
names = zipfile.ZipFile(bundle).namelist()
base = 'gg/oddin/oddsfeed/'
artifacts = [base + 'odds-feed-parent/%s/odds-feed-parent-%s.pom' % (version, version)] + [
    base + 'odds-feed/%s/odds-feed-%s%s' % (version, version, suffix)
    for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom')]
expected = set(artifacts) | {a + '.asc' for a in artifacts}
files = {n for n in names if not n.endswith('/') and not re.search(r'\.(md5|sha1|sha256|sha512)$', n)}
check(files == expected, 'the bundle holds %s, missing %s' % (sorted(files - expected) or 'nothing else',
                                                             sorted(expected - files) or 'nothing'))

with zipfile.ZipFile(bundle) as z:
    z.extractall(os.path.join(work, 'bundle'))
for artifact in sorted(expected & files - {a + '.asc' for a in artifacts}):
    path = os.path.join(work, 'bundle', artifact)
    verify = subprocess.run(['gpg', '--batch', '--status-fd', '1', '--verify', path + '.asc', path],
                            capture_output=True, text=True)
    check(verify.returncode == 0 and '[GNUPG:] VALIDSIG' in verify.stdout,
          '%s: its signature does not verify against the test key' % artifact)
    for algorithm in ('md5', 'sha1'):
        digest = os.path.join(work, 'bundle', artifact + '.' + algorithm)
        check(os.path.exists(digest) and open(digest).read().strip()
              == hashlib.new(algorithm, open(path, 'rb').read()).hexdigest(),
              '%s: its %s file is missing or wrong' % (artifact, algorithm))

def pom_version(artifact, parent=False):
    text = open(os.path.join(work, 'bundle', artifact)).read()
    text = text.split('<parent>')[1].split('</parent>')[0] if parent else re.sub(r'<parent>.*?</parent>', '', text, flags=re.S)
    m = re.search(r'<version>([^<]+)</version>', text)
    return m and m.group(1)
check(pom_version(artifacts[0]) == version, 'the parent POM does not say version %s' % version)

# The metadata Central requires, on both POMs: odds-feed's own name and description, the rest as
# a consumer of odds-feed sees it - inherited from the parent, where Maven appends the module's
# artifactId to the URLs unless the parent's child.*.inherit.append.path attributes say false.
import xml.etree.ElementTree as ET
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
parent = ET.parse(os.path.join(work, 'bundle', artifacts[0])).getroot()
child = ET.parse(os.path.join(work, 'bundle', artifacts[4])).getroot()

def text(root, path):
    element = root.find(path, NS)
    return (element.text or '').strip() if element is not None else ''

def effective(path, append_attribute=None, holder_path=None):
    if text(child, path):
        return text(child, path)
    inherited = text(parent, path)
    if inherited and append_attribute:
        holder = parent if holder_path is None else parent.find(holder_path, NS)
        if holder is None or holder.get(append_attribute) != 'false':
            inherited = inherited.rstrip('/') + '/odds-feed'
    return inherited

REQUIRED = ['m:name', 'm:description', 'm:url', 'm:licenses/m:license/m:name',
            'm:developers/m:developer/m:name', 'm:scm/m:url', 'm:scm/m:connection']
for path in REQUIRED:
    check(text(parent, path), 'the parent POM has no %s' % path.replace('m:', ''))
for path in ('m:name', 'm:description'):
    check(text(child, path), "odds-feed's POM has no %s of its own" % path.replace('m:', ''))
check(effective('m:licenses/m:license/m:name') == 'BSD-3-Clause',
      "odds-feed's license is %r, not BSD-3-Clause" % effective('m:licenses/m:license/m:name'))
check(effective('m:developers/m:developer/m:name'), "odds-feed's POM has no developer, own or inherited")
REPOSITORY = 'https://github.com/oddin-gg/javasdk'
for path, attribute, holder, expected in (
        ('m:url', 'child.project.url.inherit.append.path', None, REPOSITORY),
        ('m:scm/m:url', 'child.scm.url.inherit.append.path', 'm:scm', REPOSITORY),
        ('m:scm/m:connection', 'child.scm.connection.inherit.append.path', 'm:scm', 'scm:git:' + REPOSITORY + '.git')):
    actual = effective(path, attribute, holder)
    check(actual == expected, "odds-feed's %s is %r, not %r" % (path.replace('m:', ''), actual, expected))
check(pom_version(artifacts[4], parent=True) == version, "odds-feed's POM does not name parent %s" % version)
with zipfile.ZipFile(os.path.join(work, 'bundle', artifacts[1])) as jar:
    properties = jar.read('com/oddin/oddsfeedsdk/internal/sdk.properties').decode()
check('version=%s' % version in properties.splitlines(), 'the jar does not report version %s' % version)

# the stand-in saw one upload, of this bundle, and was asked for its state
requests = open(os.path.join(work, 'requests')).read().split()
check(requests[:1] == ['/api/v1/publisher/upload'] and requests.count('/api/v1/publisher/upload') == 1,
      'the stand-in portal saw %s, not one upload' % requests)
check('/api/v1/publisher/status' in requests, 'nobody asked the stand-in portal for the state')
publishing_type = open(os.path.join(work, 'publishing-type')).read()
check(publishing_type == 'AUTOMATIC',
      'the upload asked to be published %r, not AUTOMATIC: Central would wait for someone to press Publish' % publishing_type)
check(data in open(os.path.join(work, 'upload'), 'rb').read(), 'the upload is not the bundle')

for problem in problems:
    print('FAIL ' + problem, file=sys.stderr)
if problems:
    sys.exit(1)
print('the bundle holds the parent POM and odds-feed %s, all signed, and nothing else' % version)
EOF
