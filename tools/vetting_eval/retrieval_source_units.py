"""Pure source-bound structural selection; no tokenizer/model/database imports.

Uses the existing token-window Part mapper and the source-unit prototype's
strict UTF16 overlap/no-gap rule. Only observed canonical ranges are complete;
source-block completeness, applicability and semantics remain unknown.
"""
import copy
import math
import re
from token_windows import digest, paragraph_map, utf16_length, WindowError

POLICY = 'unique-source-structural-unit-seeds-v1'
SCOPE_FIELDS = ('documentId', 'sourceHash', 'role', 'metadataVersion',
                'segmentationVersion', 'nativeTableMetadataVersion',
                'sourceQualityMetadataVersion', 'sourceQualityHash')


class SourceUnits:
    def __init__(self, parents):
        self.parents = {p['id']: p for p in parents}
        if len(self.parents) != len(parents):
            raise ValueError('duplicate_canonical_parent_id')
        self.docs, self.clauses, self.blocks, self.scopes = {}, {}, {}, {}
        self.cache = {}
        for p in parents:
            key = tuple(p.get(k) for k in SCOPE_FIELDS)
            self.scopes[p['id']] = key
            self.docs.setdefault(p.get('documentId'), set()).add(key)
            if p.get('clauseId'):
                self.clauses.setdefault((key, p['clauseId']), []).append(p)
            for part in p.get('parts', []):
                self.blocks.setdefault((key, part.get('blockId')), []).append((p, part))

    def source(self, p):
        key = self.scopes[p['id']]
        if (any(not isinstance(x, str) or not x.strip() for x in key)
                or not re.fullmatch('[0-9a-f]{64}', p['sourceHash'])
                or not re.fullmatch('[0-9a-f]{64}', p['sourceQualityHash'])
                or p['role'] not in ('tender', 'standard', 'project_fact', 'package_manifest')
                or not isinstance(p.get('sourceQuality'), dict)):
            raise ValueError('unknown_source_identity_quality_or_version')
        if len(self.docs[p['documentId']]) != 1:
            raise ValueError('ambiguous_document_source_identity')
        return key

    def part_map(self, p):
        paragraph_map(p)
        for part in p['parts']:
            if (not isinstance(part.get('blockId'), str) or not part['blockId']
                    or not isinstance(part.get('anchor'), str) or not part['anchor']
                    or part['startOffset'] < 0):
                raise ValueError('unknown_source_part_mapping')

    def block_range(self, key, block):
        rows = self.blocks[(key, block)]
        ordered = sorted(rows, key=lambda x: (x[1]['startOffset'], x[1]['endOffset'], x[0]['id']))
        cursor = 0
        for i, (p, part) in enumerate(ordered):
            self.part_map(p)
            a, b = part['startOffset'], part['endOffset']
            if a > cursor:
                raise ValueError('observed_block_start_or_gap_unknown')
            raw = part['text'].encode('utf-16-le')
            for _, prior in ordered[:i]:
                left, right = max(a, prior['startOffset']), min(b, prior['endOffset'])
                if left < right and raw[(left-a)*2:(right-a)*2] != prior['text'].encode('utf-16-le')[(left-prior['startOffset'])*2:(right-prior['startOffset'])*2]:
                    raise ValueError('overlapping_source_text_mismatch')
            cursor = max(cursor, b)
        return {'blockId': block, 'startUtf16': 0, 'endUtf16': cursor,
                'status': 'observed_canonical_ranges_zero_gap', 'sourceBlockComplete': None}

    def resolve(self, p):
        if p['id'] in self.cache:
            return copy.deepcopy(self.cache[p['id']])
        row = {'originIds': [p['id']], 'kind': 'unknown', 'status': 'unknown',
               'reason': None, 'requiredMemberIds': [], 'members': [], 'observedRanges': [],
               'sourceIdentity': {k: p.get(k) for k in SCOPE_FIELDS},
               'clauseId': p.get('clauseId'), 'headingLocation': p.get('clauseHeadingLocation'),
               'blockIds': [], 'derivedMembersCanBecomeOrigins': False,
               'memberScores': None, 'qualifiersComplete': 'unknown', 'semanticScopeVerified': False}
        try:
            key = self.source(p)
            self.part_map(p)
            if p.get('clauseId'):
                group = self.clauses[(key, p['clauseId'])]
                headings = {c.get('clauseHeadingLocation') for c in group}
                if len(headings) != 1 or not p.get('clauseHeadingLocation'):
                    raise ValueError('clause_heading_not_unique')
                # A physical heading must be present in the observed canonical
                # scope. A free-standing label alone cannot create ancestry.
                heading = p['clauseHeadingLocation']
                if not any(part['anchor'].split(' @')[0] == heading or part['anchor'].split(' @')[0].endswith(' · '+heading)
                           for c in group for part in c.get('parts', [])):
                    raise ValueError('heading_not_located_in_observed_parts')
                row['kind'] = 'exact_clause_heading'
                ids = {c['id'] for c in group}
                blocks = {part['blockId'] for c in group for part in c['parts']}
                if any(c['id'] not in ids for block in blocks for c, _ in self.blocks[(key, block)]):
                    raise ValueError('source_block_crosses_clause_unit_boundary')
            else:
                row['kind'] = 'observed_source_blocks'
                blocks = {part['blockId'] for part in p['parts']}
                ids = {c['id'] for block in blocks for c, _ in self.blocks[(key, block)]}
            for member in ids:
                c = self.parents[member]
                if self.source(c) != key or c['sourceQuality'] != p['sourceQuality']:
                    raise ValueError('source_quality_or_scope_differs')
                self.part_map(c)
            row['observedRanges'] = [self.block_range(key, block) for block in sorted(blocks)]
            row['blockIds'] = sorted(blocks)
            row['requiredMemberIds'] = sorted(ids)
            row['members'] = [copy.deepcopy(self.parents[id_]) for id_ in sorted(ids)]
            row['status'] = 'complete_observed_unit'
        except (ValueError, KeyError, TypeError, UnicodeError, WindowError) as error:
            row['reason'] = error.kind if isinstance(error, WindowError) else str(error)
            row['members'], row['requiredMemberIds'], row['observedRanges'] = [], [], []
        identity = {k: row[k] for k in ('kind', 'sourceIdentity', 'clauseId', 'headingLocation', 'blockIds', 'requiredMemberIds')}
        if row['status'] != 'complete_observed_unit':
            identity['fallbackSeedId'] = p['id']
        row['unitId'] = digest(identity)
        self.cache[p['id']] = copy.deepcopy(row)
        return row


def select_source_units(parents, reranked_hits, limit):
    """Keep only actually reranked seeds, with at most K reliable unique units.

    The complete rerank sequence must be supplied. Structure never assigns or
    changes scores. Derived members are looked up once from canonical parents;
    their text, references and other blocks are never followed as new origins.
    """
    catalog = SourceUnits(parents)
    selected, units, used, skipped = [], [], {}, []
    if type(limit) is not int or limit < 0 or len({h['id'] for h in reranked_hits}) != len(reranked_hits):
        raise ValueError('invalid_limit_or_duplicate_reranked_seed')
    for rank, hit in enumerate(reranked_hits, 1):
        if type(hit.get('score')) not in (int, float) or not math.isfinite(hit['score']):
            raise ValueError('reranked_seed_score_not_finite_number')
        if hit['id'] not in catalog.parents or hit['payload'] != catalog.parents[hit['id']]:
            raise ValueError('reranked_seed_canonical_payload_differs')
        unit = catalog.resolve(catalog.parents[hit['id']])
        if unit['unitId'] in used:
            skipped.append({'id': hit['id'], 'originalRerankOrdinal': rank, 'score': hit['score'],
                            'unitId': unit['unitId'], 'selectedSeedId': used[unit['unitId']]})
            continue
        if len(selected) >= limit:
            break
        used[unit['unitId']] = hit['id']
        selected.append(copy.deepcopy(hit))
        unit['originalRerankOrdinal'], unit['seedScore'] = rank, hit['score']
        units.append(unit)
    return selected, {'policy': POLICY, 'scope': 'canonical_observed_structure_only',
                      'originalHitIds': [h['id'] for h in selected], 'units': units,
                      'rawParentTopKIds': [h['id'] for h in reranked_hits[:limit]],
                      'skippedDuplicateUnitSeeds': skipped, 'derivedMembersCanBecomeOrigins': False,
                      'memberOrder': 'canonical_parent_id_lexical', 'qualifiersComplete': 'unknown',
                      'semanticScopeVerified': False}
