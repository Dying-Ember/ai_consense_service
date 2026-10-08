"""Explicit, externally bound compatibility for one legacy v3 embedding path.

No model/vector imports or inference. This is an executable source equivalence
check, not a claim that arbitrary Python rewrites have equivalent semantics.
The two approved immutable predecessors are bound before any legacy hash is used.
"""
import argparse
import ast
import copy
import hashlib
import json
import os
from pathlib import Path

from token_windows import fail

PROTOCOL = 'explicit-legacy-v3-embedding-compatibility-v1'
LEGACY_PROVENANCE_SHA = '909e67644bbd116e6bd9c0fcfbf0a7f055e2ffab83d105f4b45b156a3a70df44'
STRUCTURAL_PROVENANCE_SHA = '8b1175edaba7e00376cad92f8ff9f9b5fbb6e5a4dd73c96403312697a301f9ed'
LEGACY_ALGORITHMS = ('window_runtime.py', 'token_windows.py', 'fixed_vector_cache.py', 'server.py', 'workspace_root.py')
CORE_FILES = (*LEGACY_ALGORITHMS, 'ocr_runtime.py', 'samples.json', 'actual_window_retrieval.py', 'launch_window_retrieval.py')
ADDITIONS = ('retrieval_source_units.py', 'embedding_compatibility.py')
ENV_PATH = 'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT'
ENV_SHA = 'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT_SHA256'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def descriptor(path):
    path = Path(path).resolve(); raw = path.read_bytes()
    return {'path': str(path), 'bytes': len(raw), 'sha256': sha(raw)}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        fail(key not in result, 'EMBEDDING_COMPATIBILITY_JSON', 'Duplicate receipt JSON key')
        result[key] = value
    return result


def read_descriptor(value):
    fail(isinstance(value, dict) and set(value) == {'path', 'bytes', 'sha256'}, 'EMBEDDING_COMPATIBILITY_DESCRIPTOR', 'Exact file descriptor required')
    raw = Path(value['path']).read_bytes()
    fail(type(value['bytes']) is int and len(raw) == value['bytes'] and sha(raw) == value['sha256'], 'EMBEDDING_COMPATIBILITY_BYTES', 'Bound file bytes changed')
    return raw


def read_json(value):
    return json.loads(read_descriptor(value), object_pairs_hook=unique_object)


def tree(raw):
    return ast.parse(raw.decode('utf-8-sig'))


def dump(node):
    return ast.dump(node, include_attributes=False)


def methods(module, class_name='WindowRuntime'):
    classes = [n for n in module.body if isinstance(n, ast.ClassDef) and n.name == class_name]
    fail(len(classes) == 1, 'EMBEDDING_COMPATIBILITY_AST', 'Unique runtime class required')
    result = {n.name: n for n in classes[0].body if isinstance(n, ast.FunctionDef)}
    fail(len(result) == len(classes[0].body), 'EMBEDDING_COMPATIBILITY_AST', 'Only known runtime methods are permitted')
    return result


def normalize_calls(node):
    result = copy.deepcopy(node)
    for item in ast.walk(result):
        if isinstance(item, ast.Call) and isinstance(item.func, ast.Attribute) and item.func.attr == 'embedding_algorithms':
            fail(dump(item.func.value) == dump(ast.Name(id='self', ctx=ast.Load())) and not item.args and not item.keywords,
                 'EMBEDDING_COMPATIBILITY_AST', 'Only self.algorithms replacement is permitted')
            item.func.attr = 'algorithms'
    return result


def loaded_names(module):
    assignments = [n for n in module.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == '_loaded_source_hashes' for t in n.targets)]
    fail(len(assignments) == 1 and isinstance(assignments[0].value, ast.DictComp), 'EMBEDDING_COMPATIBILITY_AST', 'Known source hash comprehension required')
    return tuple(ast.literal_eval(assignments[0].value.generators[0].iter))


def outer_projection(module):
    result = copy.deepcopy(module)
    result.body = [n for n in result.body if not (
        isinstance(n, ast.ImportFrom) and n.module in {'retrieval_source_units', 'embedding_compatibility'})]
    for n in result.body:
        if isinstance(n, ast.ClassDef) and n.name == 'WindowRuntime':
            n.body = []
        if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == '_loaded_source_hashes' for t in n.targets):
            n.value.generators[0].iter = ast.parse(repr(LEGACY_ALGORITHMS), mode='eval').body
    return result


def retrieve_embedding_prefix(node, structural):
    """Allow only the saved-score selection tail/empty-scope response changes."""
    result = copy.deepcopy(node)
    scope = result.body[-1]
    fail(isinstance(scope, ast.With), 'EMBEDDING_COMPATIBILITY_AST', 'Known retrieval lock scope required')
    empty = scope.body[3]
    fail(isinstance(empty, ast.If) and dump(empty.test) == dump(ast.parse('not scope', mode='eval').body), 'EMBEDDING_COMPATIBILITY_AST', 'Known empty-scope check required')
    empty.body = [ast.Pass()]
    work = scope.body[4]
    fail(isinstance(work, ast.Try), 'EMBEDDING_COMPATIBILITY_AST', 'Known retrieval try scope required')
    cut = 5 if structural else 2
    fail(isinstance(work.body[-1], ast.Return), 'EMBEDDING_COMPATIBILITY_AST', 'Known final retrieval return required')
    # The predecessor whole source hash binds the removed output-only tail.
    # Everything through actual query encode/dense/BM25/RRF/rerank/order is equal.
    work.body = work.body[:-cut]
    return result


def launcher_projection(module, additions):
    result = copy.deepcopy(module)
    functions = {n.name: n for n in result.body if isinstance(n, ast.FunctionDef)}
    receipt = functions['write_receipt']
    code = next(n for n in receipt.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'code' for t in n.targets))
    names = list(ast.literal_eval(code.value.generators[0].iter))
    fail(all(names.count(name) == 1 for name in additions), 'EMBEDDING_COMPATIBILITY_AST', 'Exact launcher receipt additions required')
    code.value.generators[0].iter = ast.parse(repr(tuple(n for n in names if n not in additions)), mode='eval').body
    return result


def validate_proof(receipt, current_root):
    fail(receipt.get('protocol') == PROTOCOL and receipt.get('status') == 'explicit_source_equivalence_no_neural_calls', 'EMBEDDING_COMPATIBILITY_PROTOCOL', 'Known compatibility receipt required')
    fail(receipt['legacyProvenance']['sha256'] == LEGACY_PROVENANCE_SHA and receipt['structuralProvenance']['sha256'] == STRUCTURAL_PROVENANCE_SHA,
         'EMBEDDING_COMPATIBILITY_PREDECESSOR', 'Only the two approved immutable predecessors are permitted')
    legacy_provenance = read_json(receipt['legacyProvenance']); structural_provenance = read_json(receipt['structuralProvenance'])
    old, structural, new = receipt['legacyCore'], receipt['structuralCore'], receipt['currentCore']
    fail(set(old) == set(CORE_FILES) and set(structural) == {'window_runtime.py', 'launch_window_retrieval.py', 'retrieval_source_units.py'} and set(new) == set(CORE_FILES + ADDITIONS),
         'EMBEDDING_COMPATIBILITY_FILE_SET', 'Complete exact core file sets required')
    old_raw = {n: read_descriptor(v) for n, v in old.items()}; structural_raw = {n: read_descriptor(v) for n, v in structural.items()}
    new_raw = {n: read_descriptor(v) for n, v in new.items()}
    for n in new:
        fail(descriptor(Path(current_root) / n)['sha256'] == new[n]['sha256'], 'EMBEDDING_COMPATIBILITY_CURRENT_CORE', 'Receipt does not bind the currently executing core')
    frozen_legacy = {item['relativePath']: item['frozen'] for item in legacy_provenance['software']}
    for n in LEGACY_ALGORITHMS:
        fail(n in frozen_legacy and frozen_legacy[n]['sha256'] == old[n]['sha256'] and read_descriptor(frozen_legacy[n]) == old_raw[n],
             'EMBEDDING_COMPATIBILITY_PREDECESSOR', 'Legacy core differs from actual frozen predecessor')
    approved_structural = {Path(item['relativePath']).name: item['candidate'] for item in structural_provenance['files']}
    for n in structural:
        fail(approved_structural[n]['sha256'] == structural[n]['sha256'] and read_descriptor(approved_structural[n]) == structural_raw[n],
             'EMBEDDING_COMPATIBILITY_PREDECESSOR', 'Structural core differs from frozen 12-delta predecessor')
    exact = set(CORE_FILES) - {'window_runtime.py', 'launch_window_retrieval.py'}
    for n in exact:
        fail(old_raw[n] == new_raw[n], 'EMBEDDING_COMPATIBILITY_EMBEDDING_CHANGED', 'Embedding dependency bytes changed')
    fail(new_raw['retrieval_source_units.py'] == structural_raw['retrieval_source_units.py'], 'EMBEDDING_COMPATIBILITY_SELECTOR_CHANGED', 'Only the approved pure selector is permitted')
    legacy_tree, structural_tree, current_tree = map(tree, (old_raw['window_runtime.py'], structural_raw['window_runtime.py'], new_raw['window_runtime.py']))
    fail(loaded_names(legacy_tree) == LEGACY_ALGORITHMS and loaded_names(structural_tree) == LEGACY_ALGORITHMS + ('retrieval_source_units.py',) and loaded_names(current_tree) == LEGACY_ALGORITHMS + ADDITIONS,
         'EMBEDDING_COMPATIBILITY_AST', 'Exact loaded source sets required')
    added_imports = [n for n in current_tree.body if isinstance(n, ast.ImportFrom) and n.module == 'embedding_compatibility']
    expected_import = ast.parse('from embedding_compatibility import verified_embedding_algorithms').body[0]
    fail(len(added_imports) == 1 and dump(added_imports[0]) == dump(expected_import), 'EMBEDDING_COMPATIBILITY_AST', 'Exact compatibility helper import required')
    fail(dump(outer_projection(legacy_tree)) == dump(outer_projection(structural_tree)) == dump(outer_projection(current_tree)),
         'EMBEDDING_COMPATIBILITY_AST', 'Runtime imports/constants/module execution changed outside approved additions')
    a, b, c = map(methods, (legacy_tree, structural_tree, current_tree))
    fail(set(a) == set(b) and set(c) == set(b) | {'embedding_algorithms'}, 'EMBEDDING_COMPATIBILITY_AST', 'Unknown runtime method additions/removals')
    equal_methods = {}
    for name in a:
        if name in {'retrieve', 'retrieval_recipe'}:
            fail(dump(b[name]) == dump(c[name]), 'EMBEDDING_COMPATIBILITY_AST', 'Query selection differs from approved 12-delta')
        else:
            projected = normalize_calls(c[name]) if name in {'signature', 'embedding_cache_identity'} else c[name]
            fail(dump(a[name]) == dump(b[name]) == dump(projected), 'EMBEDDING_COMPATIBILITY_EMBEDDING_CHANGED', 'Embedding/runtime method AST changed: ' + name)
            equal_methods[name] = {'legacyAstSha256': sha(dump(a[name]).encode()), 'structuralAstSha256': sha(dump(b[name]).encode()),
                                   'currentProjectedAstSha256': sha(dump(projected).encode())}
    expected_method = ast.parse('class WindowRuntime:\n    def embedding_algorithms(self):\n        return verified_embedding_algorithms(Path(__file__).parent, self.algorithms())\n').body[0].body[0]
    fail(dump(c['embedding_algorithms']) == dump(expected_method), 'EMBEDDING_COMPATIBILITY_AST', 'Compatibility delegation differs')
    fail(dump(retrieve_embedding_prefix(a['retrieve'], False)) == dump(retrieve_embedding_prefix(b['retrieve'], True)),
         'EMBEDDING_COMPATIBILITY_EMBEDDING_CHANGED', 'Actual query encode/dense/BM25/RRF/rerank execution prefix changed')
    fail(dump(launcher_projection(tree(old_raw['launch_window_retrieval.py']), ())) == dump(launcher_projection(tree(structural_raw['launch_window_retrieval.py']), ('retrieval_source_units.py',))) == dump(launcher_projection(tree(new_raw['launch_window_retrieval.py']), ADDITIONS)),
         'EMBEDDING_COMPATIBILITY_EMBEDDING_CHANGED', 'Launcher math/model/settings/execution changed outside receipt additions')
    proof = {'equalRuntimeMethods': equal_methods, 'equalCoreDependencyFiles': sorted(exact),
             'queryNeuralAndCandidatePrefixAstEqual': True, 'launcherExecutionAstEqual': True,
             'legacyAlgorithmHashes': {n: old[n]['sha256'] for n in LEGACY_ALGORITHMS},
             'currentFullAlgorithmHashes': {n: new[n]['sha256'] for n in LEGACY_ALGORITHMS + ADDITIONS},
             'scope': 'Source equivalence only; corpus/model/tokenizer/window/runtime/math identities still bind normally; no vector/model validation in this receipt.'}
    if 'proof' in receipt:
        fail(receipt['proof'] == proof, 'EMBEDDING_COMPATIBILITY_PROOF', 'Stored proof differs from actual source checks')
    return proof


def verified_embedding_algorithms(current_root, current_algorithms, environ=None):
    environ = os.environ if environ is None else environ
    path, expected = environ.get(ENV_PATH), environ.get(ENV_SHA)
    fail(bool(path) == bool(expected), 'EMBEDDING_COMPATIBILITY_CONFIG', 'Compatibility path and external SHA must both be present or absent')
    if not path:
        return dict(current_algorithms)
    raw = Path(path).read_bytes()
    fail(sha(raw) == expected, 'EMBEDDING_COMPATIBILITY_RECEIPT_CHANGED', 'External compatibility receipt SHA differs')
    receipt = json.loads(raw, object_pairs_hook=unique_object)
    fail('proof' in receipt, 'EMBEDDING_COMPATIBILITY_PROOF', 'Final source equivalence proof required')
    proof = validate_proof(receipt, current_root)
    fail(current_algorithms == proof['currentFullAlgorithmHashes'], 'EMBEDDING_COMPATIBILITY_LOADED_CORE', 'Imported source hashes differ from receipt current core')
    return dict(proof['legacyAlgorithmHashes'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-core', required=True); parser.add_argument('--structural-core', required=True)
    parser.add_argument('--current-core', required=True); parser.add_argument('--legacy-provenance', required=True)
    parser.add_argument('--structural-provenance', required=True); parser.add_argument('--output', required=True)
    args = parser.parse_args(); output = Path(args.output)
    fail(not output.exists(), 'EMBEDDING_COMPATIBILITY_OUTPUT', 'Fresh receipt path required')
    value = {'protocol': PROTOCOL, 'status': 'explicit_source_equivalence_no_neural_calls',
             'legacyProvenance': descriptor(args.legacy_provenance), 'structuralProvenance': descriptor(args.structural_provenance),
             'legacyCore': {n: descriptor(Path(args.legacy_core) / n) for n in CORE_FILES},
             'structuralCore': {n: descriptor(Path(args.structural_core) / n) for n in ('window_runtime.py', 'launch_window_retrieval.py', 'retrieval_source_units.py')},
             'currentCore': {n: descriptor(Path(args.current_core) / n) for n in CORE_FILES + ADDITIONS},
             'actualCalls': {'neural': 0, 'tokenizer': 0, 'index': 0, 'query': 0, 'HTTP': 0, 'DB': 0}}
    value['proof'] = validate_proof(value, args.current_core)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print(json.dumps(descriptor(output)))


if __name__ == '__main__':
    main()
