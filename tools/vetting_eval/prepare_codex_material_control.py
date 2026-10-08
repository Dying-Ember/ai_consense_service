"""Freeze captured messages for a blind Codex same-material control; no inference."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def descriptor(path):
    raw = path.read_bytes()
    return {'path': str(path.resolve()), 'bytes': len(raw), 'sha256': sha(raw)}


def checked_body(expected):
    path = Path(expected['path'])
    actual = descriptor(path)
    if actual != expected:
        raise ValueError('Captured input identity changed: ' + str(path))
    return path.read_bytes()


def checked_text(event, event_dir):
    expected = event['textArtifact']
    path = (event_dir / expected['relativePath']).resolve()
    if path.parent != event_dir.resolve():
        raise ValueError('Text artifact escaped the bound probe directory')
    raw = path.read_bytes()
    if len(raw) != expected['bytes'] or sha(raw) != expected['sha256']:
        raise ValueError('Captured caller string identity changed')
    return raw


def prepare(case, run_id, project_id, out):
    case = Path(case).resolve()
    out = Path(out).resolve()
    job = json.loads((case / 'terminal_job.json').read_bytes())
    if job['id'] != run_id or job['status'] != 'COMPLETED' or job['completedUnits'] != job['totalUnits']:
        raise ValueError('Only a completed, explicitly identified source run can be prepared')
    binding = json.loads((case / 'active_run_binding.json').read_bytes())
    if binding['runId'] != run_id or binding['projectId'] != project_id:
        raise ValueError('Application binding differs')
    event_dir = case / 'probes/java' / run_id
    stages = {}
    for phase in ('model_gateway_system_projection', 'model_user', 'model_schema', 'context_selection'):
        for path in sorted(event_dir.glob('*-' + phase + '.json')):
            event = json.loads(path.read_bytes())
            if event['runId'] != run_id or event['projectId'] != project_id or event['phase'] != phase:
                raise ValueError('Mixed caller event identity')
            key = (event['topicIndex'], phase)
            if key in stages:
                raise ValueError('Ambiguous duplicate caller stage')
            stages[key] = (event, path)
    prepared = []
    for directory in sorted((case / 'probes/model_http').glob('call_*')):
        capture_path = directory / 'capture.json'
        capture = json.loads(capture_path.read_bytes())
        ordinal = capture['serialOrdinal']
        if (capture['fixtureOnly'] or capture['status'] != 'captured' or not capture['stableRunBinding']
                or capture['caseBindingAtStart']['runId'] != run_id
                or capture['caseBindingAtEnd']['projectId'] != project_id):
            raise ValueError('Unbound or incomplete baseline request')
        raw = checked_body(capture['clientRequestBody'])
        if raw != checked_body(capture['upstreamRequestBody']):
            raise ValueError('The baseline request was altered in transport')
        body = json.loads(raw)
        if body.get('stream') is not False or len(body['messages']) != 2:
            raise ValueError('Unexpected captured request envelope')
        if [m['role'] for m in body['messages']] != ['system', 'user']:
            raise ValueError('Unexpected captured message roles')
        for message, phase in zip(body['messages'], ('model_gateway_system_projection', 'model_user')):
            event, _ = stages[(ordinal, phase)]
            if message['content'].encode('utf-8') != checked_text(event, event_dir):
                raise ValueError('Messages do not match the actual caller strings')
        schema_event, schema_path = stages[(ordinal, 'model_schema')]
        schema_raw = checked_text(schema_event, event_dir)
        schema = json.loads(schema_raw)
        context, context_path = stages[(ordinal, 'context_selection')]
        prepared.append({'ordinal': ordinal, 'messages': body['messages'], 'schema': schema,
            'schemaRaw': schema_raw, 'context': context, 'inputProof': {
                'callerSchema': descriptor(schema_path), 'callerContext': descriptor(context_path),
                'actualRequestEntity': capture['clientRequestBody'],
                'messagesCanonicalSha256': sha(canonical(body['messages'])),
                'systemUtf8Sha256': sha(body['messages'][0]['content'].encode('utf-8')),
                'userUtf8Sha256': sha(body['messages'][1]['content'].encode('utf-8')),
                'baselineClientParameters': {k: v for k, v in body.items() if k != 'messages'},
                'captureId': capture['captureId']}})
    if [p['ordinal'] for p in prepared] != list(range(1, 20)):
        raise ValueError('Expected all nineteen captured production inputs')
    # Validate all source identities before creating the new output directory.
    out.mkdir(parents=True, exist_ok=False)
    rows = []
    for item in prepared:
        directory = out / ('call_%02d' % item['ordinal'])
        directory.mkdir()
        for name, raw in (('messages.json', canonical(item['messages'])),
                          ('system.txt', item['messages'][0]['content'].encode('utf-8')),
                          ('user.txt', item['messages'][1]['content'].encode('utf-8')),
                          ('schema.json', item['schemaRaw'])):
            with (directory / name).open('xb') as stream:
                stream.write(raw)
        row = {'ordinal': item['ordinal'], 'topic': item['context']['topic'],
               'messages': descriptor(directory / 'messages.json'),
               'system': descriptor(directory / 'system.txt'), 'user': descriptor(directory / 'user.txt'),
               'schema': descriptor(directory / 'schema.json'), 'inputProof': item['inputProof'],
               'sourceChunkIds': item['context']['observations']['actualSubmittedChunkIds']}
        rows.append(row)
    manifest = {'schemaVersion': 1, 'status': 'prepared_no_inference',
        'preparedAtUtc': datetime.now(timezone.utc).isoformat(), 'sourceRunId': run_id, 'projectId': project_id,
        'controlType': 'Blind same-material Codex task; not a parameter-matched API or model-only benchmark',
        'modelRoute': 'Inherited model and reasoning of the current Codex account task',
        'capturedInputCount': len(rows), 'calls': rows, 'software': descriptor(Path(__file__).resolve()),
        'externalApiCalls': 0, 'applicationModelCalls': 0, 'sourceAnswerFixtureReads': 0,
        'readBoundary': 'Captured request bytes and actual Java prompt/schema/context events only. No baseline responses, findings or independent gold are read.',
        'evaluationProtocol': ['Freeze the blind structured output before revealing any Bonsai answer or original-source audit.',
            'Reviewer sees only one captured call at a time; no extra retrieval, other calls or original documents.',
            'Evaluate supported claims, honest unknowns and missing coverage separately after blind output is frozen.',
            'Run the exact application gates separately; a schema/quote checker is not a substitute for those gates.',
            'Codex harness/system instructions, sampling, output limit and tokenizer differ and are not claimed identical.'],
        'semanticQualityAccepted': None}
    path = out / 'prepared_manifest.json'
    with path.open('xb') as stream:
        stream.write(json.dumps(manifest, ensure_ascii=False, indent=2, allow_nan=False).encode('utf-8') + b'\n')
    return descriptor(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--project-id', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.case, args.run_id, args.project_id, args.out)))


if __name__ == '__main__':
    main()
