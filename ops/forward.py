# A forwarder for the proof and the harness check: it passes each HTTP request on 127.0.0.1:<listen>
# to serve on 127.0.0.1:<target>, and logs one JSON line a request to <log>: its verb, path,
# JSON-RPC method, whose credential its Authorization header bore (by the digest <want>, the
# sessions' credential's: "the sessions'", "another" or "none"; never its value) and serve's status.
#
#   python3 -I ops/forward.py <listen> <target> <log> <want> [<sink> <sink-log>]
#
# With <sink>, it also answers on 127.0.0.1:<sink> as the place where every request bound off
# the machine is sent (a model's base URL and the HTTP and HTTPS proxy): it logs each one's verb
# and target to <sink-log> and refuses it, so nothing sent there leaves the machine.
import hashlib, http.client, http.server, json, sys, threading

listen_port, target_port, log_path, want = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
lock = threading.Lock()


def record(path, line):
    with lock, open(path, "a") as f:
        f.write(json.dumps(line) + "\n")


class Forward(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def handle_one(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        auth = self.headers.get_all("Authorization") or []
        bearer = auth[0][7:] if len(auth) == 1 and auth[0].startswith("Bearer ") else None
        credential = ("none" if not auth else "the sessions'" if bearer is not None
                      and hashlib.sha256(bearer.encode()).hexdigest() == want else "another")
        c = http.client.HTTPConnection("127.0.0.1", target_port, timeout=60)
        sent = {k: v for k, v in self.headers.items() if k.lower() != "connection"}
        c.request(self.command, self.path, body=raw or None, headers=sent)
        r = c.getresponse()
        answer = r.read()
        try:
            method = json.loads(raw).get("method", "-") if raw else "-"
        except (ValueError, AttributeError):
            method = "?"
        record(log_path, {"method": self.command, "path": self.path, "rpc": method,
                          "credential": credential, "status": r.status})
        self.send_response(r.status)
        for k, v in r.getheaders():
            if k.lower() not in ("content-length", "connection", "transfer-encoding"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(answer)))
        self.end_headers()
        self.wfile.write(answer)

    do_GET = do_POST = do_DELETE = do_PUT = handle_one

    def log_message(self, *a):
        pass


class Sink(http.server.BaseHTTPRequestHandler):
    def refuse(self):
        record(sink_log, {"method": self.command, "target": self.path,
                          "host": self.headers.get("Host")})
        self.send_response(403)
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()

    do_GET = do_POST = do_DELETE = do_PUT = do_PATCH = do_HEAD = do_CONNECT = refuse

    def log_message(self, *a):
        pass


if len(sys.argv) == 7:
    sink_log = sys.argv[6]
    sink = http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[5])), Sink)
    threading.Thread(target=sink.serve_forever, daemon=True).start()
http.server.ThreadingHTTPServer(("127.0.0.1", listen_port), Forward).serve_forever()
