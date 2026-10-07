"""Compare another local model against already captured production topic inputs.

No retrieval is performed and source prompts are sent without edits. GPU/CPU placement
is observed while the request is active, because keep_alive=0 unloads after completion.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import time

import requests

from diagnose import validate_records

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--topic', type=int, action='append', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--ollama-url', default='http://127.0.0.1:11434')
    parser.add_argument('--ollama-executable', type=Path, default=WORKSPACE/'tmp/ollama_portable/ollama.exe')
    parser.add_argument('--timeout', type=float, default=300)
    parser.add_argument('--num-ctx', type=int, default=8192)
    parser.add_argument('--temperature', type=float, default=.2)
    parser.add_argument('--system-prompt-file', type=Path,
                        help='Explicit generic prompt experiment; original captured user prompt remains unchanged')
    parser.add_argument('--num-predict', type=int, help='Optional output-token budget for a decoding experiment')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    summary = {'model': args.model, 'inputDirectory': str(args.inputs.resolve()), 'topics': [],
               'note': 'No new retrieval or evaluation answers read. Captured user prompts remain unchanged; an optional system prompt/decoding override is labelled as an experiment. Placement snapshots come from Ollama ps while requests are active.'}
    override = args.system_prompt_file.read_text(encoding='utf-8') if args.system_prompt_file else None
    for index in args.topic:
        path = args.inputs/f'topic_{index:02d}.json'
        original = json.loads(path.read_text(encoding='utf-8'))
        body = {'model': args.model, 'messages': [{'role': 'system', 'content': override or original['systemPrompt']},
                {'role': 'user', 'content': original['userPrompt']}], 'stream': False,
                'options': {'temperature': args.temperature, 'num_ctx': args.num_ctx}}
        if args.num_predict is not None:
            body['options']['num_predict'] = args.num_predict
        row = {'topicIndex': index, 'topic': original['topic'], 'capturedInputSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
               'inputFile': str(path.resolve()), 'body': body, 'placement': [],
               'systemPromptSha256': hashlib.sha256(body['messages'][0]['content'].encode()).hexdigest(),
               'promptMode': 'generic_prompt_experiment' if override else 'captured_production_prompt',
               'startedAt': datetime.now(timezone.utc).isoformat()}
        print(json.dumps({'topic': index, 'model': args.model, 'phase': 'starting'}), flush=True)
        started, last_placement = time.perf_counter(), None
        try:
            with ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(requests.post, args.ollama_url.rstrip('/')+'/api/chat',
                                         json=body, timeout=(10, args.timeout))
                while not future.done():
                    observation = {'seconds': round(time.perf_counter()-started, 3)}
                    try:
                        response = requests.get(args.ollama_url.rstrip('/')+'/api/ps', timeout=(3, 3))
                        response.raise_for_status()
                        observation['apiPs'] = response.json()
                        if observation['apiPs'].get('models'):
                            observed = subprocess.run([str(args.ollama_executable), 'ps'], capture_output=True,
                                                      text=True, timeout=10)
                            observation['cliPs'] = observed.stdout.strip()
                            marker = observation['cliPs'].split('CONTEXT')[0]
                            if marker != last_placement:
                                print(json.dumps({'topic': index, 'placement': observation['cliPs']}, ensure_ascii=False), flush=True)
                                last_placement = marker
                        gpu = subprocess.run(['nvidia-smi', '--query-gpu=memory.total,memory.used', '--format=csv,noheader,nounits'],
                                             capture_output=True, text=True, timeout=10)
                        observation['gpuMemoryTotalUsedMiB'] = gpu.stdout.strip()
                    except Exception as error:
                        observation['error'] = f'{type(error).__name__}: {error}'
                    row['placement'].append(observation)
                    time.sleep(1)
                response = future.result()
            response.raise_for_status()
            row['response'] = response.json()
            known = {hit['id']: hit['payload'] for role in original['retrieval'].values() for hit in role['response']['hits']}
            submitted = [known[item['id']] for item in original['submitted']]
            row['validation'] = validate_records(row['response']['message']['content'], submitted)
            row['status'] = 'completed'
        except Exception as error:
            row.update(status='failed', error=f'{type(error).__name__}: {error}')
        row['seconds'] = time.perf_counter()-started
        (args.out/f'topic_{index:02d}.json').write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding='utf-8')
        compact = {key: row.get(key) for key in ('topicIndex', 'status', 'seconds', 'validation', 'error')}
        summary['topics'].append(compact)
        (args.out/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(compact, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
