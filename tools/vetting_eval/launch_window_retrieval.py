"""Explicit offline launcher for the verified CUDA float32 window recipe.

The default command prepares a file receipt and does not start HTTP or load model
weights. ``serve`` is an explicit action, separate from benchmark receipts.
"""
import argparse
import json
import os
from pathlib import Path
import sys

from actual_window_retrieval import (ENV, MATH, model_identity, desc, require,
                                     validate_cache_bundle, external, set_math)


def parse(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'serve'))
    for name in ('workspace', 'state', 'embedding-snapshot', 'rerank-snapshot'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--host', choices=('127.0.0.1',), default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    parser.add_argument('--fixed-cache-bundle')
    parser.add_argument('--fixed-cache-bundle-sha256')
    return parser.parse_args(argv)


def settings(args):
    workspace, state = Path(args.workspace).resolve(), Path(args.state).resolve()
    require(workspace.is_dir() and state.is_relative_to(workspace), 'Workspace must exist; explicit state must stay inside it')
    require(1 <= args.port <= 65535, 'Invalid loopback port')
    require(bool(args.fixed_cache_bundle) == bool(args.fixed_cache_bundle_sha256), 'Cache path and external SHA required together')
    config = json.loads(Path(__file__).with_name('samples.json').read_text(encoding='utf-8'))
    models = {'embedding': model_identity(args.embedding_snapshot), 'reranker': model_identity(args.rerank_snapshot)}
    for kind, identity in models.items():
        require(Path(identity['snapshot']).name == config[kind]['revision'], 'Explicit local snapshot revision differs')
    bundle_descriptor = external(args.fixed_cache_bundle, args.fixed_cache_bundle_sha256) if args.fixed_cache_bundle else None
    bundle = validate_cache_bundle(bundle_descriptor) if bundle_descriptor else None
    return {'workspace': str(workspace), 'state': str(state), 'models': models, 'environment': dict(ENV), 'math': dict(MATH),
            'host': args.host, 'port': args.port, 'fixedCacheBundle': bundle_descriptor}, bundle


def write_receipt(args, plan):
    receipt = Path(args.receipt).resolve()
    require(not receipt.exists(), 'Fresh startup receipt required')
    receipt.parent.mkdir(parents=True, exist_ok=True)
    code = {name: desc(Path(__file__).with_name(name)) for name in
            ('server.py', 'token_windows.py', 'window_runtime.py', 'workspace_root.py', 'fixed_vector_cache.py', 'retrieval_source_units.py', 'embedding_compatibility.py', 'actual_window_retrieval.py', 'launch_window_retrieval.py', 'samples.json')}
    value = {'protocol': 'explicit-offline-window-server-launch-receipt-v1', 'status': 'prepared_no_inference',
             'mode': args.mode, 'settings': plan, 'software': code,
             'actualCallsAtReceipt': {'modelWeightLoads': 0, 'encode': 0, 'predict': 0, 'index': 0, 'query': 0, 'HTTP': 0, 'download': 0, 'DB': 0},
             'limits': ['Preparing or launching does not prove native semantic accuracy or full application integration.',
                        'serve may open the explicit new local state on later application index/query requests.'],
             'parentDirectoryDefaultBypassed': True}
    with receipt.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    return desc(receipt)


def configure_server(server, models, math):
    original = server.model_runtime
    server.model_runtime = lambda kind: {**original(kind), 'executionMath': math}
    runtime = server.window_runtime()
    original_identity = runtime.embedding_cache_identity
    runtime.embedding_cache_identity = lambda dimension: {**original_identity(dimension), 'executionMath': math}
    server.model_path = lambda config: models['embedding' if config['name'] == server.MODELS['embedding']['name'] else 'reranker']['snapshot']


def main(argv=None):
    args = parse(argv)
    plan, bundle = settings(args)
    receipt = write_receipt(args, plan)
    print(json.dumps({'receipt': receipt, 'mode': args.mode, 'HTTPStarted': False}))
    if args.mode == 'prepare':
        return
    # Child/process-scoped variables only. No proxy, user/system registry or
    # persistent environment changes. These match the actual frozen benchmark.
    os.environ.update(ENV)
    os.environ['CONSENSE_WORKSPACE_ROOT'] = plan['workspace']
    os.environ['HF_HOME'] = str(Path(plan['workspace']) / 'tmp/vetting_models')
    os.environ['CONSENSE_RETRIEVAL_DATA'] = plan['state']
    for key in ('CONSENSE_FIXED_VECTOR_CACHE', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256'):
        os.environ.pop(key, None)
    if bundle:
        os.environ['CONSENSE_FIXED_VECTOR_CACHE'] = bundle['root']
        os.environ['CONSENSE_FIXED_VECTOR_CACHE_WHITELIST'] = bundle['whitelist']['path']
        os.environ['CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256'] = bundle['whitelist']['sha256']
    actual = set_math()
    import server
    configure_server(server, plan['models'], actual['math'])
    import uvicorn
    uvicorn.run(server.app, host=args.host, port=args.port, workers=1)


if __name__ == '__main__':
    main()
