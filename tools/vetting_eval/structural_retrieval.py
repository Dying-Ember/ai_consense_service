"""Source-bound dual-granularity planning and same-pool rerank comparison.

The caller supplies canonical chunks in their observed source order and real
tokenizer/scorer adapters. No model, index, labels, or network are created here.
Only SourceUnits' verified observed ranges may produce a longer context. Unknown
structure remains explicit; it never becomes guessed clause/table ancestry.
"""
from __future__ import annotations

import copy
import math
from numbers import Real
from dataclasses import asdict, dataclass

from retrieval_source_units import SourceUnits
from token_windows import (WindowRecipe, digest, fail, stable_parent_payload_sha,
                           text_sha, utf16_length, windows)

VERSION = 'observed-structure-dual-granularity-typed-parent-shared-note-context-v4'
CONTRACT = 'source-bound-dual-granularity-structure-results-v2'
COMBINED_CONTEXT_POLICY = 'independent-observed-hierarchy-and-direct-shared-note-union-v2'


@dataclass(frozen=True)
class StructuralRecipe:
    mode: str = 'windows-v1'
    long_embedding_tokens: int = 2048
    context_rerank_tokens: int = 2048
    strict_context: bool = True
    version: str = VERSION

    def __post_init__(self):
        fail(self.mode in {'windows-v1', 'dual-structure-v1'}, 'STRUCTURAL_RECIPE', 'Unknown retrieval mode')
        for value in (self.long_embedding_tokens, self.context_rerank_tokens):
            fail(type(value) is int and 4 <= value <= 8192, 'STRUCTURAL_RECIPE', 'Explicit structure budgets must be 4..8192 tokens')
        fail(type(self.strict_context) is bool, 'STRUCTURAL_RECIPE', 'strict_context must be boolean')

    @classmethod
    def from_environment(cls, environ):
        mode = environ.get('CONSENSE_RETRIEVAL_GRANULARITY', 'windows-v1')
        values = []
        for name in ('CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS', 'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS'):
            try:
                values.append(int(environ.get(name, '2048')))
            except (ValueError, TypeError) as error:
                fail(False, 'STRUCTURAL_RECIPE', name + ' must be an integer', cause=str(error))
        strict = environ.get('CONSENSE_STRUCTURAL_RERANK_STRICT', 'true')
        fail(strict in {'true', 'false'}, 'STRUCTURAL_RECIPE', 'CONSENSE_STRUCTURAL_RERANK_STRICT must be true or false')
        return cls(mode, *values, strict == 'true')

    @property
    def enabled(self):
        return self.mode == 'dual-structure-v1'

    def identity(self):
        return asdict(self)


def _catalog(parents):
    """Reassemble exact overlapping source ranges, never concatenate duplicates."""
    catalog = SourceUnits(parents)
    units, bindings = {}, {}
    source_order = {}
    for parent in parents:
        for part in parent.get('parts', []):
            source_order.setdefault((parent.get('documentId'), parent.get('sourceHash'),
                                     part.get('blockId')), len(source_order))
    for parent in parents:
        resolved = catalog.resolve(parent)
        binding = {'unitId': resolved['unitId'], 'status': resolved['status'], 'reason': resolved['reason']}
        hierarchy = resolved.get('hierarchyContext')
        attachment = resolved.get('attachmentContext')
        context_id = resolved['unitId']
        selected_members, selected_blocks, selected_ranges = resolved['members'], resolved['blockIds'], resolved['observedRanges']
        if hierarchy is not None:
            context_id = hierarchy['contextId']
            binding['contextId'] = context_id
            binding['hierarchyContextPolicy'] = hierarchy['policy']
            if hierarchy['status'] != 'complete_observed_hierarchy_context':
                binding.update(status='unknown', reason=hierarchy['reason'])
            else:
                selected_members, selected_blocks, selected_ranges = hierarchy['members'], hierarchy['blockIds'], hierarchy['observedRanges']
        if attachment is not None:
            binding['attachmentContextPolicy'] = attachment['policy']
            binding['attachmentContextId'] = attachment['contextId']
            context_id = attachment['contextId']
            if attachment['status'] != 'complete_observed_attachment_context':
                binding.update(status='unknown', reason=attachment['reason'])
            elif binding['status'] == 'complete_observed_unit':
                # These two independently validated contexts are a direct
                # union. Attachment roots are only the observed base; neither
                # hierarchy descendants nor derived targets nominate new notes.
                combined = {p['id']: p for p in selected_members + attachment['members']}
                selected_members = [combined[i] for i in sorted(combined, key=catalog.source_order.__getitem__)]
                selected_blocks = sorted({part['blockId'] for p in selected_members for part in p['parts']})
                selected_ranges = [catalog.block_range(catalog.source(parent), b) for b in selected_blocks]
                if hierarchy is not None:
                    context_id = digest({'policy': COMBINED_CONTEXT_POLICY, 'observedUnitId': resolved['unitId'],
                        'hierarchyContextId': hierarchy['contextId'], 'attachmentContextId': attachment['contextId'],
                        'memberPayloadSha256': [{'id': p['id'], 'sha256': digest(p)} for p in selected_members]})
                    binding['combinedContextPolicy'] = COMBINED_CONTEXT_POLICY
            binding['contextId'] = context_id
        bindings[parent['id']] = binding
        if binding['status'] != 'complete_observed_unit' or context_id in units:
            continue
        members = {p['id']: p for p in selected_members}
        blocks = {}
        for member in members.values():
            for part in member['parts']:
                if part['blockId'] not in selected_blocks:
                    continue
                blocks.setdefault(part['blockId'], []).append((member, part))
        parts, occurrences = [], []
        ordered_blocks = sorted(blocks, key=lambda block: source_order[(parent['documentId'], parent['sourceHash'], block)])
        for block in ordered_blocks:
            slices = sorted(blocks[block], key=lambda row: (row[1]['startOffset'], row[1]['endOffset'], row[0]['id']))
            cursor, raw = 0, bytearray()
            for member, part in slices:
                a, b = part['startOffset'], part['endOffset']
                encoded = part['text'].encode('utf-16-le')
                fail(a <= cursor, 'STRUCTURAL_SOURCE_GAP', 'Observed structural block has a source gap', blockId=block)
                if b > cursor:
                    raw.extend(encoded[(cursor - a) * 2:])
                    cursor = b
                occurrences.append({'parentId': member['id'], 'parentPayloadSha256': stable_parent_payload_sha(member),
                                    'part': copy.deepcopy(part)})
            try:
                text = raw.decode('utf-16-le')
            except UnicodeError as error:
                fail(False, 'STRUCTURAL_UTF16', 'Source range splits a Unicode character', cause=str(error))
            # Existing metadata describes one observed original block. Only its
            # text/range are reconstructed; native cells remain evidence, never
            # independently combined or rewritten.
            full = copy.deepcopy(slices[0][1])
            full.update(text=text, startOffset=0, endOffset=utf16_length(text))
            parts.append(full)
        context = {k: copy.deepcopy(v) for k, v in parent.items()
                   if k not in {'id', 'content', 'parts', 'cells', 'pageNo', 'anchor'}}
        context.update(id='structure:' + context_id, content='\n'.join(p['text'] for p in parts), parts=parts,
                       structuralUnit={'unitId': context_id, 'kind': resolved['kind'],
                                       'requiredMemberIds': list(members),
                                       'observedRanges': selected_ranges, 'originalPartOccurrences': occurrences,
                                       'sourceOrderBasis': 'first_observed_part_occurrence_in_canonical_input',
                                       'sourceBlockComplete': None, 'qualifiersComplete': 'unknown'})
        if hierarchy is not None:
            context['structuralUnit'].update(observedUnitId=resolved['unitId'], hierarchyContextPolicy=hierarchy['policy'],
                hierarchyContextId=hierarchy['contextId'], hierarchyEdgeIds=[e['edgeId'] for e in hierarchy['edgeDeclarations']],
                semanticScopeVerified=False, legalClosureVerified=False)
        if attachment is not None:
            context['structuralUnit'].update(observedUnitId=resolved['unitId'], attachmentContextPolicy=attachment['policy'],
                attachmentContextId=attachment['contextId'], attachmentEdgeIds=[e['edgeId'] for e in attachment['edgeDeclarations']],
                semanticScopeVerified=False, legalClosureVerified=False)
            if hierarchy is not None:
                context['structuralUnit']['combinedContextPolicy'] = COMBINED_CONTEXT_POLICY
        units[context_id] = context
    return units, bindings


def _point_origins(row, members):
    overlaps = {}
    for parent in members:
        total = 0
        for part in parent['parts']:
            for span in row['sourceSpans']:
                if part['blockId'] == span['blockId']:
                    total += max(0, min(part['endOffset'], span['sourceEndUtf16']) -
                                 max(part['startOffset'], span['sourceStartUtf16']))
        if total:
            overlaps[parent['id']] = total
    fail(overlaps, 'STRUCTURAL_ORIGIN_MISSING', 'Structural point has no exact original parent overlap')
    # This is a source overlap mapping, not a relevance or truth score.
    return sorted(overlaps, key=lambda key: (-overlaps[key], key))


def plan(parents, tokenizer, tokenizer_identity, short_recipe, recipe):
    """Return both lanes and exact contexts; unknown units retain only short lane."""
    fail(recipe.enabled, 'STRUCTURAL_MODE_REQUIRED', 'Dual planning requires explicit dual-structure-v1')
    fail(recipe.long_embedding_tokens >= short_recipe.max_tokens, 'STRUCTURAL_RECIPE', 'Long embedding cap must cover the short cap')
    units, bindings = _catalog(parents)
    by_id = {p['id']: p for p in parents}
    points = []
    for parent in parents:
        binding = bindings[parent['id']]
        for row in windows(parent, tokenizer, short_recipe, tokenizer_identity, embedding_strip=True):
            points.append({**row, 'granularity': 'short', 'retrievalSeedParentId': parent['id'],
                           'originParentIds': [parent['id']], 'structureBinding': binding})
    longer = WindowRecipe(recipe.long_embedding_tokens, short_recipe.overlap_tokens,
                          short_recipe.max_windows_per_parent)
    for unit_id, context in units.items():
        members = [by_id[p] for p in context['structuralUnit']['requiredMemberIds']]
        for row in windows(context, tokenizer, longer, tokenizer_identity,
                           embedding_strip=True, structural_boundaries=True):
            origins = _point_origins(row, members)
            points.append({**row, 'granularity': 'structure', 'retrievalSeedParentId': origins[0],
                           'originParentIds': origins, 'structureUnitId': unit_id})
    fail(len({p['id'] for p in points}) == len(points), 'WINDOW_ID_COLLISION', 'Dual points require unique identities')
    unavailable = [{'parentId': p['id'], **bindings[p['id']]} for p in parents
                   if bindings[p['id']]['status'] != 'complete_observed_unit']
    return {'points': points, 'contexts': list(units.values()), 'bindings': bindings,
            'coverage': {'originalParents': len(parents), 'shortCoveredParents': len(parents),
                         'structuralUnits': len(units), 'contextUnavailable': unavailable,
                         'allOriginalTextCoveredByShortLane': True, 'structuralCompleteness': 'observed_ranges_only',
                         'semanticScopeVerified': False}, 'recipe': recipe.identity()}


def aggregate_points(point_scores, points_by_id, parents_by_id):
    """Map actual dual point scores to original overlapping retrieval seeds."""
    best = {}
    for identity, raw_score in point_scores:
        fail(identity in points_by_id, 'UNKNOWN_WINDOW_ID', 'Dense point is outside the source-bound plan')
        point = points_by_id[identity]
        seed = point['retrievalSeedParentId']
        fail(seed in parents_by_id and seed in point['originParentIds'], 'STRUCTURAL_ORIGIN_MISSING',
             'Dense point seed must be an original overlapping parent')
        parent = parents_by_id[seed]
        fail(all(point.get(k) == parent.get(k) for k in ('documentId', 'sourceHash', 'role')),
             'WINDOW_SOURCE_PAYLOAD_MISMATCH', 'Dense point crosses its original source identity')
        score = float(raw_score)
        fail(math.isfinite(score), 'NONFINITE_SCORE', 'Dense model score must be finite')
        if seed not in best or score > best[seed]['score']:
            best[seed] = {'id': seed, 'score': score, 'winningWindowId': identity,
                          'granularity': point['granularity'], 'payload': parent}
    return sorted(best.values(), key=lambda row: (-row['score'], row['id']))


def compare(query, candidates, all_parents, tokenizer, tokenizer_identity, short_recipe, recipe, scorer):
    """Score frozen candidates twice with exact input/score receipts.

    scorer receives the actual ordered query/passage pairs. No candidate is
    added, removed, relabelled or assigned a manufactured model score. In strict
    mode a context gap fails before either inference stage.
    """
    fail(recipe.enabled, 'STRUCTURAL_MODE_REQUIRED', 'Structure comparison requires explicit mode')
    fail(recipe.context_rerank_tokens >= short_recipe.max_tokens, 'STRUCTURAL_RECIPE', 'Context rerank cap must cover baseline cap')
    by_id = {p['id']: p for p in all_parents}
    fail(len(by_id) == len(all_parents) and len({p['id'] for p in candidates}) == len(candidates),
         'DUPLICATE_CANDIDATE', 'Canonical parents and frozen candidate pool must have unique IDs')
    fail(all(p['id'] in by_id and p == by_id[p['id']] for p in candidates),
         'CANDIDATE_PAYLOAD_MISMATCH', 'Frozen candidate differs from canonical source')
    units, bindings = _catalog(all_parents)
    unavailable = [{'parentId': p['id'], **bindings[p['id']]} for p in candidates
                   if bindings[p['id']]['status'] != 'complete_observed_unit']
    fail(not recipe.strict_context or not unavailable, 'STRUCTURAL_CONTEXT_UNAVAILABLE',
         'Strict structure rerank requires verified observed context for every candidate', contextUnavailable=unavailable)
    pool = [{'id': p['id'], 'documentId': p['documentId'], 'role': p['role'],
             'sourceHash': p['sourceHash'], 'payloadSha256': stable_parent_payload_sha(p)} for p in candidates]
    baseline_rows = [w for p in candidates for w in windows(p, tokenizer, short_recipe, tokenizer_identity, query=query)]
    longer = WindowRecipe(recipe.context_rerank_tokens, short_recipe.overlap_tokens, short_recipe.max_windows_per_parent)
    selected_units = list(dict.fromkeys(bindings[p['id']].get('contextId', bindings[p['id']]['unitId']) for p in candidates
                                       if bindings[p['id']]['status'] == 'complete_observed_unit'))
    context_rows = [w for unit in selected_units for w in windows(units[unit], tokenizer, longer,
                    tokenizer_identity, query=query, structural_boundaries=True)]

    def score(rows, stage):
        if not rows:
            return {}, {'stage': stage, 'pairs': [], 'actualScores': [], 'aggregation': 'max'}
        actual_pairs = [(query, w['content']) for w in rows]
        values = list(scorer(actual_pairs))
        fail(len(values) == len(rows), 'RERANK_SCORE_COUNT', 'Actual score count differs from actual pair count', stage=stage)
        normalized = []
        for raw in values:
            fail(isinstance(raw, Real) and not isinstance(raw, bool), 'NONFINITE_SCORE',
                 'Actual relevance score must be a real number, not boolean or text')
            try:
                number = float(raw)
            except (TypeError, ValueError) as error:
                fail(False, 'NONFINITE_SCORE', 'Actual score must be numeric', cause=str(error))
            fail(math.isfinite(number), 'NONFINITE_SCORE', 'Actual model score must be finite')
            normalized.append(number)
        best = {}
        for row, value in zip(rows, normalized):
            if row['parentId'] not in best or value > best[row['parentId']]['score']:
                best[row['parentId']] = {'score': value, 'winningWindowId': row['id']}
        return best, {'stage': stage, 'pairs': rows, 'actualScores': normalized, 'aggregation': 'max',
                      'actualPairsSha256': digest(actual_pairs), 'pairWindowManifestSha256': digest(rows)}

    baseline, baseline_evidence = score(baseline_rows, 'baseline_short_window')
    structured, structure_evidence = score(context_rows, 'observed_structure_context')
    results = []
    for parent in candidates:
        key = parent['id']
        binding = bindings[key]
        context = structured.get('structure:' + binding.get('contextId', binding['unitId']))
        results.append({'id': key, 'baselineScore': baseline[key]['score'],
                        'score': context['score'] if context else baseline[key]['score'],
                        'contextScore': context['score'] if context else None,
                        'baselineWinningWindowId': baseline[key]['winningWindowId'],
                        'contextWinningWindowId': context['winningWindowId'] if context else None,
                        'structureBinding': binding,
                        'treatment': 'observed_structure_context' if context else 'explicit_baseline_context_unavailable'})
    return {'results': results, 'querySha256': text_sha(query), 'candidatePool': pool,
            'candidatePoolSha256': digest(pool), 'sameQueryAndCandidatePool': True,
            'baseline': baseline_evidence, 'structure': structure_evidence,
            'contextUnavailable': unavailable, 'recipe': recipe.identity(),
            'scoreMeaning': 'Actual relevance model scores; max over source-bound groups. Neither evidence validity nor defect probability.',
            'qualityImprovementEstablished': False}
