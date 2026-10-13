"""Bounded immutable JSON results of pure computation, never source verdicts."""
from collections import OrderedDict
import json
from threading import RLock


class PreparationCache:
    def __init__(self, *, maximum_entries=8, maximum_bytes=32 * 1024 * 1024):
        if type(maximum_entries) is not int or maximum_entries < 1 or type(maximum_bytes) is not int or maximum_bytes < 1:
            raise ValueError('Preparation cache bounds must be positive integers')
        self.maximum_entries, self.maximum_bytes = maximum_entries, maximum_bytes
        self._entries, self._bytes, self._lock = OrderedDict(), 0, RLock()

    @property
    def byte_count(self):
        with self._lock:
            return self._bytes

    def compute(self, identity, producer):
        with self._lock:
            if identity in self._entries:
                payload = self._entries.pop(identity)
                self._entries[identity] = payload
                return json.loads(payload)
        value = producer()
        payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
        if len(payload) <= self.maximum_bytes:
            with self._lock:
                previous = self._entries.pop(identity, None)
                if previous is not None:
                    self._bytes -= len(previous)
                while self._entries and (len(self._entries) >= self.maximum_entries or self._bytes + len(payload) > self.maximum_bytes):
                    _, expired = self._entries.popitem(last=False)
                    self._bytes -= len(expired)
                self._entries[identity] = payload
                self._bytes += len(payload)
        # The producer's mutable graph is never retained or returned. Each
        # caller receives a separate value reconstructed from immutable bytes.
        return json.loads(payload)
