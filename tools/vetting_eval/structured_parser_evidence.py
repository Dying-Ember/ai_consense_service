"""Preserve complete parser outputs and project unverified structural evidence.

This module does not run a parser, fetch assets, assign contract facts, or choose
a winning parser. Callers supply the original source identity, resolved runtime
identity and an explicit request-page map. Unsupported output stays in the raw
ledger instead of being recursively flattened into apparently complete text.

``normalize_parser_run`` is the parser seam. ``build_dual_parser_evidence``
combines two runs without fusing cells or treating agreement as verification.
All JSON pointers refer to a named raw output in that run. Byte/string outputs
retain their exact transport bytes; objects have a canonical-JSON identity,
which is deliberately not described as a transport hash.
"""
import base64
import copy
import hashlib
from html.parser import HTMLParser
import json
import math
import os
from pathlib import Path
import re
import tempfile

from artifact_store import ArtifactStore, ArtifactStoreError, DESCRIPTOR_SCHEMA


SCHEMA = 'consense.structured-parser-evidence.v1'
PARSERS = ('paddleocr-vl', 'mineru')


class ParserEvidenceError(ValueError):
    """A caller identity/mapping error; not an inference-quality failure."""


def _json_bytes(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def _hash(value):
    return hashlib.sha256(_json_bytes(value)).hexdigest()


def _sha(value, name):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-fA-F]{64}', value):
        raise ParserEvidenceError(name + ' must be a SHA-256 hex identity')
    return value.lower()


def _positive(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ParserEvidenceError(name + ' must be a positive integer')
    return value


def _source(source):
    value = copy.deepcopy(source)
    for key in ('sourceFilename',):
        if not isinstance(value.get(key), str) or not value[key].strip():
            raise ParserEvidenceError('Source ' + key + ' is required')
    value['sourceSha256'] = _sha(value.get('sourceSha256'), 'source.sourceSha256')
    value.setdefault('sourceRevisionSha256', value['sourceSha256'])
    value['sourceRevisionSha256'] = _sha(value['sourceRevisionSha256'], 'source.sourceRevisionSha256')
    _positive(value.get('pageCount'), 'source.pageCount')
    if value.get('mediaType') not in ('application/pdf', 'image/png', 'image/jpeg', 'image/tiff', 'image/webp'):
        raise ParserEvidenceError('This scan-parser seam accepts PDF/image; native DOCX stays with POI')
    return value


def _identity(parser, identity):
    value = copy.deepcopy(identity)
    value.pop('identitySha256', None)
    for key in ('parserVersion', 'engine', 'models'):
        if not value.get(key):
            raise ParserEvidenceError('Resolved identity requires ' + key)
    if not isinstance(value['models'], list) or any(
            not isinstance(model, dict) or not model.get('modelId') for model in value['models']):
        raise ParserEvidenceError('models must contain modelId records')
    if not isinstance(value.get('config'), dict):
        raise ParserEvidenceError('Resolved parser config must be supplied')
    config_hash = _hash(value['config'])
    if value.get('configSha256') not in (None, config_hash):
        raise ParserEvidenceError('Supplied config hash does not match resolved configuration')
    value['configSha256'] = config_hash
    value['modelIdentityStatus'] = 'incomplete' if any(
        not (model.get('sha256') or model.get('revision') not in (None, '', 'main', 'latest', 'unknown'))
        for model in value['models']) else 'declared_pinned'
    for model in value['models']:
        if model.get('sha256'):
            model['sha256'] = _sha(model['sha256'], 'model.sha256')
    value['parser'] = parser
    value['identitySha256'] = _hash(value)
    return value


def _raw_output(value, *, parse_json=True, max_bytes=64 * 1024 * 1024, artifact_store=None):
    """Keep all bytes; projection budgets never become capture truncation."""
    record, raw, decoded = {}, None, None
    descriptor = isinstance(value, dict) and value.get('schema') == DESCRIPTOR_SCHEMA
    external = isinstance(value, Path) or descriptor
    if external and not isinstance(artifact_store, ArtifactStore):
        raise ParserEvidenceError('File/descriptor capture requires an ArtifactStore')
    if external:
        try:
            asset = copy.deepcopy(value) if descriptor else artifact_store.put_file(value)
            artifact_store.verify(asset)
        except (ArtifactStoreError, OSError) as error:
            raise ParserEvidenceError(str(error)) from error
        record.update(identityScope=asset['identityScope'], sha256=asset['sha256'],
                      byteCount=asset['byteCount'], artifactDescriptor=asset)
        if parse_json and record['byteCount'] <= max_bytes:
            raw = artifact_store.read_asset(asset)
    else:
        if isinstance(value, (bytes, str)):
            raw = value if isinstance(value, bytes) else value.encode('utf-8')
            scope = 'exact_supplied_bytes'
        else:
            raw, scope = _json_bytes(value), 'canonical_json_object'
        record.update(identityScope=scope, sha256=hashlib.sha256(raw).hexdigest(), byteCount=len(raw))
        if artifact_store is not None:
            if not isinstance(artifact_store, ArtifactStore):
                raise ParserEvidenceError('artifact_store must be an ArtifactStore')
            record['artifactDescriptor'] = artifact_store.put_bytes(raw, identity_scope=scope)
        elif scope == 'canonical_json_object':
            record['json'] = json.loads(raw)
        else:
            record['bytesBase64'] = base64.b64encode(raw).decode('ascii')
    if parse_json and record['byteCount'] > max_bytes:
        record['projectionStatus'] = 'oversize_projection_not_attempted'
    elif parse_json:
        try:
            decoded = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object,
                                 parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))
        except (UnicodeDecodeError, ValueError):
            record['projectionStatus'] = 'invalid_json_retained'
    record.setdefault('projectionStatus', 'available')
    record['captureStatus'] = 'complete_bytes_captured'
    return record, decoded


def read_artifact_record(record, *, artifact_store=None):
    """Return verified record bytes, including externally stored parser output."""
    if not isinstance(record, dict) or record.get('contentOmitted'):
        raise ParserEvidenceError('Artifact content was not captured')
    descriptor = record.get('artifactDescriptor')
    if descriptor is not None:
        if not isinstance(artifact_store, ArtifactStore):
            raise ParserEvidenceError('An ArtifactStore is required to read external captured bytes')
        if any(descriptor.get(key) != record.get(key) for key in ('sha256', 'byteCount', 'identityScope')):
            raise ParserEvidenceError('Artifact descriptor identity disagrees with its record')
        try:
            return artifact_store.read_asset(descriptor)
        except ArtifactStoreError as error:
            raise ParserEvidenceError(str(error)) from error
    try:
        raw = base64.b64decode(record['bytesBase64'], validate=True) if 'bytesBase64' in record else _json_bytes(record['json'])
    except (KeyError, ValueError, TypeError) as error:
        raise ParserEvidenceError('Artifact has no readable captured content') from error
    if record.get('byteCount') != len(raw) or record.get('sha256') != hashlib.sha256(raw).hexdigest():
        raise ParserEvidenceError('Captured artifact SHA-256 or byteCount does not match its record')
    return raw


def _verify_record(record, artifact_store):
    descriptor = record.get('artifactDescriptor')
    if descriptor is None:
        read_artifact_record(record)
        return
    if not isinstance(artifact_store, ArtifactStore):
        raise ParserEvidenceError('An ArtifactStore is required to verify external captured bytes')
    if any(descriptor.get(key) != record.get(key) for key in ('sha256', 'byteCount', 'identityScope')):
        raise ParserEvidenceError('Artifact descriptor identity disagrees with its record')
    try:
        artifact_store.verify(descriptor)
    except ArtifactStoreError as error:
        raise ParserEvidenceError(str(error)) from error


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field: ' + key)
        result[key] = value
    return result


def _page_map(mapping, count):
    if not isinstance(mapping, list) or not mapping:
        raise ParserEvidenceError('Explicit request page_map is required, including for pruned HTTP output')
    seen = set()
    result = []
    for item in mapping:
        entry = copy.deepcopy(item)
        index = entry.get('parserPageIndex')
        if isinstance(index, bool) or not isinstance(index, int) or index < 0 or index in seen:
            raise ParserEvidenceError('parserPageIndex must be unique nonnegative integers')
        seen.add(index)
        physical = _positive(entry.get('physicalPageNo'), 'physicalPageNo')
        if 'providerInputPageNo' in entry:
            _positive(entry['providerInputPageNo'], 'providerInputPageNo')
        if physical > count:
            raise ParserEvidenceError('Physical page exceeds source page count')
        if any(physical == p['physicalPageNo'] for p in result):
            raise ParserEvidenceError('One parser page cannot silently stand for a duplicated physical page')
        for name in ('width', 'height'):
            dim = entry.get(name)
            if isinstance(dim, bool) or not isinstance(dim, (int, float)) or not math.isfinite(dim) or dim <= 0:
                raise ParserEvidenceError('Page map requires finite positive ' + name)
        if entry.get('coordinateUnit') not in ('pixel', 'pt'):
            raise ParserEvidenceError('Page map coordinateUnit must be pixel or pt')
        if entry.get('providerBboxFrame') not in ('raster', 'page', 'normalized', 'normalized_1000'):
            raise ParserEvidenceError('Page map must explicitly declare providerBboxFrame')
        result.append(entry)
    return result


def _issue(code, pointer, **details):
    return {'code': code, 'pointer': pointer, 'status': 'unknown', **details}


def _provider_input(identity, artifacts, issues):
    """Bind the actual parser input independently from its original PDF source.

    An image may be a raster of original physical page 3 while the provider's
    page_index_map=[0] names page 1 of that one-page input. Vendor file_suffix
    metadata does not override the caller's actual input identity or media type.
    """
    if identity is None:
        issues.append(_issue('provider_input_identity_not_supplied', '/providerInputIdentity'))
        return {'captureStatus': 'unknown', 'pageIndexMapMeaning': 'unknown'}
    if not isinstance(identity, dict):
        raise ParserEvidenceError('provider_input_identity must describe actual input bytes')
    value = copy.deepcopy(identity)
    value.pop('inputIdentitySha256', None)
    value['inputSha256'] = _sha(value.get('inputSha256'), 'provider_input_identity.inputSha256')
    _positive(value.get('pageCount'), 'provider_input_identity.pageCount')
    if value.get('mediaType') not in ('application/pdf', 'image/png', 'image/jpeg', 'image/tiff', 'image/webp'):
        raise ParserEvidenceError('provider_input_identity requires an explicit PDF/image mediaType')
    if value.get('pageIndexMapMeaning') != 'provider_input_document':
        raise ParserEvidenceError('pageIndexMapMeaning must explicitly be provider_input_document')
    name = value.get('inputArtifactName')
    if not isinstance(name, str) or not name:
        raise ParserEvidenceError('provider_input_identity requires inputArtifactName')
    artifact = artifacts.get(name)
    if artifact is None or artifact.get('projectionStatus') != 'available' or artifact.get('contentOmitted'):
        value['captureStatus'] = 'unknown'
        issues.append(_issue('provider_input_bytes_not_captured', '/providerInputIdentity', inputArtifactName=name))
    elif artifact['sha256'] != value['inputSha256']:
        value['captureStatus'] = 'unknown'
        issues.append(_issue('provider_input_asset_sha_disagrees', '/providerInputIdentity', inputArtifactName=name))
    else:
        value['captureStatus'] = 'actual_input_bytes_captured'
    value['inputIdentitySha256'] = _hash(value)
    return value


def _box(raw, frame, page, pointer, issues):
    if raw is None:
        issues.append(_issue('bbox_missing', pointer))
        return None
    if not isinstance(raw, list) or len(raw) != 4 or any(
            isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in raw):
        issues.append(_issue('bbox_invalid', pointer))
        return None
    x0, y0, x1, y1 = raw
    limitx, limity = ((1, 1) if frame == 'normalized' else
                      (1000, 1000) if frame == 'normalized_1000' else (page['width'], page['height']))
    if not (0 <= x0 <= x1 <= limitx and 0 <= y0 <= y1 <= limity):
        issues.append(_issue('bbox_out_of_declared_frame', pointer, declaredFrame=frame))
        return None
    return {'xyxy': copy.deepcopy(raw), 'coordinateFrame': frame,
            'coordinateUnit': 'ratio' if frame == 'normalized' else 'ratio_1000' if frame == 'normalized_1000' else page['coordinateUnit'],
            'origin': 'top-left', 'pageWidth': page['width'], 'pageHeight': page['height']}


class _TableHTML(HTMLParser):
    """Read one entire HTML table's declared spans; never infer blank/header meaning."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.cells, self.errors = [], []
        self.row, self.column, self.depth = -1, 0, 0
        self.occupied, self.cell, self.tables = set(), None, 0

    def handle_starttag(self, tag, attrs):
        if tag == 'table':
            self.depth += 1
            self.tables += 1
            if self.depth > 1:
                self.errors.append('nested_table_unknown')
        if self.depth != 1:
            return
        if tag == 'tr':
            if self.cell is not None:
                self.errors.append('unclosed_cell')
            self.row += 1
            self.column = 0
        if tag in ('td', 'th'):
            if self.cell is not None or self.row < 0:
                self.errors.append('invalid_cell_nesting')
                return
            attrs = dict(attrs)
            try:
                spans = [int(attrs.get(k, '1')) for k in ('rowspan', 'colspan')]
                if any(v < 1 or v > 10000 for v in spans):
                    raise ValueError()
            except (TypeError, ValueError):
                self.errors.append('invalid_span')
                spans = [None, None]
            if spans[0] is not None and len(self.occupied) + spans[0] * spans[1] > 100000:
                self.errors.append('span_grid_budget_exceeded')
                spans = [None, None]
            while (self.row, self.column) in self.occupied:
                self.column += 1
            self.cell = {'row': self.row, 'column': self.column, 'rowSpan': spans[0],
                         'columnSpan': spans[1], 'htmlTag': tag, 'text': '', 'attributes': attrs}
        if tag == 'br' and self.cell is not None:
            self.cell['text'] += '\n'

    def handle_data(self, data):
        if self.depth == 1 and self.cell is not None:
            self.cell['text'] += data

    def handle_endtag(self, tag):
        if self.depth == 1 and tag in ('td', 'th') and self.cell is not None:
            cell = self.cell
            if cell['rowSpan'] is not None:
                for row in range(cell['row'], cell['row'] + cell['rowSpan']):
                    for col in range(cell['column'], cell['column'] + cell['columnSpan']):
                        if (row, col) in self.occupied:
                            self.errors.append('span_overlap')
                        self.occupied.add((row, col))
                self.column = cell['column'] + cell['columnSpan']
            self.cells.append(cell)
            self.cell = None
        if tag == 'table':
            self.depth -= 1

    def finish(self):
        if self.depth != 0 or self.cell is not None or self.tables != 1:
            self.errors.append('incomplete_or_multiple_tables')
        if not self.cells:
            self.errors.append('no_observed_cells')
        return self.cells, list(dict.fromkeys(self.errors))


def _html(block, pointer):
    for key in ('html', 'table_html', 'pred_html', 'block_content', 'content'):
        if isinstance(block.get(key), str) and '<table' in block[key].lower():
            return block[key], pointer + '/' + key
    # MinerU semantic table wrapper -> table_body -> table span. Retain the
    # exact HTML observation, never concatenate multiple tables into one grid.
    found = []
    # docvortex.middle 2.0 semantic blocks use explicit content items; inspect
    # only the typed table body, never flatten unrelated/unknown children.
    for n, child in enumerate(block.get('content', []) if isinstance(block.get('content'), list) else []):
        if isinstance(child, dict) and child.get('type') in ('table', 'table_body'):
            value, location = _html(child, pointer + '/content/' + str(n))
            if value is not None:
                found.append((value, location))
    for n, child in enumerate(block.get('blocks', []) if isinstance(block.get('blocks'), list) else []):
        if isinstance(child, dict) and child.get('type') == 'table_body':
            value, location = _html(child, pointer + '/blocks/' + str(n))
            if value is not None:
                found.append((value, location))
    for n, line in enumerate(block.get('lines', []) if isinstance(block.get('lines'), list) else []):
        if not isinstance(line, dict):
            continue
        for s, span in enumerate(line.get('spans', []) if isinstance(line.get('spans'), list) else []):
            if isinstance(span, dict) and span.get('type') == 'table':
                value, location = _html(span, pointer + '/lines/' + str(n) + '/spans/' + str(s))
                if value is not None:
                    found.append((value, location))
    return found[0] if len(found) == 1 else (None, None)


def _table(block, block_id, pointer, issues):
    html, html_pointer = _html(block, pointer)
    cells = []
    local = []
    if html is not None:
        parser = _TableHTML()
        parser.feed(html)
        cells, local = parser.finish()
    else:
        local.append('table_structure_unavailable')
    # Even a valid HTML declaration is a parser observation, not verified gold.
    for n, cell in enumerate(cells):
        cell.update(cellId=block_id + '/cell/' + str(n), nativeCell=False,
                    bbox=None, bboxStatus='not_supplied', headerStatus='unknown',
                    textStatus='parser_observed_unverified' if cell['text'] else 'blank_meaning_unknown',
                    provenance={'pointer': html_pointer, 'htmlCellOrdinal': n})
    issues.extend(_issue(code, pointer) for code in local)
    return {'tableGroupId': block_id + '/table', 'html': html, 'cells': cells,
            'groupPolicy': 'whole_table_only', 'nativeTable': False,
            'structureStatus': 'unknown' if local else 'parser_observed_unverified',
            'headerRelationsStatus': 'unknown', 'crossPageRelationsStatus': 'unknown',
            'declaredRelationships': {k: copy.deepcopy(block[k]) for k in
                ('caption', 'footnote', 'table_caption', 'table_footnote', 'continuation', 'cross_page') if k in block},
            'declaredCellObservations': copy.deepcopy(block.get('cells', block.get('cell_box_list'))),
            'issues': local, 'provenance': {'pointer': pointer, 'htmlPointer': html_pointer}}


def _text(block):
    for key in ('block_content', 'text', 'content'):
        if isinstance(block.get(key), str):
            return block[key], key
    content = block.get('content')
    if isinstance(content, list) and content and all(
            isinstance(item, dict) and item.get('type') == 'text' and isinstance(item.get('content'), str)
            for item in content):
        return ''.join(item['content'] for item in content), 'content/text/content'
    # Explicit model line/span structure only; never recursively harvest text.
    lines = block.get('lines')
    if isinstance(lines, list):
        values = []
        for line in lines:
            if not isinstance(line, dict) or not isinstance(line.get('spans'), list):
                return None, None
            spans = line['spans']
            if any(not isinstance(s, dict) or not isinstance(s.get('content'), str) for s in spans):
                return None, None
            values.append(''.join(s['content'] for s in spans))
        return '\n'.join(values), 'lines/spans/content'
    return None, None


def _paddle_pages(outputs):
    key = 'page_json'
    raw = outputs.get(key)
    if isinstance(raw, dict) and 'result' in raw:
        raw, prefix = raw['result'], '/result'
    else:
        prefix = ''
    if isinstance(raw, dict) and isinstance(raw.get('layoutParsingResults'), list):
        return key, [(p.get('prunedResult', p) if isinstance(p, dict) else p,
                      prefix + '/layoutParsingResults/' + str(n) + ('/prunedResult' if isinstance(p, dict) and 'prunedResult' in p else ''), n)
                     for n, p in enumerate(raw['layoutParsingResults'])]
    if isinstance(raw, list):
        return key, [(p, '/' + str(n), n) for n, p in enumerate(raw)]
    if isinstance(raw, dict) and 'parsing_res_list' in raw:
        return key, [(raw, prefix, 0)]
    return key, []


def _mineru_pages(outputs, issues):
    # Project the most detailed supplied schema. Keep every other raw output.
    for key, schema in (('middle_json', 'docvortex.middle'), ('model_json', 'docvortex.model')):
        raw = outputs.get(key)
        if raw is None:
            continue
        if not isinstance(raw, dict) or raw.get('schema') != schema or str(raw.get('schema_version')) != '2.0':
            issues.append(_issue('unsupported_mineru_schema', '/' + key))
            continue
        pages = raw.get('pages')
        if isinstance(pages, list):
            return key, [(p, '/pages/' + str(n), n) for n, p in enumerate(pages)]
        issues.append(_issue('mineru_pages_missing', '/' + key))
    raw = outputs.get('structured_content')
    if isinstance(raw, dict) and isinstance(raw.get('pages'), list):
        return 'structured_content', [(p, '/pages/' + str(n), n) for n, p in enumerate(raw['pages'])]
    if isinstance(raw, list) and all(isinstance(p, dict) and isinstance(p.get('blocks'), list) for p in raw):
        return 'structured_content', [(p, '/' + str(n), n) for n, p in enumerate(raw)]
    return None, []


def _terminal_record(terminal):
    if terminal is None:
        return {'status': 'unknown', 'pageResults': [], 'declarationMissing': True}
    if not isinstance(terminal, dict) or terminal.get('status') not in ('completed', 'failed', 'cancelled', 'unknown'):
        raise ParserEvidenceError('terminal requires explicit completed/failed/cancelled/unknown status')
    value = copy.deepcopy(terminal)
    pages = value.get('pageResults')
    if not isinstance(pages, list):
        raise ParserEvidenceError('terminal.pageResults must be an explicit list')
    for page in pages:
        if not isinstance(page, dict) or isinstance(page.get('parserPageIndex'), bool) or not isinstance(page.get('parserPageIndex'), int) or page['parserPageIndex'] < 0:
            raise ParserEvidenceError('terminal pages require nonnegative parserPageIndex')
        if page.get('status') not in ('completed', 'failed', 'cancelled', 'unknown'):
            raise ParserEvidenceError('terminal page status must be explicit')
    return value


def _capture_manifest(run):
    def identities(records):
        return {name: {k: copy.deepcopy(record[k]) for k in
                      ('sha256', 'byteCount', 'identityScope', 'artifactDescriptor') if k in record}
                for name, record in records.items()}
    manifest = {'schema': 'consense.parser-capture-manifest.v1',
                'sourceSha256': run['source']['sourceSha256'],
                'sourceRevisionSha256': run['source']['sourceRevisionSha256'],
                'parserIdentitySha256': run['identity']['identitySha256'],
                'configSha256': run['identity']['configSha256'], 'runId': run['runId'],
                'rawOutputs': identities(run['rawOutputs']), 'artifacts': identities(run['artifacts'])}
    manifest['manifestSha256'] = _hash(manifest)
    return manifest


def _run_completeness(run, artifact_store=None):
    """Material and terminal coverage only; semantic unknown remains local."""
    if run.get('independentInputCaptures') is not None:
        from parser_capture import verify_independent_capture_batch
        try:
            return verify_independent_capture_batch(run, artifact_store)
        except (ParserEvidenceError, KeyError, TypeError, ValueError):
            return {'status': 'incomplete', 'materialStatus': 'incomplete', 'terminalStatus': 'unverified',
                    'eligibleForCanonical': False, 'reasons': ['independent_child_capture_or_alias_mapping_invalid'],
                    'requestedParserPageIndices': sorted(m['parserPageIndex'] for m in run['requestPageMap']),
                    'observedParserPageIndices': sorted(p['parserPageIndex'] for p in run['pages']),
                    'requestedPhysicalPages': sorted(m['physicalPageNo'] for m in run['requestPageMap'])}
    reasons = []
    from parser_capture import verify_source_input_capture
    try:
        verify_source_input_capture(run, artifact_store)
    except (ValueError, KeyError, TypeError, OSError):
        reasons.append('selected_source_parent_input_map_or_actual_cas_invalid')
    expected = {m['parserPageIndex'] for m in run['requestPageMap']}
    observed = [p['parserPageIndex'] for p in run['pages']]
    if set(observed) != expected or len(observed) != len(expected):
        reasons.append('requested_and_observed_page_sets_differ')
    required_outputs = ('page_json', 'model_output') if run['parser'] == 'paddleocr-vl' else ('model_json', 'middle_json', 'model_output')
    if any(name not in run['rawOutputs'] for name in required_outputs):
        reasons.append('required_raw_outputs_missing')
    if not isinstance(run.get('requiredArtifacts'), list):
        reasons.append('artifact_inventory_not_declared')
    elif any(name not in run['artifacts'] for name in run['requiredArtifacts']):
        reasons.append('required_artifacts_missing')
    for records in (run['rawOutputs'], run['artifacts']):
        for record in records.values():
            try:
                _verify_record(record, artifact_store)
            except ParserEvidenceError:
                reasons.append('captured_bytes_unavailable_or_changed')
    if run.get('providerInputIdentity', {}).get('captureStatus') != 'actual_input_bytes_captured':
        reasons.append('provider_input_bytes_not_bound')
    if any(p.get('inputToOriginalMappingStatus') != 'caller_bound_input_page_to_original_page' or
           p.get('inputArtifact', {}).get('captureStatus') != 'available' for p in run['pages']):
        reasons.append('page_input_mapping_or_bytes_not_bound')
    if run.get('captureIssues'):
        reasons.append('explicit_capture_failures_present')
    structural_codes = {'page_identity_unmapped_or_duplicate', 'page_not_object', 'page_index_invalid',
                        'provider_input_page_map_disagrees', 'provider_input_page_out_of_input_document',
                        'page_blocks_unavailable', 'supported_page_structure_unavailable'}
    if any(item.get('code') in structural_codes for item in run.get('issues', [])):
        reasons.append('page_capture_structure_unresolved')
    terminal = run.get('terminal', {'status': 'unknown', 'pageResults': []})
    terminal_pages = terminal.get('pageResults', [])
    terminal_indices = [page.get('parserPageIndex') for page in terminal_pages]
    terminal_reasons = []
    if terminal.get('status') != 'completed':
        terminal_reasons.append('run_terminal_not_completed')
    if set(terminal_indices) != expected or len(terminal_indices) != len(expected):
        terminal_reasons.append('terminal_page_set_does_not_match_request')
    if any(page.get('status') != 'completed' for page in terminal_pages):
        terminal_reasons.append('page_terminal_not_completed')
    failed = terminal.get('status') in ('failed', 'cancelled') or any(
        page.get('status') in ('failed', 'cancelled') for page in terminal_pages)
    all_reasons = sorted(set(reasons + terminal_reasons))
    return {'status': 'incomplete' if reasons or failed else 'unverified' if terminal_reasons else 'complete',
            'materialStatus': 'incomplete' if reasons else 'complete',
            'terminalStatus': 'failed' if failed else 'unverified' if terminal_reasons else 'verified',
            'eligibleForCanonical': not all_reasons, 'reasons': all_reasons,
            'requestedParserPageIndices': sorted(expected), 'observedParserPageIndices': sorted(observed),
            'requestedPhysicalPages': sorted(m['physicalPageNo'] for m in run['requestPageMap'])}


def normalize_parser_run(parser, outputs, source, identity, page_map, *, artifacts=None,
                         required_artifacts=None, capture_issues=None, provider_input_identity=None,
                         artifact_store=None, terminal=None):
    """Return raw outputs plus declared structure, all unverified and detached.

    ``outputs`` contains ``page_json`` for Paddle and/or ``model_json``,
    ``middle_json``, ``structured_content`` for MinerU; extra named outputs and
    image assets are preserved. ``page_map`` binds each response page index to
    original physical page, dimensions and coordinate unit. For a cropped or
    rotated raster, include the original-page transform as provenance; absent
    transforms never imply that raster coordinates locate the original PDF.
    ``provider_input_identity`` binds actual PDF/image bytes and explicitly
    declares provider page_index_map as input-document indexes. Each page-map
    entry supplies providerInputPageNo separately from original physicalPageNo.
    Missing input/frame identities remain unknown; neither is guessed from the
    provider's file_suffix or an original PDF's page number.
    """
    if parser not in PARSERS or not isinstance(outputs, dict) or not outputs:
        raise ParserEvidenceError('Supply one supported parser and named raw outputs')
    source = _source(source)
    identity = _identity(parser, identity)
    mappings = _page_map(page_map, source['pageCount'])
    terminal = _terminal_record(terminal)
    raw, decoded, issues = {}, {}, []
    if identity['modelIdentityStatus'] == 'incomplete':
        issues.append(_issue('model_revision_or_weights_identity_missing', '/identity/models'))
    missing_outputs = [name for name in (('page_json',) if parser == 'paddleocr-vl' else ('model_json', 'middle_json'))
                       if name not in outputs]
    if missing_outputs:
        issues.append(_issue('required_raw_outputs_missing', '/', names=missing_outputs))
    if 'model_output' not in outputs:
        issues.append(_issue('earliest_model_output_not_supplied', '/'))
    for name, value in outputs.items():
        if not isinstance(name, str) or not re.fullmatch(r'[a-zA-Z0-9_.-]+', name):
            raise ParserEvidenceError('Raw output names must be simple identifiers')
        raw[name], decoded[name] = _raw_output(value, artifact_store=artifact_store)
        if raw[name]['projectionStatus'] != 'available':
            issues.append(_issue(raw[name]['projectionStatus'], '/' + name))
    artifacts = artifacts or {}
    captured_artifacts = {}
    if not isinstance(artifacts, dict):
        raise ParserEvidenceError('artifacts must map stable artifact names to actual supplied bytes')
    for name, value in artifacts.items():
        if not isinstance(name, str) or not name or not (isinstance(value, (bytes, Path)) or
                isinstance(value, dict) and value.get('schema') == DESCRIPTOR_SCHEMA):
            raise ParserEvidenceError('Asset capture requires named bytes, Path or verified store descriptor, not a path-only claim')
        captured_artifacts[name], _ = _raw_output(value, parse_json=False, max_bytes=16 * 1024 * 1024,
                                                 artifact_store=artifact_store)
        if captured_artifacts[name]['projectionStatus'] != 'available':
            issues.append(_issue('artifact_content_omitted', '/artifacts/' + name))
    if required_artifacts is None:
        issues.append(_issue('artifact_inventory_not_declared', '/artifacts'))
    elif not isinstance(required_artifacts, list) or any(not isinstance(name, str) for name in required_artifacts):
        raise ParserEvidenceError('required_artifacts must be an explicit list of requested page/image asset names')
    else:
        missing = [name for name in required_artifacts if name not in captured_artifacts]
        if missing:
            issues.append(_issue('required_artifacts_missing', '/artifacts', names=missing))
    if capture_issues:
        if not isinstance(capture_issues, list) or any(not isinstance(item, dict) or not item.get('code') for item in capture_issues):
            raise ParserEvidenceError('capture_issues must be explicit failure records')
        issues.extend(copy.deepcopy(capture_issues))
    provider_input = _provider_input(provider_input_identity, captured_artifacts, issues)
    raw_identity = {key: value['sha256'] for key, value in raw.items()}
    run_id = parser + ':' + _hash({'source': source, 'identity': identity, 'raw': raw_identity, 'pageMap': mappings,
                                  'artifacts': {k: v['sha256'] for k, v in captured_artifacts.items()},
                                  'requiredArtifacts': required_artifacts, 'captureIssues': capture_issues,
                                  'providerInputIdentity': provider_input, 'terminal': terminal})
    key, provider_pages = _paddle_pages(decoded) if parser == 'paddleocr-vl' else _mineru_pages(decoded, issues)
    if not provider_pages:
        issues.append(_issue('supported_page_structure_unavailable', '/'))
    pages = []
    observed = set()
    for value, pointer, ordinal in provider_pages:
        if not isinstance(value, dict):
            issues.append(_issue('page_not_object', pointer)); continue
        index = value.get('page_index', value.get('page_idx', ordinal))
        raw_page_index = copy.deepcopy(index)
        index_status = 'provider_declared_or_sequence_index'
        # Paddle's official image response may explicitly declare page_index
        # null. A captured single image and one explicit caller mapping bind
        # the projection without inventing or rewriting a vendor page index.
        if index is None and parser == 'paddleocr-vl' and len(provider_pages) == 1 and len(mappings) == 1 and (
                provider_input.get('captureStatus') == 'actual_input_bytes_captured' and
                provider_input.get('mediaType', '').startswith('image/') and
                provider_input.get('pageCount') == 1 and
                mappings[0].get('providerInputPageNo') == 1 and
                mappings[0].get('inputArtifactName') == provider_input.get('inputArtifactName')):
            index = mappings[0]['parserPageIndex']
            index_status = 'caller_bound_single_image_null_provider_index'
        if isinstance(index, bool) or not isinstance(index, int):
            issues.append(_issue('page_index_invalid', pointer)); continue
        candidates = [m for m in mappings if m['parserPageIndex'] == index]
        if len(candidates) != 1 or index in observed:
            issues.append(_issue('page_identity_unmapped_or_duplicate', pointer)); continue
        observed.add(index)
        mapping = candidates[0]
        input_asset = mapping.get('inputArtifactName')
        if not isinstance(input_asset, str) or input_asset not in captured_artifacts or captured_artifacts[input_asset].get('contentOmitted'):
            issues.append(_issue('page_input_asset_not_bound', pointer, physicalPageNo=mapping['physicalPageNo']))
        model = decoded.get('model_json') if parser == 'mineru' else None
        model_map = model.get('page_index_map') if isinstance(model, dict) else None
        declared_input_index = (model_map[ordinal] if isinstance(model_map, list) and ordinal < len(model_map) else
                                 model_map.get(str(ordinal), model_map.get(ordinal)) if isinstance(model_map, dict) else None)
        input_page = mapping.get('providerInputPageNo')
        input_map_status = 'caller_bound_input_page_to_original_page'
        if provider_input['captureStatus'] != 'actual_input_bytes_captured' or input_page is None:
            input_map_status = 'unknown'
            issues.append(_issue('provider_input_page_mapping_unbound', pointer,
                                 physicalPageNo=mapping['physicalPageNo'], providerInputPageNo=input_page))
        elif input_page > provider_input['pageCount']:
            issues.append(_issue('provider_input_page_out_of_input_document', pointer,
                                 physicalPageNo=mapping['physicalPageNo'], providerInputPageNo=input_page))
            observed.remove(index)
            continue
        if provider_input.get('mediaType', '').startswith('image/') and provider_input.get('inputArtifactName') != input_asset:
            input_map_status = 'unknown'
            issues.append(_issue('provider_image_input_asset_differs_from_page_raster', pointer))
        if declared_input_index is not None and input_map_status != 'unknown' and (
                isinstance(declared_input_index, bool) or not isinstance(declared_input_index, int) or
                declared_input_index + 1 != input_page):
            issues.append(_issue('provider_input_page_map_disagrees', pointer,
                                 physicalPageNo=mapping['physicalPageNo'], providerInputPageNo=input_page,
                                 providerInputPageIndex=declared_input_index))
            observed.remove(index)
            continue
        page_id = run_id + '/page/' + str(mapping['physicalPageNo'])
        # Coordinates are a declared provider frame, not guessed from magnitude.
        frame = mapping['providerBboxFrame']
        if frame not in ('raster', 'page', 'normalized', 'normalized_1000'):
            raise ParserEvidenceError('Unsupported declared providerBboxFrame')
        page = {'pageId': page_id, 'physicalPageNo': mapping['physicalPageNo'],
                'parserPageIndex': index, 'mapping': copy.deepcopy(mapping), 'blocks': [],
                'providerPageIndexObservation': {'rawPageIndex': raw_page_index, 'status': index_status},
                'providerInputPageNo': input_page, 'inputToOriginalMappingStatus': input_map_status,
                'providerPageIndexMapObservation': {'rawPageIndex': copy.deepcopy(declared_input_index),
                    'meaning': provider_input['pageIndexMapMeaning'], 'status': input_map_status},
                'qualityStatus': 'needs_review', 'provenance': {'output': key, 'pointer': pointer}}
        if isinstance(input_asset, str) and input_asset in captured_artifacts:
            page['inputArtifact'] = {'name': input_asset, 'sha256': captured_artifacts[input_asset]['sha256'],
                                     'captureStatus': captured_artifacts[input_asset]['projectionStatus']}
        if not isinstance(mapping.get('originalPageTransform'), dict):
            issues.append(_issue('original_page_transform_not_supplied', pointer))
            page['originalPageGeometryStatus'] = 'unknown'
        else:
            page['originalPageGeometryStatus'] = 'transform_declared_not_verified'
        blocks_key = 'parsing_res_list' if parser == 'paddleocr-vl' else next(
            (k for k in ('blocks', 'semantic_blocks') if isinstance(value.get(k), list)), None)
        blocks = value.get(blocks_key) if blocks_key else None
        if not isinstance(blocks, list):
            issues.append(_issue('page_blocks_unavailable', pointer))
            page['coverageStatus'] = 'unknown'
            pages.append(page); continue
        page['coverageStatus'] = 'observed_not_verified' if blocks else 'empty_observation_not_verified_blank'
        for n, block in enumerate(blocks):
            bp = pointer + '/' + blocks_key + '/' + str(n)
            if not isinstance(block, dict):
                issues.append(_issue('block_not_object', bp)); continue
            bid = page_id + '/block/' + str(n)
            label = block.get('block_label', block.get('type', block.get('label', 'unknown')))
            text, text_field = _text(block)
            box = _box(block.get('block_bbox', block.get('bbox')), frame, mapping, bp, issues)
            order = block.get('block_order', block.get('index'))
            item = {'blockId': bid, 'providerBlockId': copy.deepcopy(block.get('block_id', block.get('id'))),
                    'label': copy.deepcopy(label), 'text': text, 'bbox': box,
                    'readingOrder': {'declared': copy.deepcopy(order), 'outputSequence': n,
                                     'status': 'provider_declared_unverified' if order is not None else 'sequence_only_unverified'},
                    'qualityStatus': 'needs_review', 'native': False,
                    'confidence': copy.deepcopy(block.get('confidence', block.get('score'))),
                    'declaredContentObservations': copy.deepcopy(block.get('content')) if isinstance(block.get('content'), list) else None,
                    'provenance': {'output': key, 'pointer': bp, 'textField': text_field},
                    'relationships': {k: copy.deepcopy(block[k]) for k in
                        ('continuation', 'cross_page', 'relations', 'merge_with', 'parent_id') if k in block}}
            if label in ('table', 'table_body'):
                item['table'] = _table(block, bid, bp, issues)
                item['table']['provenance']['output'] = key
                for cell in item['table']['cells']:
                    cell['provenance']['output'] = key
            if text is None and 'table' in item and item['table']['html'] is not None:
                item['text'] = item['table']['html']
                item['provenance']['textField'] = 'whole_table_html'
            if item['text'] is None:
                issues.append(_issue('text_projection_unavailable', bp))
            page['blocks'].append(item)
        pages.append(page)
    for mapping in mappings:
        if mapping['parserPageIndex'] not in observed:
            issues.append(_issue('requested_page_not_observed', '/', physicalPageNo=mapping['physicalPageNo']))
    result = {'schema': SCHEMA, 'kind': 'parser_run', 'runId': run_id, 'parser': parser,
            'source': source, 'identity': identity, 'requestPageMap': mappings,
            'providerInputIdentity': provider_input,
            'rawOutputs': raw, 'artifacts': captured_artifacts, 'requiredArtifacts': copy.deepcopy(required_artifacts),
            'captureIssues': copy.deepcopy(capture_issues), 'terminal': terminal,
            'captureStatus': 'incomplete' if issues else 'supplied_outputs_and_assets_captured',
            'pages': pages, 'issues': issues,
            'qualityStatus': 'needs_review', 'humanConfirmed': False,
            'canonicalSelection': None,
            'limitations': ['A parser observation is not reference-verified content',
                'Confidence and agreement are not correctness', 'No observed text does not prove a blank page/cell',
                'Derived raster coordinates require an original-page transform',
                'All supplied raw output/asset records are retained; absent early outputs or assets remain absent']}
    result['captureManifest'] = _capture_manifest(result)
    result['completeness'] = _run_completeness(result, artifact_store)
    result['runContentSha256'] = _hash(result)
    return result


def _dual_completeness(runs, artifact_store=None, required_physical_pages=None):
    per_run = {run['parser']: _run_completeness(run, artifact_store) for run in runs}
    sets = [{m['physicalPageNo'] for m in run['requestPageMap']} for run in runs]
    reasons = []
    if sets[0] != sets[1]:
        reasons.append('dual_request_physical_page_sets_differ')
    required = sets[0] if required_physical_pages is None else set(required_physical_pages)
    if any(not required.issubset(pages) for pages in sets):
        reasons.append('required_physical_pages_not_fully_requested')
    for parser, completeness in per_run.items():
        reasons.extend(parser + ':' + reason for reason in completeness['reasons'])
    eligible = not reasons
    return {'status': 'complete' if eligible else 'incomplete' if any(
                value['status'] == 'incomplete' for value in per_run.values()) or sets[0] != sets[1]
                or 'required_physical_pages_not_fully_requested' in reasons else 'unverified',
            'eligibleForCanonical': eligible, 'reasons': sorted(set(reasons)),
            'requiredPhysicalPages': sorted(required),
            'requestPhysicalPagesByParser': {run['parser']: sorted(pages) for run, pages in zip(runs, sets)},
            'perParser': per_run}


def assert_complete_dual(bundle, required_physical_pages=None, *, artifact_store=None):
    """Verify immutable bytes, identities, page sets and explicit terminal states.

    This is a material/transport gate. It never approves semantic content,
    blanks, table topology, reading order, or a reviewer supplied by the caller.
    """
    if not isinstance(bundle, dict) or bundle.get('schema') != SCHEMA or bundle.get('kind') != 'dual_parser_bundle':
        raise ParserEvidenceError('Supply a dual parser evidence bundle')
    if bundle.get('bundleSha256') != _hash({k: v for k, v in bundle.items() if k != 'bundleSha256'}):
        raise ParserEvidenceError('Immutable bundle content hash does not match')
    runs = bundle.get('runs')
    if not isinstance(runs, list) or len(runs) != 2 or {run.get('parser') for run in runs} != set(PARSERS):
        raise ParserEvidenceError('A complete dual bundle requires both parser runs')
    if required_physical_pages is not None and (not isinstance(required_physical_pages, (list, tuple)) or
            any(isinstance(page, bool) or not isinstance(page, int) or page < 1 for page in required_physical_pages) or
            len(set(required_physical_pages)) != len(required_physical_pages)):
        raise ParserEvidenceError('Required physical pages must be unique positive integers')
    for run in runs:
        if run.get('runContentSha256') != _hash({k: v for k, v in run.items() if k != 'runContentSha256'}):
            raise ParserEvidenceError('Immutable run content hash does not match')
        if run.get('source') != bundle.get('source'):
            raise ParserEvidenceError('Dual run source identities do not match')
        if run.get('captureManifest') != _capture_manifest(run):
            raise ParserEvidenceError('Capture manifest does not bind current source/parser/config/artifacts')
    result = _dual_completeness(runs, artifact_store, required_physical_pages)
    if not result['eligibleForCanonical']:
        raise ParserEvidenceError('Dual evidence is not complete: ' + ', '.join(result['reasons']))
    return result


def build_dual_parser_evidence(runs, *, route_acceptance_manifest=None, artifact_store=None):
    """Keep both runs intact; only an identity-bound accepted manifest selects.

    A manifest is an explicit supplied evaluation/review record, not a proof of
    its reviewer's authenticity. The caller owns that trust decision. Its routes
    bind source/revision, exact run, config/model identity, observed layout
    features and accepted reference-evaluation (or Codex original visual-review)
    identity. Invalid/stale routes stay unknown. There is no confidence fallback.
    """
    if not isinstance(runs, list) or len(runs) != 2:
        raise ParserEvidenceError('A dual evidence bundle requires two parser runs')
    values = copy.deepcopy(runs)
    if {r.get('parser') for r in values} != set(PARSERS) or any(
            r.get('schema') != SCHEMA or r.get('kind') != 'parser_run' for r in values):
        raise ParserEvidenceError('Supply one normalized run from each full parser')
    for run in values:
        content = {k: v for k, v in run.items() if k != 'runContentSha256'}
        if run.get('runContentSha256') != _hash(content):
            raise ParserEvidenceError('Normalized run changed after capture; its immutable content hash does not match')
    source = values[0]['source']
    if any(r['source'] != source for r in values):
        raise ParserEvidenceError('Dual runs must bind the exact same source identity')
    observations = []
    for physical in sorted({p['physicalPageNo'] for run in values for p in run['pages']} |
                           {m['physicalPageNo'] for run in values for m in run['requestPageMap']}):
        per_parser = {}
        for run in values:
            found = [p for p in run['pages'] if p['physicalPageNo'] == physical]
            if found:
                per_parser[run['parser']] = {'pageId': found[0]['pageId'], 'projection': [
                    {'label': b['label'], 'text': b['text'], 'readingOrder': b['readingOrder'],
                     'bbox': _comparison_box(b['bbox'], run, found[0]),
                     'table': {'html': b['table']['html'], 'cells': [{k: c[k] for k in
                        ('row', 'column', 'rowSpan', 'columnSpan', 'text')} for c in b['table']['cells']]}
                     if 'table' in b else None} for b in found[0]['blocks']]}
        comparable = len(per_parser) == 2
        projections = [p['projection'] for p in per_parser.values()]
        equal = comparable and projections[0] == projections[1]
        observations.append({'physicalPageNo': physical, 'parserPages': per_parser,
            'comparisonStatus': 'same_observation_unverified' if equal else
                'different_observation_unverified' if comparable else 'missing_parser_observation',
            'selectionStatus': 'unknown', 'selectedParser': None,
            'reason': 'No frozen reference evaluation authorizes structure routing',
            'tablePolicy': 'whole_structure_group_only; no cell fusion'})
    completeness = _dual_completeness(values, artifact_store)
    selected, routing = _accepted_routes(values, source, route_acceptance_manifest, completeness)
    result = {'schema': SCHEMA, 'kind': 'dual_parser_bundle', 'source': source, 'runs': values,
            'observations': observations, 'canonicalSelection': selected or None,
            'dualCompleteness': completeness,
            'routeAcceptance': routing, 'qualityStatus': 'needs_review',
            'humanConfirmed': False, 'captureStatus': 'incomplete' if any(
                run['captureStatus'] == 'incomplete' for run in values) else 'supplied_outputs_and_assets_captured'}
    result['bundleSha256'] = _hash(result)
    return result


def project_bbox_to_original(run, page, box):
    """Project four corners through an explicitly bound affine transform.

    Matrix [a,b,c,d,e,f] means x'=a*x+b*y+c, y'=d*x+e*y+f.
    Units are declared by both frames; normalized vendor boxes are first
    converted into the input frame. Crop/rotation are therefore ordinary
    affine transforms, with all input/source identities preserved. Unknown or
    out-of-page transforms remain unknown; no clipping or unit guessing.
    """
    def unknown(reason):
        return {'status': 'unknown', 'reason': reason, 'xyxyNormalized': None,
                'polygonOriginal': None, 'targetFrame': None}
    if not isinstance(run, dict) or not isinstance(page, dict) or not isinstance(box, dict):
        return unknown('geometry_not_supplied')
    mapping = page.get('mapping', {})
    if not isinstance(mapping, dict):
        return unknown('page_mapping_invalid')
    transform = mapping.get('originalPageTransform')
    if not isinstance(transform, dict) or transform.get('kind') not in ('identity', 'affine'):
        return unknown('original_page_transform_not_explicit')
    source_frame, target = transform.get('inputFrame'), transform.get('targetFrame')
    if not isinstance(source_frame, dict) or not isinstance(target, dict):
        return unknown('transform_frames_and_identities_not_explicit')
    if source_frame.get('coordinateFrame') not in ('raster', 'page') or target.get('coordinateFrame') != 'original_page':
        return unknown('transform_frame_unknown')
    for frame in (source_frame, target):
        if frame.get('coordinateUnit') not in ('pixel', 'pt') or any(isinstance(frame.get(key), bool) or
                not isinstance(frame.get(key), (int, float)) or not math.isfinite(frame[key]) or frame[key] <= 0
                for key in ('width', 'height')):
            return unknown('transform_units_or_dimensions_invalid')
    name = source_frame.get('artifactName')
    artifact = run.get('artifacts', {}).get(name) if isinstance(name, str) else None
    page_asset = page.get('inputArtifact', {})
    if not isinstance(artifact, dict) or artifact.get('contentOmitted') or artifact.get('projectionStatus') != 'available' or (
            source_frame.get('sha256') != artifact.get('sha256') or page_asset.get('sha256') != artifact.get('sha256') or
            page_asset.get('name') != name or mapping.get('inputArtifactName') != name):
        return unknown('transform_input_bytes_identity_not_bound')
    if page.get('inputToOriginalMappingStatus') != 'caller_bound_input_page_to_original_page':
        return unknown('provider_input_to_original_mapping_unbound')
    if (target.get('sourceSha256') != run.get('source', {}).get('sourceSha256') or
            target.get('physicalPageNo') != page.get('physicalPageNo') or
            mapping.get('physicalPageNo') != page.get('physicalPageNo') or
            not any(m == mapping for m in run.get('requestPageMap', []))):
        return unknown('transform_original_source_or_page_identity_not_bound')
    if (source_frame['width'] != mapping.get('width') or source_frame['height'] != mapping.get('height') or
            source_frame['coordinateUnit'] != mapping.get('coordinateUnit') or
            box.get('pageWidth') != source_frame['width'] or box.get('pageHeight') != source_frame['height'] or
            box.get('origin') != 'top-left' or box.get('coordinateFrame') != mapping.get('providerBboxFrame')):
        return unknown('bbox_input_frame_disagrees')
    matrix = [1, 0, 0, 0, 1, 0] if transform['kind'] == 'identity' else transform.get('matrix')
    if not isinstance(matrix, list) or len(matrix) != 6 or any(isinstance(v, bool) or
            not isinstance(v, (int, float)) or not math.isfinite(v) for v in matrix):
        return unknown('affine_matrix_invalid')
    a, b, c, d, e, f = matrix
    if a * e - b * d == 0:
        return unknown('affine_matrix_singular')
    if transform['kind'] == 'identity' and any(source_frame[key] != target[key] for key in ('coordinateUnit', 'width', 'height')):
        return unknown('identity_transform_cannot_change_units_or_dimensions')
    coords = box.get('xyxy')
    if not isinstance(coords, list) or len(coords) != 4 or any(isinstance(v, bool) or
            not isinstance(v, (int, float)) or not math.isfinite(v) for v in coords):
        return unknown('bbox_invalid')
    frame = box['coordinateFrame']
    sx, sy = ((source_frame['width'], source_frame['height']) if frame == 'normalized' else
              (source_frame['width'] / 1000, source_frame['height'] / 1000) if frame == 'normalized_1000' else (1, 1))
    expected_unit = 'ratio' if frame == 'normalized' else 'ratio_1000' if frame == 'normalized_1000' else source_frame['coordinateUnit']
    if box.get('coordinateUnit') != expected_unit or (frame in ('raster', 'page') and frame != source_frame['coordinateFrame']):
        return unknown('bbox_units_or_frame_disagree')
    x0, y0, x1, y1 = coords[0] * sx, coords[1] * sy, coords[2] * sx, coords[3] * sy
    if not (0 <= x0 <= x1 <= source_frame['width'] and 0 <= y0 <= y1 <= source_frame['height']):
        return unknown('bbox_out_of_input_frame')
    polygon = [[a * x + b * y + c, d * x + e * y + f] for x, y in
               ((x0, y0), (x1, y0), (x1, y1), (x0, y1))]
    if any(not math.isfinite(v) for point in polygon for v in point):
        return unknown('affine_projection_nonfinite')
    if any(not (0 <= x <= target['width'] and 0 <= y <= target['height']) for x, y in polygon):
        return unknown('affine_projection_outside_original_page')
    xs, ys = [p[0] for p in polygon], [p[1] for p in polygon]
    xyxy = [min(xs), min(ys), max(xs), max(ys)]
    return {'status': 'verified', 'reason': 'explicit_input_and_source_bound_transform',
            'xyxyNormalized': [xyxy[0] / target['width'], xyxy[1] / target['height'],
                               xyxy[2] / target['width'], xyxy[3] / target['height']],
            'xyxyOriginal': xyxy, 'polygonOriginal': polygon, 'targetFrame': copy.deepcopy(target),
            'transformSha256': _hash(transform)}


def _comparison_box(box, run, page):
    projected = project_bbox_to_original(run, page, box)
    return {'status': 'verified_transform', 'normalizedXYXY': projected['xyxyNormalized']} if projected['status'] == 'verified' else {
        'status': 'unmapped_to_original_page', 'box': copy.deepcopy(box)}


def _accepted_routes(runs, source, manifest, completeness):
    if manifest is None:
        return [], {'status': 'unknown', 'reason': 'No route acceptance manifest supplied', 'routes': []}
    ledger = {'status': 'unknown', 'manifest': copy.deepcopy(manifest), 'manifestSha256': _hash(manifest), 'routes': []}
    if not isinstance(manifest, dict) or manifest.get('schema') != 'consense.parser-route-acceptance.v1' or not isinstance(manifest.get('routes'), list):
        ledger['reason'] = 'Unsupported manifest schema'
        return [], ledger
    selected = []
    used = []
    for ordinal, route in enumerate(manifest['routes']):
        reasons = []
        if not completeness['eligibleForCanonical']:
            reasons.append('dual_material_or_terminal_completeness_not_verified')
        if not isinstance(route, dict):
            ledger['routes'].append({'ordinal': ordinal, 'status': 'unknown', 'reasons': ['route_not_object']})
            continue
        run = next((r for r in runs if r['runId'] == route.get('runId')), None)
        acceptance = route.get('acceptance', {})
        if not isinstance(acceptance, dict):
            acceptance = {}
        if route.get('sourceSha256') != source['sourceSha256'] or route.get('sourceRevisionSha256') != source['sourceRevisionSha256']:
            reasons.append('source_or_revision_identity_mismatch')
        if run is None or route.get('parserIdentitySha256') != run['identity']['identitySha256'] or route.get('configSha256') != run['identity']['configSha256']:
            reasons.append('parser_or_config_identity_mismatch')
        if run is not None and run['identity']['modelIdentityStatus'] != 'declared_pinned':
            reasons.append('model_identity_not_pinned')
        if acceptance.get('status') != 'accepted' or acceptance.get('acceptedBy') not in ('codex', 'user') or acceptance.get('method') not in ('reference_evaluation', 'codex_original_visual_review'):
            reasons.append('reference_or_visual_review_not_accepted')
        if not acceptance.get('referenceVersion') or not isinstance(acceptance.get('evidenceSha256'), str) or not re.fullmatch(r'[0-9a-fA-F]{64}', acceptance['evidenceSha256']):
            reasons.append('accepted_reference_identity_missing')
        if not isinstance(route.get('layoutFeatures'), dict) or not route['layoutFeatures'] or not isinstance(route.get('layoutFeatureEvidenceSha256'), str) or not re.fullmatch(r'[0-9a-fA-F]{64}', route['layoutFeatureEvidenceSha256']):
            reasons.append('layout_features_or_evidence_missing')
        found = [] if run is None else [(p, b) for p in run['pages'] for b in p['blocks'] if b['blockId'] == route.get('blockId')]
        if len(found) != 1:
            reasons.append('exact_block_identity_missing')
        else:
            page, block = found[0]
            if page.get('inputToOriginalMappingStatus') != 'caller_bound_input_page_to_original_page':
                reasons.append('provider_input_to_original_mapping_unbound')
            if page['originalPageGeometryStatus'] == 'unknown':
                reasons.append('original_page_transform_missing')
            if not isinstance(page.get('inputArtifact'), dict) or page['inputArtifact'].get('captureStatus') != 'available':
                reasons.append('original_page_input_asset_not_captured')
            if route.get('scope') == 'table_group':
                if 'table' not in block or route.get('tableGroupId') != block['table']['tableGroupId']:
                    reasons.append('entire_table_group_identity_missing')
                elif block['table']['structureStatus'] == 'unknown':
                    reasons.append('table_structure_unresolved')
            elif route.get('scope') != 'region' or 'table' in block:
                reasons.append('region_route_cannot_select_or_mix_table_cells')
            geometry = block.get('bbox')
            if geometry is None or block.get('text') is None:
                reasons.append('exact_region_geometry_or_text_missing')
                target = None
            else:
                projected = project_bbox_to_original(run, page, geometry)
                if projected['status'] != 'verified':
                    reasons.append('original_transform_not_projected:' + projected['reason'])
                target = (page['physicalPageNo'], projected['xyxyNormalized'])
                for other_page, other in used if target[1] is not None else []:
                    box = target[1]
                    if other_page == target[0] and min(box[2], other[2]) > max(box[0], other[0]) and min(box[3], other[3]) > max(box[1], other[1]):
                        reasons.append('overlapping_region_selection')
                        break
        status = 'unknown' if reasons else 'accepted'
        ledger['routes'].append({'ordinal': ordinal, 'status': status, 'reasons': reasons})
        if not reasons:
            used.append(target)
            selected.append({'scope': route['scope'], 'runId': run['runId'], 'blockId': block['blockId'],
                'physicalPageNo': page['physicalPageNo'], 'block': copy.deepcopy(block),
                'manifestSha256': ledger['manifestSha256'], 'acceptance': copy.deepcopy(acceptance),
                'selectedBy': acceptance['acceptedBy'], 'qualityStatus': 'explicit_review_selection',
                'humanConfirmed': acceptance['acceptedBy'] == 'user'})
    ledger['status'] = 'accepted' if selected and all(r['status'] == 'accepted' for r in ledger['routes']) else 'partially_accepted' if selected else 'unknown'
    return selected, ledger


def save_evidence_bundle(bundle, destination, *, artifact_store=None):
    """Atomically save an immutable JSON ledger; never replace a different bundle.

    Raw byte outputs are embedded losslessly or stored by verified descriptors.
    External records require their ArtifactStore and are reverified on save.
    """
    if bundle.get('schema') != SCHEMA:
        raise ParserEvidenceError('Unsupported evidence schema')
    digest_key = 'bundleSha256' if bundle.get('kind') == 'dual_parser_bundle' else 'runContentSha256'
    if bundle.get(digest_key) != _hash({k: v for k, v in bundle.items() if k != digest_key}):
        raise ParserEvidenceError('Cannot save changed immutable evidence content')
    runs = bundle.get('runs', []) if bundle.get('kind') == 'dual_parser_bundle' else [bundle]
    for run in runs:
        for records in (run.get('rawOutputs', {}), run.get('artifacts', {})):
            for record in records.values():
                _verify_record(record, artifact_store)
    raw = _json_bytes(bundle)
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if destination.read_bytes() != raw:
            raise ParserEvidenceError('An immutable evidence destination already contains different bytes')
        return {'path': str(destination), 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
    fd, temporary = tempfile.mkstemp(prefix=destination.name + '.', dir=destination.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        # link is atomic and does not overwrite a concurrent writer's ledger.
        try:
            os.link(temporary, destination)
        except FileExistsError:
            if destination.read_bytes() != raw:
                raise ParserEvidenceError('Concurrent evidence destination contains different bytes')
    finally:
        Path(temporary).unlink(missing_ok=True)
    return {'path': str(destination), 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
