"""Globalus (NE per-IP) Claude kvietimų dienos limitas /api/v1/goals/parse (ir vėliau /explain)
maršrutams - kainos apsauga. Sąmoningai paprastesnis už limiter.py: PLAN.md K2 sprendimas - šiems
maršrutams nereikia per-IP dalies, forma turi likti naudojama be AI net ir limitą pasiekus."""
import threading
from datetime import date
from enum import Enum
from typing import Callable


class Decision(Enum):
    ALLOWED = "allowed"
    DAILY_LIMIT = "daily_limit"


class GoalsAiLimiter:
    """Skaitiklis + data; resetinasi, kai pasikeičia diena. `clock` - testams, kad nebūtų datetime.now()."""

    def __init__(self, daily_limit: int, clock: Callable[[], date] = date.today):
        self.daily_limit = daily_limit
        self._clock = clock
        self._day: date | None = None
        self._count = 0
        self._lock = threading.Lock()

    def try_acquire(self) -> Decision:
        with self._lock:
            today = self._clock()
            if today != self._day:
                self._day, self._count = today, 0
            if self._count >= self.daily_limit:
                return Decision.DAILY_LIMIT
            self._count += 1
            return Decision.ALLOWED
