# Example: named arguments. /anobc <arg1> <arg2> [note...]
from pymc import mcmod, mccommand

mcmod.triggerCommand("/anobc {arg1} {arg2} [note...]")

mcmod.reply(f"&aarg1 = {arg1}, arg2 = {arg2}")
if note:
    mccommand.broadcast(f"&e{mcmod.sender} says: {note}")
