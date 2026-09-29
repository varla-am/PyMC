"""pymc - write commands and event handlers for a Paper server in Python 3.

    from pymc import mcmod, mccommand

    mcmod.triggerCommand("/hello")
    mccommand.broadcast(f"{mcmod.sender} says hi!")

    # or, in another script:
    e = mcmod.event("join")
    mccommand.broadcast(f"&e{e.player} joined")
"""
from . import mcmod, mccommand

__all__ = ["mcmod", "mccommand"]
