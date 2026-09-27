"""How a script talks to the PyMC plugin: one JSON object per message.

Two transports, same messages:
    python3 process  requests go out on stdout, replies come back on stdin
    GraalPy          the plugin exposes a Java object; requests are plain calls

On the process transport stdout belongs to the protocol, so print() output is moved to
stderr, which the plugin copies into the server console.

Modes (PYMC_MODE):
    discover  the server asks which command the script answers to
    run       someone used the command, execute the script
    offline   run straight from a terminal: requests are printed, not sent
"""
import json
import os
import sys

MODE = os.environ.get("PYMC_MODE", "offline")

try:
    import polyglot  # only exists under GraalPy

    _host = polyglot.import_value("pymc_host")
except ImportError:
    _host = None

_out = sys.stdout
_in = sys.stdin
sys.stdout = sys.stderr
_next_id = 0


def _line(kind, payload):
    return json.dumps({"type": kind, **payload}, ensure_ascii=False)


def send(kind, **payload):
    """Fire-and-forget message to the plugin."""
    line = _line(kind, payload)
    if _host is not None:
        _host.send(line)
    else:
        _out.write(line + "\n")
        _out.flush()


def request(kind, **payload):
    """Send a request and wait for the plugin's answer."""
    global _next_id
    if MODE == "offline":
        print(f"[pymc offline] {kind}: {payload}")
        return None
    if MODE == "discover":
        # The server is only asking which command this is; nothing may run yet.
        print("pymc: the script does something before mcmod.triggerCommand(...) - "
              "call triggerCommand first")
        sys.exit(2)
    _next_id += 1
    line = _line(kind, {"id": _next_id, **payload})
    if _host is not None:
        answer = str(_host.request(line))
    else:
        _out.write(line + "\n")
        _out.flush()
        answer = _in.readline()
    if not answer:
        raise ConnectionError("PyMC closed the connection")
    reply = json.loads(answer)
    if not reply.get("ok"):
        raise RuntimeError(reply.get("error", "PyMC request failed"))
    return reply.get("result")
