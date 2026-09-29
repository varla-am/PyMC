"""What started this script (a command or an event), the calls that declare it, and server info."""
import difflib
import json
import keyword
import os
import re
import sys
from types import SimpleNamespace

from . import _bridge

command = os.environ.get("PYMC_COMMAND", "")
sender = os.environ.get("PYMC_SENDER", "CONSOLE")
args = json.loads(os.environ.get("PYMC_ARGS", "[]"))

# Keep in sync with EventBridge.EVENTS on the Java side.
EVENTS = (
    "load", "join", "first_join", "quit", "chat", "command", "death", "respawn", "kill", "damage",
    "break", "place", "right_click", "left_click", "drop", "pickup", "consume", "bed_enter",
    "world_change", "level_change", "gamemode_change",
)

# {name} required, [name] optional, a trailing "..." takes the rest of the line
_ARG = re.compile(r"\{(\w+)(\.\.\.)?\}|\[(\w+)(\.\.\.)?\]")
_RESERVED = {"pymc"}


def reply(message):
    """Message only whoever used the command. &-colour codes work: "&cerror"."""
    return _bridge.request("reply", message=str(message))


def triggerCommand(pattern):
    """Declare the command this script answers to, with optional named arguments:

        mcmod.triggerCommand("/gift {player} {item} [amount]")
        mcmod.triggerCommand("/shout {text...}")

    While the server starts, PyMC runs every script once to ask exactly this: the call
    reports the command and stops the script there. When someone uses the command,
    the arguments become variables (player, item, amount) and the rest of the script
    runs. Missing required arguments -> the sender gets the usage line, script stops.
    Use a plain string here, not an f-string.
    """
    name, params = _parse(pattern)
    caller = sys._getframe(1).f_globals
    for p in params:
        if p.name in caller:
            raise ValueError(f"argument {{{p.name}}} would overwrite the script's own "
                             f"'{p.name}' - pick another name")
    usage = "/" + " ".join([name] + [_usage_of(p) for p in params])

    if _bridge.MODE == "discover":
        _bridge.send("register", command=name, usage=usage)
        sys.exit(0)

    values, missing = _bind(params, args)
    if missing:
        if _bridge.MODE == "offline":
            print(f"[pymc offline] missing {', '.join(missing)} -> sender would see: Usage: {usage}")
        else:
            reply(f"&cUsage: {usage}")
        sys.exit(0)
    if _bridge.MODE == "offline":
        print(f"[pymc offline] /{name} run by {sender}: {values}")

    caller.update(values)
    return SimpleNamespace(**values)


def event(*names):
    """Declare the server events this script runs on, like Skript's "on join:":

        e = mcmod.event("join")
        mcmod.reply(f"&aWelcome, {e.player}!")

        e = mcmod.event("break", "place")     # several events, e.name says which one
        e = mcmod.event("on first join")      # Skript spelling works too

    The rest of the script runs every time one of the events happens. The result has the
    event's details as attributes: e.name, e.player (the player's name, None for "load"),
    plus per event e.g. e.message (chat), e.block, e.x, e.y, e.z (break/place), e.killer
    (death). mcmod.sender is the player, so mcmod.reply(...) messages them.

    Events: load, join, first_join, quit, chat, command, death, respawn, kill, damage,
    break, place, right_click, left_click, drop, pickup, consume, bed_enter, world_change,
    level_change, gamemode_change. The script runs just after the event, so it can react
    to it but not cancel it.
    """
    wanted = []
    for n in names:
        name = _event_name(n)
        if name not in wanted:
            wanted.append(name)
    if not wanted:
        raise ValueError("mcmod.event needs at least one event, e.g. mcmod.event(\"join\")")

    if _bridge.MODE == "discover":
        _bridge.send("register", events=wanted)
        sys.exit(0)

    if _bridge.MODE == "offline":
        # Dry run: details can be given in PYMC_EVENT_DATA, any other field reads as None.
        data = {"name": wanted[0], "player": None if sender == "CONSOLE" else sender,
                **json.loads(os.environ.get("PYMC_EVENT_DATA", "{}"))}
        print(f"[pymc offline] on {wanted[0]}: {data}")
        return _OfflineEvent(**data)

    fired = os.environ.get("PYMC_EVENT")
    if not fired:
        raise RuntimeError("this script was started by a command, but it declares events - "
                           "a script is either a command or an event handler")
    return _ns({"name": fired, **json.loads(os.environ.get("PYMC_EVENT_DATA", "{}"))})


def _event_name(name):
    """ "on first join", "First-Join", "first_join" -> "first_join"."""
    text = str(name).strip().lower()
    text = re.sub(r"^on\s+", "", text)
    text = re.sub(r"[\s\-]+", "_", text).rstrip(":")
    if text not in EVENTS:
        close = difflib.get_close_matches(text, EVENTS, n=2)
        hint = f"did you mean {' or '.join(close)}? " if close else ""
        raise ValueError(f"unknown event {name!r} - {hint}Known events: {', '.join(EVENTS)}")
    return text


class _OfflineEvent(SimpleNamespace):
    def __getattr__(self, name):
        return None


def _parse(pattern):
    tokens = str(pattern).strip().split()
    if not tokens:
        raise ValueError("triggerCommand needs a command, e.g. \"/hello\"")
    name = tokens[0].removeprefix("/").lower()
    if not re.fullmatch(r"[a-z0-9_\-]+", name):
        raise ValueError(f"not a valid command name: {tokens[0]!r}")
    if name in _RESERVED:
        raise ValueError(f"/{name} belongs to the PyMC plugin itself")

    params = []
    for i, token in enumerate(tokens[1:]):
        m = _ARG.fullmatch(token)
        if not m:
            raise ValueError(f"{token!r}: write arguments as {{name}} or [name]")
        param = SimpleNamespace(name=m.group(1) or m.group(3), required=m.group(1) is not None,
                                rest=bool(m.group(2) or m.group(4)))
        if not param.name.isidentifier() or keyword.iskeyword(param.name):
            raise ValueError(f"{token!r}: {param.name!r} can't be a Python variable name")
        if any(p.name == param.name for p in params):
            raise ValueError(f"{token!r}: argument {param.name!r} appears twice")
        if param.rest and i != len(tokens) - 2:
            raise ValueError(f"{token!r}: only the last argument can take the rest of the line")
        if param.required and params and not params[-1].required:
            raise ValueError(f"{token!r}: required arguments can't come after optional ones")
        params.append(param)
    return name, params


def _bind(params, given):
    values, missing = {}, []
    for i, p in enumerate(params):
        if p.rest:
            value = " ".join(given[i:]) or None
        else:
            value = given[i] if i < len(given) else None
        if value is None and p.required:
            missing.append(p.name)
        values[p.name] = value
    return values, missing


def _usage_of(p):
    label = p.name + ("..." if p.rest else "")
    return f"<{label}>" if p.required else f"[{label}]"


def _ns(value):
    """JSON objects -> objects with attributes: player.name instead of player["name"]."""
    if isinstance(value, dict):
        return SimpleNamespace(**{k: _ns(v) for k, v in value.items()})
    if isinstance(value, list):
        return [_ns(v) for v in value]
    return value


class _Data:
    """Live server information, like Skript's expressions. Every read asks the server
    right now, so values are always current:

        mcmod.data.player_count            # 3
        for p in mcmod.data.players:       # p.name, p.health, p.world, p.x ...
        mcmod.data.version                 # "1.21.11"
        mcmod.data.modloader               # "Paper"
    """

    def _get(self, field, **extra):
        return _ns(_bridge.request("data", field=field, **extra))

    @property
    def players(self):
        """Online players: name, uuid, display_name, world, x, y, z, health, food,
        level, gamemode, ping, op."""
        return self._get("players") or []

    def player(self, name):
        """One online player by exact name, or None if they aren't online."""
        return self._get("player", name=str(name))

    @property
    def player_count(self):
        return self._get("player_count")

    @property
    def max_players(self):
        return self._get("max_players")

    @property
    def version(self):
        """Minecraft version, e.g. "1.21.11"."""
        return self._get("version")

    @property
    def modloader(self):
        """Server software: "Paper", "Purpur", "Folia"..."""
        return self._get("modloader")

    @property
    def server_version(self):
        """Full server build string."""
        return self._get("server_version")

    @property
    def plugins(self):
        """Installed plugins: name, version, enabled."""
        return self._get("plugins") or []

    @property
    def worlds(self):
        return self._get("worlds") or []

    @property
    def motd(self):
        return self._get("motd")

    @property
    def tps(self):
        """Ticks per second over the last 1, 5 and 15 minutes (20 is perfect)."""
        return self._get("tps")

    @property
    def online_mode(self):
        """True if the server checks accounts with Mojang."""
        return self._get("online_mode")


data = _Data()
