"""Atmintyje laikomas ribotuvas: slenkantis langas (1 val.) vienam IP + bendras paros limitas kainai apsaugoti."""
import threading
import time
from collections import deque
from datetime import date
from enum import Enum

WINDOW_S = 3600
MAX_TRACKED_IPS = 10_000


class Decision(Enum):
    ALLOWED = "allowed"
    IP_LIMIT = "ip_limit"
    DAILY_CAP = "daily_cap"


class RateLimiter:
    def __init__(self, per_hour: int, daily_cap: int, clock=time.time):
        self.per_hour = per_hour
        self.daily_cap = daily_cap
        self._clock = clock
        self._hits: dict[str, deque] = {}
        self._day: date | None = None
        self._day_count = 0
        self._lock = threading.Lock()

    def try_acquire(self, ip: str) -> Decision:
        with self._lock:
            now = self._clock()
            today = date.fromtimestamp(now)
            if today != self._day:
                self._day, self._day_count = today, 0
            if len(self._hits) > MAX_TRACKED_IPS:
                self._hits = {k: q for k, q in self._hits.items() if q and now - q[-1] <= WINDOW_S}
            q = self._hits.setdefault(ip, deque())
            while q and now - q[0] > WINDOW_S:
                q.popleft()
            if len(q) >= self.per_hour:
                return Decision.IP_LIMIT
            if self._day_count >= self.daily_cap:
                return Decision.DAILY_CAP
            q.append(now)
            self._day_count += 1
            return Decision.ALLOWED
