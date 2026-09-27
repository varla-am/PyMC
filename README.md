# PyMC

Commands for a Paper server written in Python 3 - like Skript, but in real Python.
Runs on any host that runs Paper: with the machine's `python3` if it has one, otherwise
with GraalPy inside the server's own JVM.

```python
# plugins/PyMC/scripts/gift.py
from pymc import mcmod, mccommand

mcmod.triggerCommand("/gift {player} {item} [amount]")

mccommand.execute.console(f"/give {player} {item} {amount or 1}")
mccommand.broadcast(f"&6{mcmod.sender} gave {player} some {item}")
```

## Install

1. Drop `PyMC-2.0.0.jar` into `plugins/` of a **Paper 1.21+** server (Java 21+) and start it.
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

`mcmod.triggerCommand("/name ...")` declares the command. While the server starts, PyMC runs
every script once to ask this; the call reports the command and stops the script there.
When someone uses the command, the rest of the script runs.

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

### API

| Call | Does |
|---|---|
| `mcmod.triggerCommand("/name {arg} [opt]")` | declares the command and its arguments |
| `mcmod.sender`, `mcmod.args`, `mcmod.command` | who ran it, raw arguments, command name |
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
script does before `triggerCommand` is refused at startup - declare the command first.

Run a script straight from a terminal to dry-run it: requests are printed instead of sent.

## Server commands

`/pymc list` shows the engine, scripts and their commands; `/pymc reload` picks up new, edited
or deleted scripts without a restart (op only). Reload asks the scripts off the main thread, so a
slow one doesn't freeze the server; scripts still running at that moment are stopped.

## Security

Anyone who can type a script's command runs that script, and scripts can run console
commands - check `mcmod.sender` before doing anything powerful. Under GraalPy scripts can't reach
Java or the server's internals, only the calls above (they can still read and write files, like
ordinary Python). Under `python3` a script is a normal program on the machine.

## Build

```bash
./gradlew build         # -> build/libs/PyMC-2.0.0.jar
```

Built against Paper API 1.21.11 and GraalPy 25.4 with Java 21. GraalPy is a compile-only
dependency; it is never packed into the jar.
