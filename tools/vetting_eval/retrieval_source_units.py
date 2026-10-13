"""Pure source-bound structural selection; no tokenizer/model/database imports.

Uses the existing token-window Part mapper and the source-unit prototype's
strict UTF16 overlap/no-gap rule. Only observed canonical ranges are complete;
source-block completeness, applicability and semantics remain unknown.
"""
import copy
import math
import re
from collections import defaultdict
from token_windows import digest, paragraph_map, utf16_length, WindowError

OBSERVED_POLICY = 'unique-source-structural-unit-seeds-v1'
POLICY = 'unique-source-structural-unit-seeds-typed-parent-shared-note-context-v4'
HIERARCHY_POLICY = 'same-source-declared-typed-parent-descendant-context-v1'
ATTACHMENT_POLICY = 'same-source-reviewed-direct-shared-note-star-context-v2'
SCOPE_FIELDS = ('documentId', 'sourceHash', 'role', 'metadataVersion',
                'segmentationVersion', 'nativeTableMetadataVersion',
                'sourceQualityMetadataVersion', 'sourceQualityHash')
HIERARCHY_SCOPE_FIELDS = (*SCOPE_FIELDS, 'originalSourceSha256', 'projectionRevisionSha256',
                          'sourceRevisionSha256', 'scopeSha256')
ATTACHMENT_SCOPE_FIELDS = (*SCOPE_FIELDS, 'originalSourceSha256', 'projectionRevisionSha256',
                           'sourceRevisionSha256', 'sourceSha256', 'scopeSha256', 'producerKind')


class SourceUnits:
    def __init__(self, parents):
        self.parents = {p['id']: p for p in parents}
        if len(self.parents) != len(parents):
            raise ValueError('duplicate_canonical_parent_id')
        self.docs, self.clauses, self.blocks, self.scopes = {}, {}, {}, {}
        self.cache = {}
        self.source_order = {p['id']: i for i, p in enumerate(parents)}
        self.hierarchy_cache = {}
        self.hierarchy_graph = None
        self.attachment_cache = {}
        self.attachment_graph = None
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

    def _resolve_observed(self, p):
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

    @staticmethod
    def hierarchy_scope(p):
        return tuple(p.get(k) for k in HIERARCHY_SCOPE_FIELDS)

    def _build_hierarchy(self):
        """Check declared whole-unit edges; never infer ancestry from labels."""
        nodes = defaultdict(list)
        for p in self.parents.values():
            if p.get('producerKind') in ('native_pdf', 'native_pdf_source_family'):
                nodes[(self.hierarchy_scope(p), p.get('unitId'))].append(p)
        scope_errors, node_errors, active_scopes = {}, {}, set()
        edges, children, incoming, associated = {}, defaultdict(dict), defaultdict(set), set()

        def invalid(key, reason, identities=()):
            identities = set(identities)
            if identities:
                associated.update(identities)
                for identity in identities:
                    node_errors.setdefault(identity, reason)
            else:
                # An unlocatable declaration cannot be attributed to a branch.
                scope_errors.setdefault(key, reason)

        for owner in self.parents.values():
            if owner.get('producerKind') not in ('native_pdf', 'native_pdf_source_family'):
                continue
            key = self.hierarchy_scope(owner)
            for relation in owner.get('relations', []):
                if relation.get('type') != 'parent' or relation.get('scope') != 'between_whole_units':
                    continue  # Internal parent self-edges are not whole-unit cycles.
                active_scopes.add(key)
                child_nodes = nodes.get((key, relation.get('fromUnitId')), [])
                parent_nodes = nodes.get((key, relation.get('toUnitId')), [])
                boundary_ids = {p['id'] for p in child_nodes + parent_nodes}
                # Explicit endpoint/region ownership can bound a malformed
                # declaration even when its other endpoint is unavailable.
                owner_regions = owner.get('adoption', {}).get('regionIds', [])
                if (owner.get('unitId') in (relation.get('fromUnitId'), relation.get('toUnitId'))
                        or any(relation.get(field) in owner_regions for field in ('fromRegionId','toRegionId'))):
                    boundary_ids.add(owner['id'])
                if (any(not isinstance(owner.get(field), str) or not re.fullmatch('[0-9a-f]{64}', owner[field])
                        for field in HIERARCHY_SCOPE_FIELDS[len(SCOPE_FIELDS):])
                        or relation.get('status') != 'source_reviewed_declared'
                        or type(relation.get('humanConfirmed')) is not bool
                        or any(not isinstance(relation.get(field), str) or not relation[field].strip()
                               for field in ('sourceReview', 'basis', 'fromUnitId', 'toUnitId', 'fromRegionId', 'toRegionId'))):
                    invalid(key, 'hierarchy_parent_declaration_untrusted', boundary_ids)
                    continue
                if len(child_nodes) != 1 or len(parent_nodes) != 1:
                    invalid(key, 'hierarchy_parent_endpoint_missing_ambiguous_or_cross_scope', boundary_ids)
                    continue
                child, parent = child_nodes[0], parent_nodes[0]
                if (relation['fromRegionId'] not in child.get('adoption', {}).get('regionIds', [])
                        or relation['toRegionId'] not in parent.get('adoption', {}).get('regionIds', [])):
                    invalid(key, 'hierarchy_parent_region_endpoint_differs', boundary_ids)
                    continue
                if child['id'] == parent['id']:
                    invalid(key, 'hierarchy_between_whole_unit_self_edge', boundary_ids)
                    continue
                address = (key, child['id'], parent['id'])
                previous = edges.get(address)
                if previous is not None and previous['relation'] != relation:
                    invalid(key, 'hierarchy_parent_declarations_conflict', boundary_ids)
                    continue
                edge = {'edgeId': digest(relation), 'relation': copy.deepcopy(relation)}
                edges[address] = edge
                children[parent['id']][child['id']] = edge
                incoming[child['id']].add(parent['id'])
                associated.update((child['id'], parent['id']))
        for identity, sources in incoming.items():
            if len(sources) != 1:
                invalid(self.hierarchy_scope(self.parents[identity]), 'hierarchy_multiple_whole_unit_parents',
                        {identity, *sources})
        # Validate the full graph, but pause only closures that depend on the
        # invalid boundary. A disconnected cycle does not invalidate good work.
        visited, visiting, path = set(), set(), []

        def visit(identity):
            if identity in visiting:
                invalid(self.hierarchy_scope(self.parents[identity]), 'hierarchy_whole_unit_cycle',
                        path[path.index(identity):])
                return
            if identity in visited:
                return
            visiting.add(identity)
            path.append(identity)
            for child in children.get(identity, {}):
                visit(child)
            visiting.remove(identity)
            path.pop()
            visited.add(identity)
        for identity in self.parents:
            if identity in associated:
                visit(identity)
        self.hierarchy_graph = {'scopeErrors': scope_errors, 'nodeErrors': node_errors, 'activeScopes': active_scopes,
                                'children': children, 'associated': associated}

    def _hierarchy_context(self, seed, observed):
        if seed.get('producerKind') not in ('native_pdf', 'native_pdf_source_family'):
            return None
        if self.hierarchy_graph is None:
            self._build_hierarchy()
        graph, key = self.hierarchy_graph, self.hierarchy_scope(seed)
        if key not in graph['activeScopes']:
            return None
        base_ids = observed['requiredMemberIds']
        if key not in graph['scopeErrors'] and not any(i in graph['associated'] for i in base_ids):
            return None
        source_identity = {field: seed.get(field) for field in HIERARCHY_SCOPE_FIELDS}
        ordered_roots = [self.parents[i] for i in sorted(base_ids, key=self.source_order.__getitem__)]
        context = {'policy': HIERARCHY_POLICY, 'status': 'unknown', 'reason': None,
                   'sourceIdentity': source_identity, 'seedObservedUnitId': observed['unitId'],
                   'rootWholeUnitIds': [p.get('unitId') for p in ordered_roots],
                   'requiredMemberIds': [], 'members': [], 'memberPayloadSha256': [],
                   'edgeDeclarations': [], 'blockIds': [], 'observedRanges': [],
                   'memberOrder': 'canonical_source_input', 'derivedMembersCanBecomeOrigins': False,
                   'memberScores': None, 'qualifiersComplete': 'unknown',
                   'semanticScopeVerified': False, 'legalClosureVerified': False}
        try:
            if key in graph['scopeErrors']:
                raise ValueError(graph['scopeErrors'][key])
            if observed['status'] != 'complete_observed_unit':
                raise ValueError('hierarchy_exact_observed_unit_unknown')
            needed, declarations = set(base_ids), {}
            queue = list(base_ids)
            while queue:
                origin = queue.pop()
                if origin in graph['nodeErrors']:
                    raise ValueError(graph['nodeErrors'][origin])
                for child, edge in graph['children'].get(origin, {}).items():
                    declarations[edge['edgeId']] = edge
                    if child not in needed:
                        needed.add(child)
                        queue.append(child)
            # A declared whole unit remains a whole canonical payload. The old
            # exact unit is checked too; its resolution never follows this graph.
            for identity in needed:
                parent = self.parents[identity]
                if (self.hierarchy_scope(parent) != key or parent['sourceQuality'] != seed['sourceQuality']
                        or parent.get('canonicalScope') != 'whole_text_unit'):
                    raise ValueError('hierarchy_member_scope_quality_or_whole_unit_differs')
                self.source(parent)
                self.part_map(parent)
                if self._resolve_observed(parent)['status'] != 'complete_observed_unit':
                    raise ValueError('hierarchy_member_observed_unit_unknown')
            members = [self.parents[i] for i in sorted(needed, key=self.source_order.__getitem__)]
            blocks = sorted({part['blockId'] for p in members for part in p['parts']})
            ranges = [self.block_range(self.source(seed), block) for block in blocks]
            context.update(status='complete_observed_hierarchy_context',
                           requiredMemberIds=[p['id'] for p in members], members=copy.deepcopy(members),
                           memberPayloadSha256=[{'id': p['id'], 'sha256': digest(p)} for p in members],
                           edgeDeclarations=[declarations[i] for i in sorted(declarations)],
                           blockIds=blocks, observedRanges=ranges)
        except (ValueError, KeyError, TypeError, UnicodeError, WindowError) as error:
            context['reason'] = error.kind if isinstance(error, WindowError) else str(error)
        address = {field: context[field] for field in ('policy', 'status', 'reason', 'sourceIdentity',
                   'seedObservedUnitId', 'rootWholeUnitIds', 'requiredMemberIds', 'memberPayloadSha256')}
        address['edgeIds'] = [edge['edgeId'] for edge in context['edgeDeclarations']]
        context['contextId'] = digest(address)
        return context

    @staticmethod
    def attachment_scope(p):
        return tuple(p.get(k) for k in ATTACHMENT_SCOPE_FIELDS)

    def _attachment_declaration(self, owner, relation, left, right):
        """Validate the two existing producer shapes, never infer ownership."""
        required_hashes = ('originalSourceSha256', 'projectionRevisionSha256',
                           'sourceRevisionSha256', 'sourceSha256')
        for member in (owner, left, right):
            if any(not isinstance(member.get(f), str) or not re.fullmatch('[0-9a-f]{64}', member[f])
                   for f in required_hashes):
                raise ValueError('attachment_source_revision_identity_unknown')
            self.source(member)
            self.part_map(member)
            if (self.attachment_scope(member) != self.attachment_scope(owner)
                    or member['sourceQuality'] != owner['sourceQuality']
                    or any(part.get('sourceSha256', member['sourceSha256']) != member['sourceSha256']
                           for part in member['parts'])):
                raise ValueError('attachment_source_revision_quality_or_part_source_differs')
        if owner['id'] not in (left['id'], right['id']):
            raise ValueError('attachment_declaration_owner_not_endpoint')
        status = relation.get('status')
        if status == 'explicit_source_reviewed':
            reference = relation.get('referenceSha256')
            if not isinstance(reference, str) or not re.fullmatch('[0-9a-f]{64}', reference):
                raise ValueError('attachment_review_reference_unknown')
            for member, side in ((left, 'from'), (right, 'to')):
                adoption = member.get('adoption', {})
                block = relation.get(side + 'BlockId')
                if (member.get('producerKind') in ('native_pdf', 'native_pdf_source_family')
                        or adoption.get('reviewStatus') != 'explicit_source_reviewed_selection'
                        or adoption.get('referenceSha256') != reference
                        or adoption.get('unitId') != member.get('unitId')):
                    raise ValueError('attachment_review_reference_or_adoption_differs')
                if not isinstance(block, str) or block not in {p['blockId'] for p in member['parts']}:
                    raise ValueError('attachment_block_endpoint_not_owned')
                cell = relation.get(side + 'CellId')
                if cell is not None and cell not in {c.get('cellId') for p in member['parts'] for c in p.get('cells', [])}:
                    raise ValueError('attachment_cell_endpoint_not_owned')
        elif status == 'source_reviewed_declared':
            if (type(relation.get('humanConfirmed')) is not bool
                    or any(not isinstance(relation.get(f), str) or not relation[f].strip()
                           for f in ('sourceReview', 'basis', 'fromRegionId', 'toRegionId'))
                    or relation.get('scope') != ('within_whole_unit' if left['id'] == right['id'] else 'between_whole_units')):
                raise ValueError('attachment_native_declaration_untrusted')
            references = set()
            for member, side in ((left, 'from'), (right, 'to')):
                region = relation[side + 'RegionId']
                adoption = member.get('adoption', {})
                reference = member.get('referenceSha256')
                if (member.get('producerKind') not in ('native_pdf', 'native_pdf_source_family') or member.get('canonicalScope') != 'whole_text_unit'
                        or adoption.get('scope') != 'whole_text_unit'
                        or not isinstance(member.get('scopeSha256'), str)
                        or not re.fullmatch('[0-9a-f]{64}', member['scopeSha256'])
                        or not isinstance(reference, str) or not re.fullmatch('[0-9a-f]{64}', reference)):
                    raise ValueError('attachment_native_source_review_identity_unknown')
                references.add(reference)
                if (region not in adoption.get('regionIds', [])
                        or not any(p.get('referenceRegionId') == region for p in member['parts'])):
                    raise ValueError('attachment_region_endpoint_not_owned')
            if len(references) != 1:
                raise ValueError('attachment_native_reference_differs')
        else:
            raise ValueError('attachment_declaration_unreviewed')

    def _build_attachments(self):
        nodes = defaultdict(list)
        edges, incident, errors = {}, defaultdict(dict), {}
        for member in self.parents.values():
            key, unit_id = self.attachment_scope(member), member.get('unitId')
            # Published source metadata is already validated by its producer.
            # If a malformed new attachment identity nevertheless arrives,
            # bound it to its owner instead of hashing a list/dict key.
            if any(value is not None and not isinstance(value, str) for value in key):
                errors.setdefault(member['id'], 'attachment_source_identity_shape_invalid')
                continue
            if unit_id is not None and not isinstance(unit_id, str):
                errors.setdefault(member['id'], 'attachment_unit_identity_shape_invalid')
                continue
            nodes[(key, unit_id)].append(member)
        targets = defaultdict(set)

        def invalid(identities, reason):
            for identity in identities:
                errors.setdefault(identity, reason)

        for owner in self.parents.values():
            relations = owner.get('relations', [])
            if not isinstance(relations, list):
                invalid({owner['id']}, 'attachment_declarations_not_a_list')
                continue
            for relation in relations:
                if not isinstance(relation, dict):
                    invalid({owner['id']}, 'attachment_declaration_not_an_object')
                    continue
                if relation.get('type') not in ('margin_note', 'sidebar'):
                    continue
                if (owner['id'] in errors or any(not isinstance(relation.get(f), str) or not relation[f].strip()
                                                for f in ('fromUnitId', 'toUnitId'))):
                    invalid({owner['id']}, 'attachment_endpoint_identity_shape_invalid')
                    continue
                key = self.attachment_scope(owner)
                lefts = nodes.get((key, relation.get('fromUnitId')), [])
                rights = nodes.get((key, relation.get('toUnitId')), [])
                boundary = {owner['id'], *(p['id'] for p in lefts + rights)}
                try:
                    if len(lefts) != 1 or len(rights) != 1:
                        raise ValueError('attachment_endpoint_missing_ambiguous_or_cross_scope')
                    left, right = lefts[0], rights[0]
                    self._attachment_declaration(owner, relation, left, right)
                    endpoints = tuple(relation.get(f) for f in ('fromBlockId', 'toBlockId', 'fromCellId', 'toCellId',
                                                               'fromRegionId', 'toRegionId'))
                    address = (key, relation['type'], left['id'], right['id'], endpoints)
                    previous = edges.get(address)
                    if previous is not None and previous['relation'] != relation:
                        raise ValueError('attachment_declarations_conflict')
                    edge = {'edgeId': digest(relation), 'relation': copy.deepcopy(relation),
                            'memberIds': [left['id'], right['id']]}
                    edges[address] = edge
                    incident[left['id']][edge['edgeId']] = edge
                    incident[right['id']][edge['edgeId']] = edge
                    if left['id'] != right['id']:
                        targets[left['id']].add(right['id'])
                except (ValueError, KeyError, TypeError, UnicodeError, WindowError) as error:
                    invalid(boundary, error.kind if isinstance(error, WindowError) else str(error))
        # A reviewed note can explicitly qualify several observed targets.
        # Preserve its entire validated declared star; cardinality alone
        # does not constitute an ambiguous source association.
        groups = defaultdict(dict)
        for edge in edges.values():
            groups[edge['memberIds'][0]][edge['edgeId']] = edge
        self.attachment_graph = {'incident': incident, 'groups': groups, 'nodeErrors': errors}

    def _attachment_context(self, seed, observed):
        if self.attachment_graph is None:
            self._build_attachments()
        graph = self.attachment_graph
        # Only original observed members may nominate incident note groups.
        # Every declared target of such a shared note is retained once.
        # Derived targets cannot nominate another note group, siblings or
        # new ancestry; this is a direct source-association star, not a
        # recursive graph walk or a claim about legal applicability.
        roots = observed['requiredMemberIds'] or [seed['id']]
        if not any(identity in graph['incident'] or identity in graph['nodeErrors'] for identity in roots):
            return None
        context = {'policy': ATTACHMENT_POLICY, 'status': 'unknown', 'reason': None,
                   'sourceIdentity': {f: seed.get(f) for f in ATTACHMENT_SCOPE_FIELDS},
                   'seedObservedUnitId': observed['unitId'],
                   'rootWholeUnitIds': [self.parents[i].get('unitId') for i in sorted(roots, key=self.source_order.__getitem__)],
                   'requiredMemberIds': [], 'members': [], 'memberPayloadSha256': [],
                   'edgeDeclarations': [], 'blockIds': [], 'observedRanges': [],
                   'memberOrder': 'canonical_source_input', 'derivedMembersCanBecomeOrigins': False,
                   'memberScores': None, 'qualifiersComplete': 'unknown',
                   'semanticScopeVerified': False, 'legalClosureVerified': False}
        try:
            if observed['status'] != 'complete_observed_unit':
                raise ValueError('attachment_exact_observed_unit_unknown')
            needed, declarations = set(roots), {}
            for identity in roots:
                if identity in graph['nodeErrors']:
                    raise ValueError(graph['nodeErrors'][identity])
                for direct in graph['incident'].get(identity, {}).values():
                    note = direct['memberIds'][0]
                    for edge in graph['groups'][note].values():
                        declarations[edge['edgeId']] = edge
                        needed.update(edge['memberIds'])
            # The endpoint remains its complete observed original source unit,
            # never just the qualifying subpart or a shortened model summary.
            for identity in list(needed):
                member = self.parents[identity]
                if identity in graph['nodeErrors']:
                    raise ValueError(graph['nodeErrors'][identity])
                if (self.attachment_scope(member) != self.attachment_scope(seed)
                        or member['sourceQuality'] != seed['sourceQuality']):
                    raise ValueError('attachment_member_source_revision_or_quality_differs')
                exact = self._resolve_observed(member)
                if exact['status'] != 'complete_observed_unit':
                    raise ValueError('attachment_member_observed_unit_unknown')
                needed.update(exact['requiredMemberIds'])
            members = [self.parents[i] for i in sorted(needed, key=self.source_order.__getitem__)]
            for member in members:
                if (member['id'] in graph['nodeErrors'] or self.attachment_scope(member) != self.attachment_scope(seed)
                        or member['sourceQuality'] != seed['sourceQuality']):
                    raise ValueError('attachment_exact_member_scope_quality_or_declaration_unknown')
                self.source(member); self.part_map(member)
            blocks = sorted({part['blockId'] for p in members for part in p['parts']})
            context.update(status='complete_observed_attachment_context',
                           requiredMemberIds=[p['id'] for p in members], members=copy.deepcopy(members),
                           memberPayloadSha256=[{'id': p['id'], 'sha256': digest(p)} for p in members],
                           edgeDeclarations=[{'edgeId': declarations[i]['edgeId'],
                                              'relation': copy.deepcopy(declarations[i]['relation'])}
                                             for i in sorted(declarations)],
                           blockIds=blocks, observedRanges=[self.block_range(self.source(seed), b) for b in blocks])
        except (ValueError, KeyError, TypeError, UnicodeError, WindowError) as error:
            context['reason'] = error.kind if isinstance(error, WindowError) else str(error)
        address = {field: context[field] for field in ('policy', 'status', 'reason', 'sourceIdentity',
                   'seedObservedUnitId', 'rootWholeUnitIds', 'requiredMemberIds', 'memberPayloadSha256')}
        address['edgeIds'] = [edge['edgeId'] for edge in context['edgeDeclarations']]
        context['contextId'] = digest(address)
        return context

    def resolve(self, p):
        observed = self._resolve_observed(p)
        if p['id'] not in self.hierarchy_cache:
            self.hierarchy_cache[p['id']] = self._hierarchy_context(p, observed)
        context = self.hierarchy_cache[p['id']]
        if context is not None:
            observed['hierarchyContext'] = copy.deepcopy(context)
        if p['id'] not in self.attachment_cache:
            self.attachment_cache[p['id']] = self._attachment_context(p, observed)
        attachment = self.attachment_cache[p['id']]
        if attachment is not None:
            observed['attachmentContext'] = copy.deepcopy(attachment)
        return observed


def select_source_units(parents, reranked_hits, limit, *, hierarchy_context=True):
    """Keep only actually reranked seeds, with at most K reliable unique units.

    The complete rerank sequence must be supplied. Structure never assigns or
    changes scores. Derived members are looked up once from canonical parents;
    their text, references and other blocks are never followed as new origins.
    Window-only callers explicitly use the original observed-unit resolver;
    dual-structure callers retain the default typed parent context.
    """
    if type(hierarchy_context) is not bool:
        raise ValueError('hierarchy_context_mode_must_be_boolean')
    catalog = SourceUnits(parents)
    selected, units, used, skipped = [], [], {}, []
    if type(limit) is not int or limit < 0 or len({h['id'] for h in reranked_hits}) != len(reranked_hits):
        raise ValueError('invalid_limit_or_duplicate_reranked_seed')
    for rank, hit in enumerate(reranked_hits, 1):
        if type(hit.get('score')) not in (int, float) or not math.isfinite(hit['score']):
            raise ValueError('reranked_seed_score_not_finite_number')
        if hit['id'] not in catalog.parents or hit['payload'] != catalog.parents[hit['id']]:
            raise ValueError('reranked_seed_canonical_payload_differs')
        unit = (catalog.resolve(catalog.parents[hit['id']]) if hierarchy_context
                else catalog._resolve_observed(catalog.parents[hit['id']]))
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
    return selected, {'policy': POLICY if hierarchy_context else OBSERVED_POLICY,
                      'scope': 'canonical_observed_structure_only',
                      'originalHitIds': [h['id'] for h in selected], 'units': units,
                      'rawParentTopKIds': [h['id'] for h in reranked_hits[:limit]],
                      'skippedDuplicateUnitSeeds': skipped, 'derivedMembersCanBecomeOrigins': False,
                      'memberOrder': 'canonical_parent_id_lexical', 'qualifiersComplete': 'unknown',
                      'semanticScopeVerified': False}
