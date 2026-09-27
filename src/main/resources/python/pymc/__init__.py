"""pymc - write commands for a Paper server in Python 3.

    from pymc import mcmod, mccommand

    mcmod.triggerCommand("/hello")
    mccommand.broadcast(f"{mcmod.sender} says hi!")
"""
from . import mcmod, mccommand

__all__ = ["mcmod", "mccommand"]
