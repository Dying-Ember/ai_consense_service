"""Freeze saved normal text and observed structures for a development baseline.

No parser, OCR, model, database or network is used by this tool. Large raw/glyph
archives are retained by external descriptors. Quality acceptance remains false.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
from token_windows import paragraph_map, utf16_length, digest

VERSION = 'fixed-development-normal-source-parents-v1'

def descriptor(path, raw=None):
    path = Path(path).resolve()
    if raw is None:
        h = hashlib.sha256()
        with path.open('rb') as stream:
            for piece in iter(lambda: stream.read(1024 * 1024), b''): h.update(piece)
        return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': h.hexdigest()}
    return {'path': str(path), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}

def read_descriptor(desc):
    raw = Path(desc['path']).read_bytes()
    observed = descriptor(desc['path'], raw)
    if observed['sha256'] != desc['sha256'] or observed['bytes'] != desc['bytes']:
        raise ValueError('Saved normal input changed: ' + str(desc['path']))
    return json.loads(raw)

def jsonl_descriptor(desc):
    h = hashlib.sha256(); size = 0; rows = []
    with Path(desc['path']).open('rb') as stream:
        for line in stream:
            h.update(line); size += len(line)
            if line.strip(): rows.append(json.loads(line))
    if size != desc['bytes'] or h.hexdigest() != desc['sha256']:
        raise ValueError('Saved normal stream changed: ' + str(desc['path']))
    return rows

def write_json(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')
    return descriptor(path)

def part(block, original_page, namespace):
    text = block['text']
    return {'blockId': namespace + '/' + block['id'],
            'anchor': namespace + '/P' + str(original_page) + '/' + block['id'],
            'text': text, 'startOffset': 0, 'endOffset': utf16_length(text),
            'pageNo': original_page, 'derivedPageNo': block.get('pageNo'),
            'sourceBlockId': block['id'], 'bbox': block.get('bbox'),
            'coordinateSpace': 'normalized_rendered_crop_top_left',
            'source': block.get('source', 'native'),
            **({'nativeBlockIds':block['nativeBlockIds'], 'sourcePartBasis':block.get('sourcePartBasis')}
               if 'nativeBlockIds' in block else {})}

def split_parts(parts, max_chars=180000):
    result, current, length = [], [], 0
    for value in parts:
        if len(value['text']) > max_chars:
            raise ValueError('One observed block exceeds supported full-parent budget')
        added = len(value['text']) + bool(current)
        if current and length + added > max_chars:
            result.append(current); current, length = [], 0
        current.append(value); length += added
    if current: result.append(current)
    return result

def quality(family, coverage, extra=None):
    return {'status': 'development_baseline_known_gaps_retained',
            'finalQualityAccepted': False, 'humanConfirmed': False,
            'legalScopeAccepted': False, 'semanticScopeVerified': False,
            'normalCoverage': coverage, 'family': family,
            'knownGaps': (['deep hierarchy and legal applicability not fully source-verified'] if family == 'GCC' else
                ['this exact version has finite text/clause checks; legal predicates and replacement versions unverified'] if family == 'GCT' else
                ['native read order, complex/borderless tables, formula/figure grouping and legal ownership not fully source-verified',
                 'failed/OCR-required pages remain visible in coverage; no fabricated text']),
            **(extra or {})}

def parents_from_groups(groups, blocks, nodes, source, name, family, page_map,
                        source_quality, artifact_refs, corpus, coverage_rows):
    node_map = {n.get('id', n.get('nodeId')): n for n in nodes}
    sha = source['sha256']; source_hash = digest({'source': sha, 'name': name, 'version': VERSION})
    document_id = family + ':' + name
    qhash = digest(source_quality)
    seen = Counter(); generated = 0; linked = 0; empty = 0
    for group_key, group in groups.items():
        node_id, role, page_scope = group_key
        node = node_map.get(node_id, {})
        parts = []
        for block in group:
            seen[block['id']] += 1
            if not block['text'].strip(): empty += 1; continue
            parts.append(part(block, page_map[block['pageNo'] - 1], name))
        if not parts: continue
        is_clause = node.get('kind') == 'clause' and role in ('body', 'heading', 'body_interval_observation')
        heading_id = node.get('anchorSpanId')
        heading = next((p['anchor'] for p in parts if p['sourceBlockId'] == heading_id), None)
        label = node.get('labelRaw', node.get('clauseId'))
        for ordinal, section in enumerate(split_parts(parts), 1):
            content = '\n'.join(p['text'] for p in section)
            row = {'id': 'fixed:' + digest({'document': document_id, 'node': node_id, 'role': role, 'page': page_scope, 'ordinal': ordinal}),
                'content': content, 'parts': section, 'role': 'standard',
                'documentId': document_id, 'fileKey': name, 'pageNo': section[0]['pageNo'],
                'anchor': section[0]['anchor'], 'sourceHash': source_hash,
                'originalSourceSha256': sha, 'sourcePath': source['path'],
                'metadataVersion': VERSION, 'segmentationVersion': VERSION,
                'nativeTableMetadataVersion': 'source-located-external-table-observations-v1',
                'sourceQualityMetadataVersion': 'development-known-gaps-v1',
                'sourceQualityHash': qhash, 'sourceQuality': source_quality,
                'observedNodeId': node_id, 'observedParentId': node.get('parentId'),
                'observedNodeKind': node.get('kind', 'unknown'), 'observedSourceRole': role,
                'observedLabelRaw': label, 'sourceArtifactReferences': artifact_refs,
                'sourceBlockComplete': None, 'qualifiersComplete': 'unknown',
                'semanticScopeVerified': False, 'legalScopeAccepted': False,
                'fixedDevelopmentBaseline': True, 'qualityAdopted': False,
                'producerKind': 'saved_normal_development_baseline',
                'fullObservedParentPreserved': True, 'parentPartOrdinal': ordinal,
                'originalPageNos': list(dict.fromkeys(p['pageNo'] for p in section))}
            if is_clause and label and heading:
                row.update(clauseId=label, clauseHeadingLocation=heading,
                           observedClauseGroupId=node_id)
                linked += 1
            paragraph_map(row)
            corpus.append(row); generated += 1
    if set(seen) != {b['id'] for b in blocks} or any(n != 1 for n in seen.values()):
        raise ValueError('Normal source blocks not preserved exactly once: ' + name)
    coverage_rows.append({'family': family, 'name': name, 'normalPages': len(page_map),
        'originalPages': page_map, 'blocks': len(blocks), 'wholeBlocksAccountedOnce': True,
        'emptyBlocksRetainedInNormalArtifact': empty, 'parents': generated,
        'observedClauseParents': linked, 'sourceQuality': source_quality,
        'sourceArtifacts': artifact_refs})

def normal_groups(structure, blocks, gcc_latest=None):
    assignments = {a.get('spanId', a.get('normalBlockId')): a for a in structure['spanAssignments']}
    if gcc_latest:
        assignments = {a['normalBlockId']: {'nodeId': a['observedParentId'],
            'role': a['sourceRolePreserved']} for a in gcc_latest['bodyAssignments']}
    groups = defaultdict(list)
    for block in blocks:
        assigned = assignments.get(block['id'])
        if assigned is None: raise ValueError('Saved block lacks declared structure assignment: ' + block['id'])
        role = assigned.get('role', 'unknown')
        node_id = assigned.get('nodeId')
        # Auxiliary layout rows retain their own physical page container.
        aux = role in ('header', 'footer', 'separator', 'content', 'unknown')
        key = (node_id, role if aux else 'body', block['pageNo'] if aux else None)
        groups[key].append(block)
    return groups

def build(args):
    plan = json.loads(Path(args.plan).read_bytes()); out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    corpus, coverage_rows, refs = [], [], []
    # GCC: normal rows plus the latest complete reversible printed-tree sidecar.
    gcc_structure = read_descriptor(plan['gccStructure']); latest = read_descriptor(plan['gccLatestStructure'])
    blocks = jsonl_descriptor(plan['gccBlocks']); pages = jsonl_descriptor(plan['gccPages'])
    gcc_quality = quality('GCC', gcc_structure['rawNormalInputCoveragePreserved'])
    parents_from_groups(normal_groups(gcc_structure, blocks, latest), blocks, gcc_structure['nodes'],
        plan['gccSource'], 'GCC', 'GCC', list(range(1, 75)), gcc_quality,
        {'normalBlocks': plan['gccBlocks'], 'normalPages': plan['gccPages'],
         'structure': plan['gccStructure'], 'printedTree': plan['gccLatestStructure']}, corpus, coverage_rows)
    # GCT: intact eleven source roots and all declared descendants / parent tails.
    gct = read_descriptor(plan['gctGroups']); groups = defaultdict(list); gctblocks = []
    gctnodes = []
    for group in gct['completeSourceClauseAndRelationWindows']['sourceClauseGroups']:
        root_id = group['rootNodeId']; root = next(n for n in gct['nodes'] if n['nodeId'] == root_id)
        gn = {'id': root_id, 'kind': 'clause', 'labelRaw': group['rootClauseIdObserved'],
              'anchorSpanId': root['lines'][0]['blockIds'][0], 'parentId': None}
        gctnodes.append(gn)
        for unit in group['sourceBodyUnits']:
            normal_node=next(n for n in gct['nodes'] if n['nodeId']==unit['nodeId'])
            for native_line in normal_node['lines']:
                # The saved normal reading line is a literal parser artifact.
                # Multi-block layout joins retain their actual native IDs and
                # disclose that offsets address the normal reading-line view.
                block={'id':native_line['blockIds'][0] if len(native_line['blockIds'])==1 else
                       'saved-reading-line:'+digest(native_line['blockIds']),
                       'text':native_line['text'],'pageNo':native_line['pageNo'],
                       'bbox':native_line.get('bbox'),'nativeBlockIds':native_line['blockIds'],
                       'source':'saved_normal_native_reading',
                       'sourcePartBasis':'exact_saved_normal_reading_line; raw native operators externally retained'}
                groups[(root_id,'body',None)].append(block);gctblocks.append(block)
    for ordinal, line in enumerate(gct['metadataLines']):
        block = {'id': 'metadata:' + str(ordinal), 'text': line['text'], 'pageNo': line['pageNo'], 'bbox': line.get('bbox')}
        groups[(None, 'metadata', line['pageNo'])].append(block); gctblocks.append(block)
    for note in gct['sideNoteRelations']:
        if note.get('displayText', '').strip():
            block = {'id': 'note:' + note['fromFieldId'], 'text': note['displayText'], 'pageNo': note['physicalPageNo']}
            groups[(None, 'explicit_note', note['physicalPageNo'])].append(block); gctblocks.append(block)
    parents_from_groups(groups, gctblocks, gctnodes, plan['gctSource'], 'GCT', 'GCT', [1, 2],
        quality('GCT', {'totalPages': 2, 'complete': True, 'parseStatus': 'PARSED'},
                {'finiteSourceChecks': '27 body nodes / 11 complete roots; legal predicates and version generalization not accepted'}),
        {'structureAndCompleteGroups': plan['gctGroups']}, corpus, coverage_rows)
    all_profiles = plan['slProfiles'] + plan['slOldNine']; page_inventory = []
    if len(all_profiles) != plan['expectedSLUnits']: raise ValueError('SL full input count differs')
    for ordinal, profile in enumerate(all_profiles, 1):
        structure = read_descriptor(profile['structure'])
        if 'normalRoot' in profile:
            normal_root = Path(profile['normalRoot']); summary = json.loads((normal_root/'summary.json').read_bytes())
            bd = descriptor(normal_root/'blocks.jsonl'); pd = descriptor(normal_root/'pages.jsonl')
            blocks = jsonl_descriptor(bd); pages = jsonl_descriptor(pd)
            sd = descriptor(normal_root/'summary.json')
        else:
            bd, pd, sd = profile['normalBlockEvents'], profile['normalPageEvents'], profile['normalViewSummary']
            blocks = jsonl_descriptor(bd); pages = jsonl_descriptor(pd); summary = read_descriptor(sd)
        page_map = profile['originalPhysicalPages']; page_inventory.extend(page_map)
        if len(pages) != len(page_map): raise ValueError('Complete normal page inventory differs: ' + profile['name'])
        if structure['sourceSha256'] != profile['sourceSha256']: raise ValueError('SL structure/source identity differs')
        sq = quality('SL', summary['coverage'], {'normalParseStatus': summary['parseStatus'],
             'worksection': profile['worksectionRaw'], 'inputUnitRole': profile['inputUnitRole']})
        artifact_refs = {'normalBlocks': bd, 'normalPages': pd, 'normalSummary': sd,
                         'structure': profile['structure'], 'rawArchive': summary.get('rawArchive'),
                         'imageRouting': summary.get('imageRouting')}
        parents_from_groups(normal_groups(structure, blocks), blocks, structure['nodes'],
            plan['slParent'], profile['name'], 'SL', page_map, sq, artifact_refs, corpus, coverage_rows)
        if ordinal % 20 == 0: print(json.dumps({'phase':'cpu_corpus','SLUnits':ordinal,'parents':len(corpus)}), flush=True)
    if len(page_inventory) != plan['expectedSLPages'] or len(set(page_inventory)) != len(page_inventory):
        raise ValueError('SL required page inventory overlap/missing')
    if sorted(page_inventory) != list(range(1127, 4301)):
        raise ValueError('SL required technical physical range differs')
    if len({p['id'] for p in corpus}) != len(corpus): raise ValueError('Duplicate parent identity')
    cp = out/'corpus.jsonl'
    with cp.open('x', encoding='utf-8', newline='\n') as stream:
        for row in corpus: stream.write(json.dumps(row, ensure_ascii=False, separators=(',', ':'))+'\n')
    manifest = {'schema': 'consense.fixed-development-baseline-corpus.v1', 'createdAtUtc': datetime.now(timezone.utc).isoformat(),
        'projectId': plan['projectId'], 'corpus': descriptor(cp), 'parentCount': len(corpus),
        'textCharacters': sum(len(c['content']) for c in corpus), 'inputUnits': len(coverage_rows),
        'SLInputUnits': len(all_profiles), 'SLRequiredPages': len(page_inventory),
        'totalRequiredPages': len(page_inventory)+74+2,
        'familyParents': dict(Counter(c['sourceQuality']['family'] for c in corpus)),
        'sourceCoverage': coverage_rows, 'inputPlan': descriptor(args.plan), 'models': plan['models'],
        'tableProjectionInputs': plan.get('tableProjectionInputs', []),
        'finalQualityAccepted': False, 'productionAdopted': False, 'developmentFreezeAuthorized': True,
        'excluded': plan['excluded'], 'largeRawExpandedOrRead': False,
        'parserOCRModelDatabaseNetworkCalls': 0, 'knownGapsRetained': True,
        'code': descriptor(__file__)}
    md = write_json(out/'corpus-manifest.json', manifest)
    print(json.dumps({'manifest':md,'parents':len(corpus),'chars':manifest['textCharacters'],
        'SLUnits':len(all_profiles),'SLPages':len(page_inventory),'totalRequiredPages':manifest['totalRequiredPages'],
        'familyParents':manifest['familyParents'],'actualModelGPUCalls':0}), flush=True)

def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--plan', required=True); p.add_argument('--out', required=True)
    build(p.parse_args())

if __name__ == '__main__': main()
