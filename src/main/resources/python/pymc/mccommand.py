"""Things a script can make the server do."""
from . import _bridge


def _clean(command):
    # One leading slash is optional; WorldEdit-style "//set" keeps its second one.
    return str(command).strip().removeprefix("/")


class _Execute:
    def console(self, command):
        """Run a command as the server console. True if the command ran."""
        return _bridge.request("console", command=_clean(command))

    def player(self, name, command):
        """Run a command as an online player. True if the command ran."""
        return _bridge.request("player", player=str(name), command=_clean(command))


execute = _Execute()


def broadcast(message):
    """Show a message to everyone. &-colour codes work: "&9blue", "&lbold".

    Returns how many players (and the console) received it.
    """
    return _bridge.request("broadcast", message=str(message))
