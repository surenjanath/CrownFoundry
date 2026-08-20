"""Throttles that count per player rather than per IP address.

DRF's throttles identify a caller by IP. For a browser that is close enough to a person; for a
mobile app it is not. Carrier-grade NAT puts thousands of phones on one egress address, so an
IP-keyed budget is a budget shared by everyone on that carrier in that region - and the symptom
is not "an attacker was stopped" but "the app stopped working for a whole network, at random,
for the people playing fastest".

So the identity is taken from the payload where the payload has one:

* ``player_id`` - the install's own UUID, sent with the calls that create or sync data;
* ``match_id`` - failing that, the game being played, which bounds an AI turn to one game;
* the IP address - failing both, which is the old behaviour and the right floor.

A client can forge either id, so this is a fairness mechanism, not a security boundary. The
security boundary is the IP-keyed ceiling that still sits underneath it: forging ids spreads a
caller across buckets but never past :class:`AddressRateThrottle`.
"""

from __future__ import annotations

from rest_framework.throttling import AnonRateThrottle, ScopedRateThrottle

#: Body keys that identify a caller, in the order they are preferred.
IDENTITY_KEYS = ("player_id", "match_id")


def _payload_identity(request) -> str | None:
    """The caller's own id from the request, or ``None`` if it did not send one."""
    for source in (getattr(request, "data", None), getattr(request, "query_params", None)):
        if not isinstance(source, dict):
            continue
        for key in IDENTITY_KEYS:
            value = source.get(key)
            if value:
                # Bounded, because this becomes part of a cache key and the client chose it.
                return f"{key}:{str(value)[:64]}"
    return None


class PlayerIdentityMixin:
    """Prefer the caller's own id over the address it happens to share with strangers."""

    def get_ident(self, request):
        try:
            identity = _payload_identity(request)
        except Exception:
            # A body that will not parse is the view's problem to report, not the throttle's.
            identity = None
        return identity or super().get_ident(request)


class PlayerScopedRateThrottle(PlayerIdentityMixin, ScopedRateThrottle):
    """Per-endpoint budgets, counted per player."""


class AddressRateThrottle(AnonRateThrottle):
    """The ceiling underneath everything, still counted per address.

    Deliberately not player-keyed: this is the bucket that has to hold when the ids are forged.
    Its rate is set well above what one person can produce so that a shared carrier address is
    not throttled in normal use.
    """

    scope = "anon"
