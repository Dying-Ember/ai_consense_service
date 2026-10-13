"""Private, request-scoped receipts for actual OER inputs and model events.

Files belong in the isolated service state, never in an HTTP response or stdout.
The journal observes a run; it does not certify text, relevance or completeness.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from functools import wraps
import hashlib
import json
from pathlib import Path
import time
import uuid


def _encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def _save(path, value):
    raw = _encode(value)
    with path.open('xb') as stream:
        stream.write(raw)
    return {'file': path.name, 'sha256': hashlib.sha256(raw).hexdigest(),
            'byteCount': len(raw)}


def _inspect_events(path):
    digest, count, byte_count, malformed = hashlib.sha256(), 0, 0, False
    with path.open('rb') as stream:
        for line in stream:
            digest.update(line)
            byte_count += len(line)
            try:
                event = json.loads(line)
                if not isinstance(event, dict) or event.get('ordinal') != count + 1 or not line.endswith(b'\n'):
                    malformed = True
                else:
                    count += 1
            except (ValueError, UnicodeError):
                malformed = True
    return {'file': path.name, 'count': count, 'sha256': digest.hexdigest(),
            'byteCount': byte_count, 'integrityStatus': 'malformed_or_interrupted' if malformed else 'complete'}


class RequestEvidence:
    def __init__(self, root):
        self.root = Path(root)
        self.active = ContextVar('oer_request_evidence_' + uuid.uuid4().hex, default=None)

    def observe(self, stage, payload):
        record = self.active.get()
        if record is None:
            return
        next_ordinal = record['events'] + 1
        raw = _encode({'ordinal': next_ordinal, 'stage': stage,
                       'elapsedSeconds': time.perf_counter() - record['started'],
                       'payload': payload}) + b'\n'
        with (record['directory'] / 'events.jsonl').open('ab') as stream:
            stream.write(raw)
        record['events'] = next_ordinal

    @contextmanager
    def operation(self, endpoint, request):
        # /index-canonical calls the same guarded /index implementation. Keep
        # one outer run receipt while retaining its exact derived input event.
        if self.active.get() is not None:
            self.observe('nested-operation-input', {'endpoint': endpoint, 'request': request})
            yield None
            return
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
        directory = self.root / (stamp + '-' + uuid.uuid4().hex)
        directory.mkdir(parents=True, exist_ok=False)
        record = {'directory': directory, 'started': time.perf_counter(), 'events': 0,
                  'request': _save(directory / 'request.json', request), 'response': None}
        (directory / 'events.jsonl').touch(exist_ok=False)
        token = self.active.set(record)
        status, error = 'completed', None
        try:
            yield record
        except BaseException as failure:
            status = 'failed'
            error = {'type': type(failure).__name__, 'message': str(failure)}
            for name in ('status_code', 'detail', 'kind', 'code', 'metadata'):
                if hasattr(failure, name):
                    error[name] = getattr(failure, name)
            raise
        finally:
            self.active.reset(token)
            _save(directory / 'receipt.json', {
                'schema': 'consense.oer-request-evidence.v1', 'endpoint': endpoint,
                'createdAt': stamp, 'terminalStatus': status, 'error': error,
                'elapsedSeconds': time.perf_counter() - record['started'],
                'request': record['request'], 'response': record['response'],
                'events': _inspect_events(directory / 'events.jsonl'),
                'qualityCertified': False, 'humanConfirmed': False})

    def wrap(self, endpoint, operation):
        @wraps(operation)
        def recorded(request):
            value = request.model_dump(exclude_none=True)
            with self.operation(endpoint, value) as record:
                result = operation(request)
                if record is not None:
                    record['response'] = _save(record['directory'] / 'response.json', result)
                return result
        return recorded
