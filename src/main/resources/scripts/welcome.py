# Example: an event, like Skript's "on join:"
from pymc import mcmod, mccommand

e = mcmod.event("join")

if e.first_join:
    mccommand.broadcast(f"&6Everyone welcome {e.player}, first time here!")
else:
    mcmod.reply(f"&aWelcome back, {e.player}! {mcmod.data.player_count} player(s) online.")
