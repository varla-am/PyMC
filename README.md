# PyMC

Commands and events for a Paper server written in Python 3 - like Skript, but in real Python.
Runs on any host that runs Paper: with the machine's `python3` if it has one, otherwise
with GraalPy inside the server's own JVM.

```python
# plugins/PyMC/scripts/gift.py
from pymc import mcmod, mccommand

mcmod.triggerCommand("/gift {player} {item} [amount]")

mccommand.execute.console(f"/give {player} {item} {amount or 1}")
mccommand.broadcast(f"&6{mcmod.sender} gave {player} some {item}")
```

```python
# plugins/PyMC/scripts/welcome.py - like Skript's "on join:"
from pymc import mcmod, mccommand

e = mcmod.event("join")
mcmod.reply(f"&aWelcome, {e.player}!")
```

**Alpha 3.0.0** adds events (`mcmod.event(...)`), `/pymc version` and `/pymc reload <script>`,
and every reload now checks the scripts and tells you exactly which line is wrong.

## Install

1. Drop `PyMC-3.0.0-alpha.jar` into `plugins/` of a **Paper 1.21+** server (Java 21+) and start it.
2. Put scripts into `plugins/PyMC/scripts/`, then `/pymc reload`.

Nothing else is required. `plugins/PyMC/config.yml` picks how scripts run:

| `engine:` | Runs scripts with | Good to know |
|---|---|---|
| `auto` (default) | `python3` if the machine has Python 3.9+, otherwise GraalPy | the right choice almost always |
| `python` | the `python:` interpreter, one process per use | fastest; any library incl. C ones like numpy |
| `graalpy` | GraalPy, Python 3.13 inside the JVM | works on hosts without Python; ~150 MB download on first start, ~1 s per command, no C libraries |

Most Minecraft hosts run servers in containers without Python, and there `auto` ends up on
GraalPy. Paper downloads it from Maven Central into `libraries/` once; later starts reuse it.
The container still needs outbound internet on that first start.

## Scripts

A script is either a command or an event handler. `mcmod.triggerCommand("/name ...")` declares
a command, `mcmod.event("join")` declares events. While the server starts, PyMC runs every
script once to ask this; the call reports what it declared and stops the script there. When
someone uses the command or the event happens, the rest of the script runs.

### Arguments

```python
mcmod.triggerCommand("/gift {player} {item} [amount]")
```

| In the pattern | Means |
|---|---|
| `{name}` | required argument |
| `[name]` | optional, `None` when left out |
| `{name...}` / `[name...]` | the rest of the line as one string, last position only |

After the call the arguments are plain variables (it also returns them: `a = mcmod.triggerCommand(...)`
then `a.player`). If a required one is missing, the sender gets `Usage: /gift <player> <item> [amount]`
and the script stops. Write the pattern as a normal string, not an f-string: with `f"..."` Python
would try to fill `{player}` before PyMC ever sees it. `mcmod.args` still holds the raw word list.

### Events: `mcmod.event(...)`

Like Skript's `on join:` - the rest of the script runs every time the event happens:

```python
e = mcmod.event("break", "place")        # one or several events; e.name says which
if e.block == "diamond_ore":
    mccommand.broadcast(f"&b{e.player} found diamonds at {e.x}, {e.y}, {e.z}!")
```

`e.player` is the player's name and `mcmod.sender` is that player, so `mcmod.reply(...)` messages
them. Skript spellings work too: `mcmod.event("on first join")`.

| Event | Fires when | Extra fields on `e` |
|---|---|---|
| `load` | scripts are loaded (start, `/pymc reload`) | - (no player) |
| `join` | a player joins | `message`, `first_join` |
| `first_join` | a player joins for the first time | `message`, `first_join` |
| `quit` | a player leaves | `message` |
| `chat` | a player chats | `message` |
| `command` | a player uses any command | `command`, `line` |
| `death` | a player dies | `message`, `killer`, `cause` |
| `respawn` | a player respawns | `world`, `x`, `y`, `z` |
| `kill` | a player kills a mob or player | `victim`, `victim_name`, `world`, `x`, `y`, `z` |
| `damage` | a player takes damage | `cause`, `damage`, `attacker` |
| `break` / `place` | a player breaks / places a block | `block`, `world`, `x`, `y`, `z` |
| `right_click` / `left_click` | a player clicks (main hand) | `item`, `block`, `world`, `x`, `y`, `z` |
| `drop` / `pickup` | a player drops / picks up items | `item`, `amount` |
| `consume` | a player eats or drinks | `item` |
| `bed_enter` | a player gets into bed | - |
| `world_change` | a player changes world | `from`, `to` |
| `level_change` | a player's XP level changes | `old_level`, `new_level` |
| `gamemode_change` | a player's game mode changes | `old_gamemode`, `gamemode` |

Blocks, items and mobs are lower-case names: `"stone"`, `"diamond_sword"`, `"zombie"`. A field
that doesn't apply is `None` (`e.killer` when nobody killed the player). The script runs just
after the event, off the main thread, so it can react to the event but not cancel it. Each
event is one script run, so frequent events (`damage`, clicks) count against
`max-running-scripts`; when too many run, further events are skipped with a warning.

### API

| Call | Does |
|---|---|
| `mcmod.triggerCommand("/name {arg} [opt]")` | declares the command and its arguments |
| `e = mcmod.event("join", ...)` | declares the events, returns the event's details |
| `mcmod.sender`, `mcmod.args`, `mcmod.command` | who ran it (the event's player), raw arguments, command name |
| `mcmod.reply("&atext")` | message only to whoever used the command |
| `mccommand.execute.console("/cmd")` | runs a command as the console, returns `True`/`False` |
| `mccommand.execute.player("Nick", "/cmd")` | runs a command as an online player |
| `mccommand.broadcast("&9text")` | message to everyone, `&` colour codes work |

The leading `/` is optional.

### Server info: `mcmod.data`

Like Skript's expressions, every read asks the server right now:

```python
d = mcmod.data
d.player_count, d.max_players        # 3, 20
for p in d.players:                  # name, uuid, display_name, world, x, y, z,
    print(p.name, p.health, p.world) # health, food, level, gamemode, ping, op
d.player("Varla")                    # one player, or None if offline
d.version, d.modloader               # "1.21.11", "Paper"
d.server_version                     # full build string
d.plugins                            # p.name, p.version, p.enabled
d.worlds, d.motd, d.tps, d.online_mode
```

Each read is one round trip to the server's main thread (~1 tick), so keep values in variables
instead of reading the same one in a tight loop.

### Output, errors, limits

`print()` and tracebacks show up in the server console, prefixed with the script's file name.
A script that runs longer than `script-timeout` (30 s) is stopped, and at most
`max-running-scripts` (8) run at once; further uses of a command get "try again". Anything a
script does before `triggerCommand` / `event` is refused at startup - declare the command first.

Run a script straight from a terminal to dry-run it: requests are printed instead of sent.
For an event script, pass the details as JSON: `PYMC_EVENT_DATA='{"block": "stone"}' python3 mine.py`.

## Server commands

All op only:

| Command | Does |
|---|---|
| `/pymc reload` | picks up new, edited or deleted scripts without a restart and checks all of them |
| `/pymc reload <script>` | reloads and checks one script (`hello` or `hello.py`); the others keep running |
| `/pymc list` | the engine, every script's command or events, and scripts that have errors |
| `/pymc version` | PyMC version, engine (Python / GraalPy version), server and Java version |

Both reloads check each script and report problems in chat, not only in the console:

```
PyMC: 1 of 4 script(s) have errors and were skipped:
  gift.py: line 3: SyntaxError: '(' was never closed
```

The check finds syntax errors, bad `triggerCommand` patterns, unknown event names (with a
"did you mean"), scripts that never declare anything or do something before declaring, and
commands two scripts both want. The full traceback is still in the server console. A script
with errors is unloaded until it is fixed and reloaded. Errors in the part of a script that
runs later (after the declaration) only show when it runs.

Reload asks the scripts off the main thread, so a slow one doesn't freeze the server;
`/pymc reload` stops scripts still running at that moment, `/pymc reload <script>` doesn't.

## Security

Anyone who can type a script's command runs that script, and scripts can run console
commands - check `mcmod.sender` before doing anything powerful. Under GraalPy scripts can't reach
Java or the server's internals, only the calls above (they can still read and write files, like
ordinary Python). Under `python3` a script is a normal program on the machine.

## Build

```bash
./gradlew build         # -> build/libs/PyMC-3.0.0-alpha.jar
```

Built against Paper API 1.21.11 and GraalPy 25.4 with Java 21. GraalPy is a compile-only
dependency; it is never packed into the jar.
