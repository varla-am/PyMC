# Example PyMC script: /hello [amount]
from pymc import mcmod, mccommand

mcmod.triggerCommand("/hello")

mccommand.broadcast(f"&9{mcmod.sender}: IM BLUE DABUDI DABUDAI")

if mcmod.args and mcmod.args[0].isdigit() and mcmod.sender != "CONSOLE":
    mccommand.execute.console(f"/give {mcmod.sender} diamond {mcmod.args[0]}")
