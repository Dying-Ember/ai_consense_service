"""Read-only source quality baseline. Parse coverage is never a fidelity score."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROLES = {'tender', 'standard', 'project_fact', 'package_manifest'}

def fingerprint(path):
    path = Path(path).resolve()
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for group in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(group)
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}

def baseline(inventory, raw_root, project_id, expected_sources):
    inventory = Path(inventory).resolve()
    raw_root = Path(raw_root).resolve(strict=True)
    identity = fingerprint(inventory)
    sources = json.loads(inventory.read_bytes())
    if not isinstance(sources, list) or len(sources) != expected_sources:
        raise ValueError('Unexpected source inventory shape/count')
    seen, records = set(), []
    for source in sources:
        sid = str(source['documentId'])
        if sid in seen or source['projectId'] != project_id or source['sourceRole'] not in ROLES:
            raise ValueError('Duplicate or out-of-scope source identity/role')
        seen.add(sid)
        path = Path(source['newStoragePath']).resolve(strict=True)
        if not path.is_relative_to(raw_root):
            raise ValueError('Original path is outside the named immutable uploads root')
        lower = source['fileName'].casefold()
        if any(marker in lower for marker in ('2d_tender doc vetting comments', '2e_questions and answers', 'vetting_cases.json')):
            raise ValueError('Evaluation answers cannot enter source inventory')
        actual = fingerprint(path)
        if actual['sha256'] != source['storedOriginalSha256'] or actual['sha256'] != source['copiedSha256']:
            raise ValueError('Original byte identity changed: ' + sid)
        if actual['bytes'] != source['originalBytes']:
            raise ValueError('Original size changed: ' + sid)
        coverage = json.loads(source['parseCoverageJson']) if source.get('parseCoverageJson') else None
        records.append({'documentId': sid, 'role': source['sourceRole'], 'fileKey': source['fileKey'],
            'fileName': source['fileName'], 'original': actual,
            'savedSourceHash': source['sourceHash'], 'savedStructuredJsonSha256': source['rawStructuredJsonSha256'],
            'savedParseStatus': source['parseStatus'], 'savedParseMessage': source.get('parseMessage'),
            'savedTextCharsUtf16': source['textCharsUtf16'], 'savedPageCount': source.get('pageCount'),
            'savedOcrUsed': source['ocrUsed'], 'savedParseCoverage': coverage,
            'fidelityAccepted': None, 'layoutAccepted': None, 'ocrAccuracy': None,
            'interpretation': 'Saved historical parse metadata; original bytes checked now; no new parse/OCR performed'})
    if fingerprint(inventory) != identity:
        raise ValueError('Inventory changed while being audited')
    ocr = [r for r in records if r['savedOcrUsed']]
    return {'protocol': 'foundation-source-quality-baseline-v1',
        'recordedAtUtc': datetime.now(timezone.utc).isoformat(),
        'projectId': project_id, 'inputInventory': identity, 'originalBytesVerifiedNow': len(records),
        'roleCounts': dict(Counter(r['role'] for r in records)),
        'historicalParseStatusCounts': dict(Counter(r['savedParseStatus'] for r in records)),
        'historicalOcrSourceCount': len(ocr),
        'historicalRecordedOcrPages': sum((r['savedParseCoverage'] or {}).get('ocrPages', 0) for r in ocr),
        'sources': records,
        'wholeSourceFidelityAccepted': None, 'wholeOcrAccuracy': None,
        'liveApplicationStateQueried': False,
        'actualNewCalls': {'parse': 0, 'ocr': 0, 'model': 0, 'index': 0, 'query': 0, 'database': 0},
        'limitations': ['Historical PARSED/complete does not imply native layout or text fidelity.',
            'Later source80/source95 reparses are separate source revisions; do not merge this baseline into a current live corpus.',
            'Source hashes describe saved derived data; the original byte SHA is checked independently.',
            'OCR page counts describe recorded processing, not pages independently quality-reviewed.']}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--raw-root', type=Path, required=True)
    parser.add_argument('--project-id', required=True)
    parser.add_argument('--expected-sources', type=int, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise ValueError('Output already exists; preserve previous baseline')
    value = baseline(args.inventory, args.raw_root, args.project_id, args.expected_sources)
    value['toolSource'] = fingerprint(__file__)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'receipt': fingerprint(args.out), 'sourceCount': value['originalBytesVerifiedNow'],
        'roleCounts': value['roleCounts'], 'historicalOcrSources': value['historicalOcrSourceCount'],
        'historicalOcrPages': value['historicalRecordedOcrPages'], 'newCalls': value['actualNewCalls']}))

if __name__ == '__main__':
    main()
