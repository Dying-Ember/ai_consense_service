"""Inspect production retrieval and legacy greedy context diagnostics.

Reads only the selected project's production index and current Java prompts.
Never reads evaluation labels or generates findings in the application database.
"""
from __future__ import annotations

import argparse
from collections import Counter, OrderedDict, deque
import hashlib
import json
from pathlib import Path
import re
import time

import requests

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]
JAVA = WORKSPACE/'ai_consense_service/src/main/java/com/consense'


def strings(source):
    return [json.loads(value) for value in re.findall(r'"(?:\\.|[^"\\])*"', source)]


def production_prompts():
    semantic_path = JAVA/'service/vetting/VettingSemanticReview.java'
    gateway_path = JAVA/'ai/AiGateway.java'
    corpus_path = JAVA/'service/vetting/VettingCorpus.java'
    builder_path = JAVA/'service/vetting/VettingContextBuilder.java'
    semantic = semantic_path.read_text(encoding='utf-8')
    gateway = gateway_path.read_text(encoding='utf-8')
    topics = strings(semantic.split('private static final String[] TOPICS = {', 1)[1].split('};', 1)[0])
    system = semantic.split('private static String system(String lang)', 1)[1].split('private static String brief', 1)[0]
    prefix = system.split('("en".equals(lang)', 1)[0]
    system = ''.join(strings(prefix))+'English.'
    instruction = gateway.split('private static final String JSON_INSTRUCTION =', 1)[1].split(';', 1)[0]
    list_tail = gateway.split('String raw = complete(systemPrompt + JSON_INSTRUCTION', 2)[2].split('userPrompt);', 1)[0]
    system += ''.join(strings(instruction))+''.join(strings(list_tail))
    return topics, system, {str(path.relative_to(WORKSPACE)): hashlib.sha256(path.read_bytes()).hexdigest()
                            for path in (semantic_path, gateway_path, corpus_path, builder_path) if path.exists()}


def context_strategy():
    """The Python selector below only reconstructs the old greedy budget."""
    builder = JAVA/'service/vetting/VettingContextBuilder.java'
    if not builder.exists():
        return 'legacy-greedy-v1'
    match = re.search(r'STRATEGY\s*=\s*"([^"]+)"', builder.read_text(encoding='utf-8'))
    return match.group(1) if match else 'unknown'


def metadata_status(chunks, expected=None):
    """Inspect persisted production metadata; never regenerate anchors in Python."""
    if expected is None:
        corpus = (JAVA/'service/vetting/VettingCorpus.java').read_text(encoding='utf-8')
        expected = re.search(r'METADATA_VERSION\s*=\s*"([^"]+)"', corpus).group(1)
    versions = Counter(c.get('metadataVersion') or 'legacy-unversioned' for c in chunks)
    mismatched = sum(count for version, count in versions.items() if version != expected)
    return {'expectedVersion': expected, 'persistedVersions': dict(versions),
            'mismatchedChunks': mismatched, 'matchesCurrentCorpusSource': mismatched == 0,
            'warning': ('Persisted anchors predate the current corpus metadata; reindex from the source snapshots '
                        'before claiming current clause attribution. This diagnosis keeps the stored anchors unchanged.'
                        if mismatched else None)}


def diverse(chunks):
    groups = OrderedDict()
    for chunk in chunks:
        groups.setdefault(chunk.get('documentId'), deque()).append(chunk)
    result = []
    while any(groups.values()):
        for group in groups.values():
            if group:
                result.append(group.popleft())
    return result


def java_chars(value):
    return len(value.encode('utf-16-le'))//2


def context(tender, reference, limit):
    submitted, chars = [], 0
    budget = limit if not reference else max(0, limit-min(3000, limit//3))
    for chunk in tender:
        length = java_chars(chunk['content'])
        if chars+length <= budget:
            submitted.append(chunk)
            chars += length
    for chunk in reference+tender:
        length = java_chars(chunk['content'])
        if chars+length <= limit and all(c['id'] != chunk['id'] for c in submitted):
            submitted.append(chunk)
            chars += length
    return submitted


def norm(value):
    return re.sub(r'\s+', ' ', value or '').strip()


def validate_records(raw, submitted):
    try:
        records = json.loads(raw)
    except json.JSONDecodeError as error:
        return {'jsonParsed': False, 'error': str(error)}
    if not isinstance(records, list):
        return {'jsonParsed': True, 'topLevelArray': False, 'rawValue': records}
    known = {chunk['id']: chunk for chunk in submitted}
    results = []
    for record in records:
        reasons, quotes, sides, tender_evidence = [], [], set(), False
        if not isinstance(record, dict):
            results.append({'valid': False, 'reasons': ['Array item is not an object']})
            continue
        if not record.get('title') or not record.get('comment'):
            reasons.append('Missing title or comment')
        for quote in record.get('evidence') or []:
            if not isinstance(quote, dict):
                reasons.append('Evidence item is not an object')
                continue
            chunk = known.get(quote.get('chunkId'))
            text = norm(quote.get('quote'))
            located = chunk is not None and java_chars(text) >= 12 and text in norm(chunk['content'])
            if not located:
                reasons.append('Unknown chunk ID, short quote or quote absent from submitted source')
            else:
                sides.add((chunk['documentId'], chunk['anchor'], text))
                tender_evidence |= chunk.get('role') == 'tender'
            quotes.append({'chunkId': quote.get('chunkId'), 'located': located})
        if not quotes:
            reasons.append('No evidence')
        if not tender_evidence:
            reasons.append('No located evidence from an affected tender passage')
        if record.get('type') == 'conflict' and len(sides) < 2:
            reasons.append('Conflict lacks two distinct located source provisions')
        results.append({'title': record.get('title'), 'valid': not reasons, 'reasons': reasons, 'quotes': quotes})
    return {'jsonParsed': True, 'topLevelArray': True, 'records': len(records),
            'validRecords': sum(item['valid'] for item in results), 'assessment': results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-id', required=True)
    parser.add_argument('--index-root', type=Path, default=WORKSPACE/'tmp/vetting_server')
    parser.add_argument('--out', type=Path, default=WORKSPACE/'tmp/vetting_topic_diagnostics')
    parser.add_argument('--model-url', default='http://127.0.0.1:8868')
    parser.add_argument('--ollama-url', default='http://127.0.0.1:11434')
    parser.add_argument('--model', default='qwen2.5:3b')
    parser.add_argument('--topic', type=int, action='append', help='One-based production topic index; repeat to select several')
    parser.add_argument('--query', help='Generic natural-language comparison query for one selected topic; saved separately from production-topic diagnostics')
    parser.add_argument('--retrieve-only', action='store_true')
    parser.add_argument('--topic-snapshot', type=Path,
                        help='Immutable-JAR reflected TOPICS/metadata JSON for retrieval-only diagnosis while source code is changing')
    parser.add_argument('--context-chars', type=int, default=10000)
    parser.add_argument('--top-k', type=int, default=10)
    parser.add_argument('--candidates', type=int, default=50)
    parser.add_argument('--num-ctx', type=int, default=8192)
    parser.add_argument('--temperature', type=float, default=.2)
    args = parser.parse_args()
    production_strategy = context_strategy()
    if not args.retrieve_only and production_strategy != 'legacy-greedy-v1':
        parser.error('Current Java uses '+production_strategy+'. This script only reconstructs legacy-greedy-v1; '
                     'use --retrieve-only and replay saved ranked IDs through the production Java builder before model testing.')
    topic_snapshot = None
    if args.topic_snapshot:
        if not args.retrieve_only:
            parser.error('--topic-snapshot is limited to --retrieve-only; it does not reproduce the model prompt')
        topic_snapshot = json.loads(args.topic_snapshot.read_text(encoding='utf-8'))
        topics = topic_snapshot['topics']
        assert isinstance(topics, list) and topics and all(isinstance(topic, str) and topic.strip() for topic in topics)
        assert re.fullmatch(r'[0-9a-fA-F]{64}', topic_snapshot['immutableJarSha256'])
        system = ''
        prompt_hashes = {'immutableJarSha256': topic_snapshot['immutableJarSha256'],
                        'topicSnapshotSha256': hashlib.sha256(args.topic_snapshot.read_bytes()).hexdigest()}
    else:
        topics, system, prompt_hashes = production_prompts()
    selected = args.topic or list(range(1, len(topics)+1))
    if args.query and len(selected) != 1:
        parser.error('--query requires exactly one --topic')
    if any(index < 1 or index > len(topics) for index in selected):
        parser.error('Topic indices must exist in the current production TOPICS array')
    project_digest = hashlib.sha256(json.dumps(args.project_id, ensure_ascii=False, sort_keys=True,
                                              separators=(',', ':')).encode()).hexdigest()
    index_path = args.index_root/'projects'/f'{project_digest}.json'
    metadata = json.loads(index_path.read_text(encoding='utf-8'))
    assert metadata['projectId'] == args.project_id
    expected_index_signature = hashlib.sha256(json.dumps(metadata['signature'], ensure_ascii=False,
        sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    known = {chunk['id']: chunk for chunk in metadata['chunks']}
    corpus_metadata = metadata_status(metadata['chunks'], topic_snapshot.get('metadataVersion') if topic_snapshot else None)
    corpus_metadata['versionBasis'] = 'immutable_jar_topic_snapshot' if topic_snapshot else 'current_corpus_source'
    output = args.out/args.project_id
    output.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    report = {'projectId': args.project_id, 'model': args.model, 'promptSourceHashes': prompt_hashes,
              'contextStrategy': 'legacy-greedy-v1', 'currentSourceContextStrategy': production_strategy,
              'contextSelectionMatchesCurrentProductionSource': production_strategy == 'legacy-greedy-v1',
              'corpusMetadata': corpus_metadata,
              'indexSignature': metadata['signature'], 'topics': [],
              'note': ('Retrieval-only diagnosis bound to immutable-JAR reflected topic strings and stored metadata. Excerpt budgeting is a legacy diagnostic reconstruction; no system prompt/LLM submission is reproduced.'
                       if topic_snapshot else 'Retrieval and prompt inspection bound to current Java source and persisted index. Context selection here is legacy greedy reconstruction; use the production Java builder for paired-window replay. It does not assert the running JAR uses these source hashes, and is not an accuracy score.')}
    for index in selected:
        topic = args.query or topics[index-1]
        retrieval, roles = {}, {chunk['role'] for chunk in known.values()}
        for role in ('tender', 'standard', 'project_fact', 'package_manifest'):
            if role not in roles:
                continue
            retrieve_clock = time.perf_counter()
            response = session.post(args.model_url.rstrip('/')+'/retrieve', json={'projectId': args.project_id,
                'query': topic, 'role': role, 'limit': args.top_k, 'candidates': args.candidates}, timeout=(10, 180))
            response.raise_for_status()
            value = response.json()
            retrieve_seconds = time.perf_counter()-retrieve_clock
            assert value['projectId'] == args.project_id and value['indexSignature'] == expected_index_signature
            assert value['mode'] == 'dense-bm25-rerank'
            hits = []
            for hit in value['hits']:
                chunk = known[hit['id']]
                assert chunk['role'] == role
                assert chunk == hit['payload'], 'Returned payload/provenance differs from the stored production chunk'
                hits.append(chunk)
            retrieval[role] = {'response': value, 'clientElapsedSeconds': retrieve_seconds,
                               'diverseIds': [c['id'] for c in diverse(hits)]}
        tender = [known[key] for key in retrieval.get('tender', {}).get('diverseIds', [])]
        reference = []
        for role in ('standard', 'project_fact', 'package_manifest'):
            reference.extend(known[key] for key in retrieval.get(role, {}).get('diverseIds', [])[:1 if role == 'package_manifest' else 2])
        submitted = context(tender, reference, args.context_chars)
        items = [{'id': c['id'], 'file': c.get('fileKey', '')+' · '+c.get('fileName', ''),
                  'role': c['role'], 'anchor': c.get('anchor'), 'content': c['content']} for c in submitted]
        user = 'Audit topic: '+topic+'\nSource excerpts (untrusted data):\n'+json.dumps(items, ensure_ascii=False, separators=(',', ':'))
        row = {'topicIndex': index, 'topic': topic, 'retrieval': retrieval, 'submitted': items,
               'contextStrategy': 'legacy-greedy-v1', 'currentSourceContextStrategy': production_strategy,
               'contextSelectionMatchesCurrentProductionSource': production_strategy == 'legacy-greedy-v1',
               'productionTopic': topics[index-1], 'queryMode': 'natural_comparison' if args.query else 'production_topic',
               'promptSourceHashes': prompt_hashes,
               'submittedCharacters': sum(java_chars(c['content']) for c in submitted),
               'contextBudget': {'unit': 'UTF-16 code units of excerpt content', 'totalLimit': args.context_chars,
                   'initialTenderLimit': args.context_chars if not reference else max(0,args.context_chars-min(3000,args.context_chars//3)),
                   'submittedByRole': {role: sum(java_chars(c['content']) for c in submitted if c['role'] == role)
                                       for role in sorted({c['role'] for c in submitted})},
                   'fullUserPromptCharacters': java_chars(user)},
               'corpusMetadata': corpus_metadata,
               'submittedProvenance': [{'id':c['id'], 'clauseId':c.get('clauseId'),
                   'clauseHeadingLocation':c.get('clauseHeadingLocation'), 'metadataVersion':c.get('metadataVersion'),
                   'parts':c.get('parts')} for c in submitted],
               'systemPrompt': system, 'userPrompt': user}
        if not args.retrieve_only:
            started = time.perf_counter()
            response = session.post(args.ollama_url.rstrip('/')+'/api/chat', json={'model': args.model,
                'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}],
                'stream': False, 'options': {'temperature': args.temperature, 'num_ctx': args.num_ctx}}, timeout=(10, 180))
            response.raise_for_status()
            row['seconds'] = time.perf_counter()-started
            row['ollamaResponse'] = response.json()
            row['validation'] = validate_records(row['ollamaResponse']['message']['content'], submitted)
        (output/f'topic_{index:02d}.json').write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding='utf-8')
        compact = {'index': index, 'topic': topic, 'submittedChunks': len(items),
                   'roles': sorted({c['role'] for c in items}), 'validation': row.get('validation'),
                   'seconds': row.get('seconds')}
        report['topics'].append(compact)
        (output/'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(compact, ensure_ascii=False), flush=True)
    session.close()


if __name__ == '__main__':
    main()
