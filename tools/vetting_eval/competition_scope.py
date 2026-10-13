"""Apply declared competition-source scope without rewriting original chunks.

The SL policy binds one original PDF SHA and physical page partition. Text,
captions, references and appendix prefixes never classify a source. A chunk
spanning both eligible and excluded pages must be split upstream; this module
cannot safely map a text substring onto a source page and never attempts it.
Unknown ordinary sources remain available with an explicit unclassified trace.
No models, services, indexes or framework objects are initialized here.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re


SCHEMA = 'consense.sl-library-scope.v1'
POLICY_VERSION = 'consense.competition-scope.v2'
ORIGINAL_HASH_MEANING = 'original_file_bytes_sha256'
MODULES = frozenset({'drafting', 'vetting', 'advisory'})
SOURCE_PURPOSES = frozenset({'standard_template', 'project_document', 'reference_standard',
                            'project_fact', 'package_manifest', 'evaluation_answer', 'unknown'})
EXCLUDED_CLASSES = frozenset({'pricing_bq', 'pricing_sor', 'pricing_general_summary',
                              'works_related_pss', 'sl_nontechnical'})
DECLARED_INCLUDED_CLASSES = frozenset({'project_pre', 'notes_to_tenderers', 'notice_to_tenderers',
                                      'special_conditions_of_tender', 'standard_conditions_of_tender',
                                      'special_conditions_of_contract'})


class CompetitionScopeError(ValueError):
    """Scope could not be established; caller must not index a partial result."""

    def __init__(self, code, *, chunk_id=None, details=None):
        self.code = code
        self.chunk_id = chunk_id
        self.details = details or {}
        suffix = '' if chunk_id is None else ': ' + str(chunk_id)
        super().__init__(code + suffix)


def _json_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('utf-8')


def _digest(value, field):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-fA-F]{64}', value):
        raise CompetitionScopeError('invalid_sha256', details={'field': field})
    return value.lower()


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CompetitionScopeError('duplicate_manifest_field', details={'field': key})
        result[key] = value
    return result


def _number(value, field):
    if isinstance(value, str) and re.fullmatch(r'P?[1-9][0-9]*', value):
        return int(value.removeprefix('P'))
    if isinstance(value, int) and not isinstance(value, bool) and value > 0:
        return value
    raise CompetitionScopeError('physical_page_number_missing_or_invalid', details={'field': field})


def _span(item, page_count):
    if not isinstance(item, dict):
        raise CompetitionScopeError('invalid_manifest_page_span')
    start = _number(item.get('physicalPageStart'), 'physicalPageStart')
    end = _number(item.get('physicalPageEnd'), 'physicalPageEnd')
    if end < start or end > page_count:
        raise CompetitionScopeError('invalid_manifest_page_span')
    if item.get('pageCount', end - start + 1) != end - start + 1:
        raise CompetitionScopeError('manifest_span_count_mismatch')
    return start, end


def _pages(spans):
    return {page for start, end in spans for page in range(start, end + 1)}


def _validate_declared_page(holder, *, required, label):
    declared = [(name, holder[name]) for name in ('physicalPageNo', 'pageNo')
                if name in holder and holder[name] is not None]
    if not declared:
        if required:
            raise CompetitionScopeError('physical_page_number_missing_or_invalid', details={'field': label})
        return None
    values = {_number(value, label + '.' + name) for name, value in declared}
    if len(values) != 1:
        raise CompetitionScopeError('physical_page_identity_conflict', details={'field': label})
    return values.pop()


def _original_hash(holder, *, required):
    # Java sourceHash identifies canonical text/structure, not file bytes. It
    # is never an original-file identity without an explicit meaning marker.
    declared = [(field, holder[field]) for field in ('originalSourceSha256', 'sourceSha256')
                if holder.get(field) is not None]
    if holder.get('originalHashMeaning') == ORIGINAL_HASH_MEANING and holder.get('sourceHash') is not None:
        declared.append(('sourceHash', holder['sourceHash']))
    if not declared:
        if required:
            raise CompetitionScopeError('sl_original_source_identity_missing', chunk_id=holder.get('id'))
        return None
    try:
        hashes = {_digest(value, field) for field, value in declared}
    except CompetitionScopeError as error:
        raise CompetitionScopeError('sl_original_source_hash_invalid', chunk_id=holder.get('id')) from error
    if len(hashes) != 1:
        raise CompetitionScopeError('sl_original_source_identity_conflict', chunk_id=holder.get('id'))
    return hashes.pop()


def _module_exclusion(chunk, classes, module):
    """Apply supplied source identity, never infer a template from its filename."""
    purpose = chunk.get('sourcePurpose')
    if purpose is not None and (not isinstance(purpose, str) or purpose not in SOURCE_PURPOSES):
        raise CompetitionScopeError('invalid_source_purpose', chunk_id=chunk.get('id'))
    eligible = chunk.get('eligibleModules')
    if eligible is not None and (not isinstance(eligible, list)
            or any(not isinstance(value, str) or value not in MODULES for value in eligible)
            or len(eligible) != len(set(eligible))):
        raise CompetitionScopeError('invalid_eligible_modules', chunk_id=chunk.get('id'))
    template = (purpose == 'standard_template' or 'drafting_standard_template' in classes
                or chunk.get('category') == 'STANDARD_TEMPLATE'
                and chunk.get('fileKey') in ('NTT', 'SCT', 'SCC'))
    answer = purpose == 'evaluation_answer' or 'evaluation_answer' in classes
    if answer:
        return 'evaluation_answer_isolated'
    if template and purpose not in (None, 'standard_template', 'unknown'):
        raise CompetitionScopeError('conflicting_declared_source_purpose', chunk_id=chunk.get('id'))
    if template and module in ('vetting', 'advisory'):
        return 'standard_template_drafting_only'
    if eligible is not None and module not in eligible:
        return 'module_not_eligible'
    return None


@dataclass(frozen=True)
class CompetitionScopePolicy:
    """Immutable policy; apply returns each included original object unchanged."""

    manifest_sha256: str
    manifest_content_sha256: str
    source_sha256: str
    source_page_count: int
    scope_version: str
    included_spans: tuple[tuple[int, int], ...]
    metadata_spans: tuple[tuple[int, int], ...]
    excluded_spans: tuple[tuple[int, int], ...]

    @property
    def policyIdentity(self):
        identity = {'schema': POLICY_VERSION, 'scopeVersion': self.scope_version,
                    'manifestSha256': self.manifest_sha256,
                    'manifestContentSha256': self.manifest_content_sha256,
                    'slSourceSha256': self.source_sha256, 'slSourcePageCount': self.source_page_count,
                    'slSourceHashMeaning': ORIGINAL_HASH_MEANING,
                    'slIncludedPageSpans': [{'physicalPageStart': start, 'physicalPageEnd': end}
                                            for start, end in self.included_spans],
                    'declaredExcludedSourceClasses': sorted(EXCLUDED_CLASSES),
                    'moduleIsolation': {'modules': sorted(MODULES),
                        'standardTemplates': 'drafting_only', 'evaluationAnswers': 'isolated',
                        'filenameClassification': False},
                    'keywordClassification': False}
        identity['policySha256'] = hashlib.sha256(_json_bytes(identity)).hexdigest()
        return identity

    def _sl_pages(self, chunk):
        chunk_id = chunk.get('id')
        source_hash = _original_hash(chunk, required=True)
        if source_hash != self.source_sha256:
            raise CompetitionScopeError('sl_source_hash_does_not_match_manifest', chunk_id=chunk_id,
                                        details={'expectedSourceSha256': self.source_sha256,
                                                 'observedSourceSha256': source_hash})
        top_page = _validate_declared_page(chunk, required=False, label='chunk')
        parts = chunk.get('parts')
        if parts is not None and not isinstance(parts, list):
            raise CompetitionScopeError('sl_parts_must_be_page_mapped_objects', chunk_id=chunk_id)
        pages = []
        for index, part in enumerate(parts or []):
            if not isinstance(part, dict):
                raise CompetitionScopeError('sl_parts_must_be_page_mapped_objects', chunk_id=chunk_id)
            part_original_hash = _original_hash(part, required=False)
            if part_original_hash is not None and part_original_hash != source_hash:
                raise CompetitionScopeError('sl_part_original_source_hash_conflict', chunk_id=chunk_id)
            if part.get('sourceHash') is not None and chunk.get('sourceHash') is not None and part['sourceHash'] != chunk['sourceHash']:
                raise CompetitionScopeError('sl_part_canonical_source_hash_conflict', chunk_id=chunk_id)
            if part.get('documentId') is not None and chunk.get('documentId') is not None and str(part['documentId']) != str(chunk['documentId']):
                raise CompetitionScopeError('sl_part_document_identity_conflict', chunk_id=chunk_id)
            if part.get('fileKey') is not None and part['fileKey'] != chunk.get('fileKey', part['fileKey']):
                raise CompetitionScopeError('sl_part_file_key_conflict', chunk_id=chunk_id)
            pages.append(_validate_declared_page(part, required=True, label=f'parts[{index}]'))
        if not pages:
            if top_page is None:
                raise CompetitionScopeError('sl_physical_page_mapping_missing', chunk_id=chunk_id)
            pages = [top_page]
        elif top_page is not None and top_page not in pages:
            raise CompetitionScopeError('sl_chunk_page_conflicts_with_parts', chunk_id=chunk_id)
        if any(page > self.source_page_count for page in pages):
            raise CompetitionScopeError('sl_physical_page_outside_source', chunk_id=chunk_id)
        return sorted(set(pages))

    def _sl_status(self, page):
        if any(start <= page <= end for start, end in self.included_spans):
            return 'included_technical_library'
        if any(start <= page <= end for start, end in self.metadata_spans):
            return 'metadata_only'
        if any(start <= page <= end for start, end in self.excluded_spans):
            return 'excluded_sl_preliminaries'
        raise CompetitionScopeError('sl_page_not_classified_by_manifest')

    def apply(self, chunks, *, module='vetting'):
        if not isinstance(module, str) or module not in MODULES:
            raise CompetitionScopeError('invalid_target_module')
        if not isinstance(chunks, (list, tuple)):
            raise CompetitionScopeError('chunks_must_be_ordered_objects')
        included, excluded, unclassified, decisions = [], [], [], []
        for ordinal, chunk in enumerate(chunks):
            if not isinstance(chunk, dict):
                raise CompetitionScopeError('chunk_must_be_object', details={'ordinal': ordinal})
            classes = []
            for field in ('sourceClass', 'documentClass'):
                value = chunk.get(field)
                if value is not None and (not isinstance(value, str) or not value.strip()):
                    raise CompetitionScopeError('invalid_declared_source_class', chunk_id=chunk.get('id'), details={'field': field})
                if value is not None:
                    classes.append(value.strip())
            excluded_classes = sorted(set(classes) & EXCLUDED_CLASSES)
            module_exclusion = _module_exclusion(chunk, classes, module)
            if excluded_classes and set(classes) & DECLARED_INCLUDED_CLASSES:
                raise CompetitionScopeError('conflicting_declared_source_scope', chunk_id=chunk.get('id'),
                                            details={'declaredClasses': classes})
            trace = {'ordinal': ordinal, 'chunkId': chunk.get('id'), 'documentId': chunk.get('documentId'),
                     'fileKey': chunk.get('fileKey'), 'sourceHash': chunk.get('sourceHash'),
                     'declaredClasses': classes, 'physicalPages': [], 'targetModule': module,
                     'sourcePurpose': chunk.get('sourcePurpose'),
                     'eligibleModules': None if chunk.get('eligibleModules') is None
                         else list(chunk['eligibleModules'])}
            is_sl = chunk.get('fileKey') == 'SL' or 'specification_library' in classes
            sl_status = None
            if is_sl:
                try:
                    pages = self._sl_pages(chunk)
                except CompetitionScopeError as error:
                    raise CompetitionScopeError(error.code, chunk_id=chunk.get('id'), details=error.details) from error
                trace['originalSourceSha256'] = _original_hash(chunk, required=True)
                trace['physicalPages'] = pages
                statuses = sorted({self._sl_status(page) for page in pages})
                if len(statuses) != 1:
                    raise CompetitionScopeError('mixed_scope_chunk_requires_upstream_split', chunk_id=chunk.get('id'),
                                                details={'physicalPages': pages, 'scopeStates': statuses,
                                                         'originalContentChanged': False})
                sl_status = statuses[0]
            if module_exclusion:
                trace.update(reason=module_exclusion, scopeStatus='excluded')
                excluded.append(trace)
            elif excluded_classes:
                trace.update(reason='declared_source_class_excluded', excludedClasses=excluded_classes,
                             scopeStatus='excluded')
                excluded.append(trace)
            elif sl_status in ('metadata_only', 'excluded_sl_preliminaries'):
                trace.update(reason=sl_status, scopeStatus='excluded')
                excluded.append(trace)
            else:
                included.append(chunk)
                known = sl_status == 'included_technical_library' or bool(classes) and all(
                    value in DECLARED_INCLUDED_CLASSES for value in classes)
                trace.update(reason=sl_status or ('declared_project_source_included' if known else 'ordinary_source_unclassified'),
                             scopeStatus='included' if known else 'unclassified_retained')
                decisions.append(trace)
                if not known:
                    unclassified.append(trace.copy())
        return {'included': included, 'excluded': excluded, 'unclassified': unclassified,
                'includedDecisions': decisions, 'policyIdentity': self.policyIdentity}


def load_policy(manifest_path, expected_manifest_sha256=None):
    """Read and validate an entire source-bound partition before applying scope.

    expected_manifest_sha256 pins exact UTF-8 file bytes. The independently
    recorded manifestContentSha256 pins canonical content excluding itself.
    Callers still own the trust of the provided scope-review manifest.
    """
    raw = Path(manifest_path).read_bytes()
    file_sha = hashlib.sha256(raw).hexdigest()
    if expected_manifest_sha256 is not None and file_sha != _digest(expected_manifest_sha256, 'expected_manifest_sha256'):
        raise CompetitionScopeError('scope_manifest_file_sha256_mismatch')
    try:
        manifest = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    except (UnicodeError, json.JSONDecodeError, ValueError) as error:
        if isinstance(error, CompetitionScopeError):
            raise
        raise CompetitionScopeError('scope_manifest_not_strict_utf8_json') from error
    if not isinstance(manifest, dict) or manifest.get('schema') != SCHEMA:
        raise CompetitionScopeError('unsupported_scope_manifest_schema')
    content_sha = _digest(manifest.get('manifestContentSha256'), 'manifestContentSha256')
    if hashlib.sha256(_json_bytes({key: value for key, value in manifest.items() if key != 'manifestContentSha256'})).hexdigest() != content_sha:
        raise CompetitionScopeError('scope_manifest_content_sha256_mismatch')
    if manifest.get('unknownLibraryBoundaries') not in (None, []):
        raise CompetitionScopeError('scope_manifest_library_boundaries_unresolved')
    source = manifest.get('source')
    if not isinstance(source, dict):
        raise CompetitionScopeError('scope_manifest_source_identity_missing')
    source_sha = _digest(source.get('sourceSha256'), 'source.sourceSha256')
    page_count = _number(source.get('pageCount'), 'source.pageCount')
    if page_count > 100000:
        raise CompetitionScopeError('scope_manifest_page_count_unreasonable')
    page_numbering = manifest.get('pageNumbering', {})
    if not isinstance(page_numbering, dict) or page_numbering.get('basis') != 'original_pdf_physical_page' or type(page_numbering.get('indexBase')) is not int or page_numbering.get('indexBase') != 1 or page_numbering.get('rangeEndsInclusive') is not True:
        raise CompetitionScopeError('scope_manifest_physical_page_numbering_required')
    collections = []
    for key in ('includedPageSpans', 'metadataSpans', 'excludedSpans'):
        values = manifest.get(key)
        if not isinstance(values, list) or not values:
            raise CompetitionScopeError('scope_manifest_partition_missing', details={'field': key})
        spans = tuple(_span(item, page_count) for item in values)
        count = sum(end - start + 1 for start, end in spans)
        if count != len(_pages(spans)):
            raise CompetitionScopeError('scope_manifest_page_span_overlap', details={'field': key})
        collections.append(spans)
    included, metadata, excluded = collections
    partition = [_pages(spans) for spans in collections]
    if any(partition[a] & partition[b] for a, b in ((0, 1), (0, 2), (1, 2))):
        raise CompetitionScopeError('scope_manifest_page_span_overlap')
    if set.union(*partition) != set(range(1, page_count + 1)):
        raise CompetitionScopeError('scope_manifest_page_partition_gap')
    libraries = manifest.get('includedLibraries')
    if libraries is not None:
        if not isinstance(libraries, list) or not libraries:
            raise CompetitionScopeError('scope_manifest_included_library_identity_missing')
        library_spans = tuple(_span(item, page_count) for item in libraries)
        if _pages(library_spans) != partition[0] or sum(end - start + 1 for start, end in library_spans) != len(partition[0]):
            raise CompetitionScopeError('scope_manifest_library_span_mismatch')
    declared_pages = manifest.get('includedPhysicalPages')
    if declared_pages is not None and (not isinstance(declared_pages, list) or
            any(isinstance(page, bool) or not isinstance(page, int) for page in declared_pages) or
            len(declared_pages) != len(partition[0]) or set(declared_pages) != partition[0]):
        raise CompetitionScopeError('scope_manifest_explicit_pages_mismatch')
    version = manifest.get('scopeVersion')
    if not isinstance(version, str) or not version:
        raise CompetitionScopeError('scope_manifest_version_missing')
    return CompetitionScopePolicy(file_sha, content_sha, source_sha, page_count, version, included, metadata, excluded)
