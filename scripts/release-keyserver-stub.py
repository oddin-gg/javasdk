#!/usr/bin/env python3
# Stub keyservers, one site per server name. /<server>/<label>/<what the real server is asked>:
# the directory www/<server>/<label> holds a file "key" (answered with 200), "status" (that
# status), or "stall" (404, after 10 seconds); without any, 404. The request must have the form
# the real server takes - by-fingerprint on keys.openpgp.org, the machine-readable lookup of
# 0x<fingerprint> on keyserver.ubuntu.com - with an upper-case fingerprint, or it gets a 400.
# Every request is recorded.
#
# release-keyserver-stub.py <port file> <www directory> <request log>; used by scripts/release-key-check-test.sh
# and scripts/release-signing-key-check-test.sh.
import http.server, os, re, sys, time
port_file, www, log = sys.argv[1:]
SHAPES = {
    'openpgp': re.compile(r'^vks/v1/by-fingerprint/[0-9A-F]{40}$'),
    'ubuntu': re.compile(r'^pks/lookup\?op=get&options=mr&search=0x[0-9A-F]{40}$'),
}
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        site, label, rest = self.path[1:].split('/', 2)
        with open(log, 'a') as f:
            f.write('%s %s %s\n' % (site, label, rest))
        body = b''
        if not SHAPES[site].match(rest):
            status = 400
        else:
            folder = os.path.join(www, site, label)
            if os.path.exists(os.path.join(folder, 'key')):
                status = 200
                body = open(os.path.join(folder, 'key'), 'rb').read()
            elif os.path.exists(os.path.join(folder, 'status')):
                status = int(open(os.path.join(folder, 'status')).read())
            elif os.path.exists(os.path.join(folder, 'stall')):
                time.sleep(10)
                status = 404
            else:
                status = 404
        self.send_response(status)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args):
        pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
with open(port_file + '.part', 'w') as f:
    f.write(str(server.server_port))
os.rename(port_file + '.part', port_file)
server.serve_forever()
