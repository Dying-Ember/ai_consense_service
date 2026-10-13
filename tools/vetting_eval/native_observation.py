"""Capture exact native OOXML observations before source-reviewed projection.

The package, every ZIP part and adapter source are actual bytes in CAS. Native
positions are checked against the original XML tree; this is structural and
integrity validation, never a claim of correct displayed text or legal meaning.
The scanned dual-parser contract is deliberately not part of this module.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET
import zipfile

from artifact_store import ArtifactStore, ArtifactStoreError

EXPORT_SCHEMA = 'consense.native-observation.v1'
BUNDLE_SCHEMA = 'consense.native-observation-bundle.v1'
DOCX_MEDIA = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
WORD_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
STRICT_WORD_NS = 'http://purl.oclc.org/ooxml/wordprocessingml/main'
WORD_NAMESPACES = {WORD_NS, STRICT_WORD_NS}
XMLNS = 'http://www.w3.org/2000/xmlns/'
NATIVE_TAGS = {'paragraph': 'p', 'run': 'r', 'table': 'tbl', 'row': 'tr', 'cell': 'tc'}
TEXT_CHANNELS = ('observedRaw', 'deleted', 'directStrike', 'nonDeletedNonDirectStrike')
TOKEN_NAMES = {'t', 'delText', 'tab', 'ptab', 'br', 'cr', 'noBreakHyphen', 'softHyphen',
               'sym', 'instrText', 'delInstrText', 'fldChar', 'drawing', 'object', 'pict',
               'footnoteReference', 'endnoteReference', 'commentReference', 'annotationRef',
               'separator', 'continuationSeparator', 'lastRenderedPageBreak'}


class NativeObservationError(ValueError):
    """An unsupported shape, invented position, incomplete capture or stale byte identity."""


def _require(condition, message):
    if not condition:
        raise NativeObservationError(message)


def _bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _identity(value, field):
    _require(isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value),
             field + ' must be lowercase SHA-256')
    return value


def _strict_json(raw):
    def pairs(values):
        result = {}
        for key, value in values:
            _require(key not in result, 'Duplicate JSON key: ' + key)
            result[key] = value
        return result
    try:
        result = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=pairs,
                            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
        _bytes(result)  # Reject nonfinite floats produced by numeric overflow.
    except (ValueError, UnicodeError, TypeError) as error:
        raise NativeObservationError('Native observation must be strict UTF-8 JSON') from error
    _require(isinstance(result, dict), 'Native observation must be an object')
    return result


def _captured(row, name):
    _require(isinstance(row, dict), name + ' requires actual captured bytes')
    try:
        raw = base64.b64decode(row['bytesBase64'], validate=True)
    except (KeyError, ValueError, TypeError) as error:
        raise NativeObservationError(name + ' has invalid or unavailable bytes') from error
    _require(type(row.get('byteCount')) is int and row['byteCount'] == len(raw)
             and row.get('sha256') == _sha(raw), name + ' byteCount/SHA does not match actual bytes')
    return raw


class _NoDtd(ET.TreeBuilder):
    def __init__(self):
        super().__init__()
        self.attributes, self.pending_namespaces = {}, {}

    def start_ns(self, prefix, uri):
        self.pending_namespaces['{' + XMLNS + '}' + (prefix or 'xmlns')] = uri

    def end_ns(self, prefix):
        pass

    def start(self, tag, attrs):
        node = super().start(tag, attrs)
        self.attributes[node] = {**attrs, **self.pending_namespaces}
        self.pending_namespaces = {}
        return node

    def doctype(self, name, pubid, system):
        raise NativeObservationError('OOXML DTD/entity declarations are not supported')


def _xml_context(raw):
    try:
        builder = _NoDtd()
        root = ET.fromstring(raw, parser=ET.XMLParser(target=builder))
    except ET.ParseError as error:
        raise NativeObservationError('Native story XML is malformed') from error
    positions, parents, paths = {}, {}, {}
    def walk(node, path, parent):
        positions[path] = node
        parents[path] = parent
        paths[node] = path
        counts = {}
        for child in node:
            counts[child.tag] = counts.get(child.tag, 0) + 1
            walk(child, path + '/' + child.tag + '[' + str(counts[child.tag]) + ']', path)
    walk(root, '/' + root.tag + '[1]', None)
    return {'positions': positions, 'parents': parents, 'paths': paths,
            'attributes': builder.attributes, 'root': root}


def _tree(raw):
    context = _xml_context(raw)
    return context['positions'], context['parents']


def _local(node):
    if not isinstance(node.tag, str) or not node.tag.startswith('{'):
        return None
    namespace, name = node.tag[1:].split('}', 1)
    return name if namespace in WORD_NAMESPACES else None


def _direct(node, local):
    return next((child for child in node if _local(child) == local), None)


def _ancestor(context, path, local=None):
    cursor = context['parents'][path]
    while cursor is not None:
        node = context['positions'][cursor]
        if local is None or _local(node) == local:
            return cursor
        cursor = context['parents'][cursor]
    return None


def _nearest_native(context, path):
    cursor = context['parents'][path]
    while cursor is not None:
        if _local(context['positions'][cursor]) in NATIVE_TAGS.values():
            return cursor
        cursor = context['parents'][cursor]
    return None


def native_node_id(kind, source_sha, part_sha, path, *, part_name):
    return 'native-' + _sha(('consense.native-node.v1\n' + kind + '\n' + source_sha
                            + '\n' + part_name + '\n' + part_sha + '\n' + path).encode('utf-8'))


def _position(source_sha, part, part_sha, path):
    return {'kind': 'docx_native', 'sourceSha256': source_sha, 'partName': part,
            'partSha256': part_sha, 'nodePath': path}


def _utf16(text):
    return len(text.encode('utf-16-le')) // 2


def _property(context, owner, props, name, declared, label):
    properties = _direct(owner, props)
    element = _direct(properties, name) if properties is not None else None
    val = None if element is None else next((element.attrib['{' + ns + '}val']
               for ns in (WORD_NS, STRICT_WORD_NS) if '{' + ns + '}val' in element.attrib), None)
    _require(isinstance(declared, dict) and declared.get('present') is (element is not None)
             and 'val' in declared and declared['val'] == val, label + ' property presence/value differs from original')
    if element is not None:
        _raw_node(context, element, declared.get('rawNode'), label + '.rawNode')
    else:
        _require('rawNode' not in declared, label + ' invents an absent raw property')
    return element, val


def _raw_node(context, element, declared, label):
    # Raw XML remains in CAS. This verifies exact element location/attributes and
    # child element structure; DOM CDATA/text node segmentation is not redefined.
    _require(isinstance(declared, dict) and declared.get('expandedName') == element.tag
             and declared.get('nodePath') == context['paths'][element]
             and declared.get('attributes') == _attributes(context, element),
             label + ' raw XML property identity/attributes differ from original')
    children = declared.get('children')
    _require(isinstance(children, list), label + ' raw XML children required')
    child_elements = [child for child in children if isinstance(child, dict) and 'expandedName' in child]
    _require(len(child_elements) == len(element), label + ' raw XML child element inventory differs from original')
    for actual, value in zip(element, child_elements):
        _raw_node(context, actual, value, label)
    # Verify character data while allowing DOM to split it into text/CDATA nodes.
    text = ''.join(str(child.get('value', '')) for child in children
                   if isinstance(child, dict) and child.get('domNodeType') in (3, 4))
    expected = (element.text or '') + ''.join(child.tail or '' for child in element)
    _require(text == expected, label + ' raw XML character data differs from original')


def _attributes(context, element):
    return {(key if key.startswith('{') else '{}' + key): value
            for key, value in context['attributes'][element].items()}


def _tokens(context, owner):
    found = []
    def collect(cursor):
        if cursor is not owner:
            local = _local(cursor)
            if local == 'p' or (_local(owner) == 'r' and local == 'r'):
                return
            if local in TOKEN_NAMES:
                found.append(cursor)
                return
        for child in cursor:
            collect(child)
    collect(owner)
    return found


def _validate_text(context, actual, node, source_sha, part, part_sha):
    channels = node.get('textChannels')
    tokens = node.get('tokens')
    _require(isinstance(channels, dict) and set(channels) == set(TEXT_CHANNELS)
             and all(isinstance(value, str) for value in channels.values()) and isinstance(tokens, list),
             'Native channels/tokens must be explicit exact strings/lists')
    expected_elements = _tokens(context, actual) if node['kind'] in ('paragraph', 'run') else []
    _require(len(tokens) == len(expected_elements), 'Native token inventory is missing or invented')
    output, lengths = {name: [] for name in TEXT_CHANNELS}, {name: 0 for name in TEXT_CHANNELS}
    for element, token in zip(expected_elements, tokens):
        path, local = context['paths'][element], _local(element)
        literal = ''.join(element.itertext())
        field = local in ('instrText', 'delInstrText')
        encoded = '' if field else (literal if local in ('t', 'delText') else
                 {'tab': '\t', 'ptab': '\t', 'br': '\n', 'cr': '\r', 'noBreakHyphen': '\u2011', 'softHyphen': '\u00ad'}.get(local, ''))
        removed = local in ('delText', 'delInstrText') or any(
            _ancestor(context, path, name) is not None for name in ('del', 'moveFrom'))
        run_path = _ancestor(context, path, 'r')
        run = context['positions'][run_path] if run_path is not None else ET.Element('absent')
        _require(isinstance(token, dict) and token.get('kind') == local and token.get('rawDomText') == literal
                 and token.get('encodedText') == encoded and token.get('sourcePosition') == _position(source_sha, part, part_sha, path)
                 and token.get('rawAttributes') == _attributes(context, element), 'Native token literal/control/position differs from original')
        struck = False
        for property_name, field_name in (('strike', 'directStrikeProperty'), ('dstrike', 'directDoubleStrikeProperty')):
            prop, val = _property(context, run, 'rPr', property_name, token.get(field_name), field_name)
            interpretation = 'absent_inheritance_unknown' if prop is None else ('true' if val is None or val in ('true', '1', 'on')
                             else 'false' if val in ('false', '0', 'off') else 'unknown_invalid_value')
            _require(token[field_name].get('interpretation') == interpretation, 'Native direct strike interpretation differs from actual property')
            struck = struck or interpretation == 'true'
        _require(token.get('deletedOrMovedFrom') is removed and token.get('directStruck') is struck
                 and token.get('effectiveRenderingStatus') == 'unknown', 'Native token edit/effective-state claim differs from original')
        _require(not field or token.get('fieldInstruction') == literal, 'Native field instruction is missing or normalized')
        value_origin = 'literal_dom_character_data' if local in ('t', 'delText', 'instrText', 'delInstrText') else 'explicit_ooxml_control_encoding'
        _require(token.get('valueOrigin') == value_origin, 'Native token origin differs from actual OOXML encoding')
        offsets = token.get('channelOffsets')
        _require(isinstance(offsets, dict) and set(offsets) == set(TEXT_CHANNELS), 'All native UTF16 channel offsets required')
        includes = (True, removed, struck, not removed and not struck)
        for name, included in zip(TEXT_CHANNELS, includes):
            size = _utf16(encoded) if included else 0
            _require(isinstance(offsets[name], dict) and type(offsets[name].get('start')) is int
                     and type(offsets[name].get('end')) is int and type(offsets[name].get('included')) is bool
                     and offsets[name] == {'start': lengths[name], 'end': lengths[name] + size, 'included': included},
                     'Native UTF16 offset/inclusion differs from actual token sequence')
            if included:
                output[name].append(encoded)
            lengths[name] += size
    _require(channels == {name: ''.join(output[name]) for name in TEXT_CHANNELS},
             'Native text channel differs from original literal/control/edit sequence')


def _validate_inventories(export, parts, source, nodes, contexts):
    expected_order = []
    story_names = []
    for row in export['rawParts']:
        media = row['mediaType']
        if 'wordprocessingml' in media and any(suffix in media for suffix in
                ('.document.main+xml', '.header+xml', '.footer+xml', '.footnotes+xml', '.endnotes+xml', '.comments+xml', '.document.glossary+xml')):
            story_names.append(row['name'])
    story_names.sort(key=lambda name: (name != 'word/document.xml', name))
    stories, unsupported = export.get('storyInventory'), export.get('unsupportedStories')
    _require(isinstance(stories, list) and isinstance(unsupported, list) and len(stories) == len(story_names),
             'Native story inventory is missing or invented')
    expected_unsupported = []
    kind_by_tag = {tag: kind for kind, tag in NATIVE_TAGS.items()}
    position_nodes = {(node['sourcePosition']['partName'], node['sourcePosition']['nodePath']): node for node in nodes.values()}
    media_by_name = {row['name']: row['mediaType'] for row in export['rawParts']}
    for name, story in zip(story_names, stories):
        if name not in contexts:
            contexts[name] = _xml_context(parts[name])
        context = contexts[name]
        root = context['root']
        local = _local(root) or 'unsupported_namespace_or_root'
        supported = local in ('document', 'hdr', 'ftr', 'footnotes', 'endnotes')
        actual_nodes = [(path, element) for path, element in context['positions'].items()
                        if _local(element) in kind_by_tag] if supported else []
        _require(isinstance(story, dict) and story.get('partName') == name and story.get('partSha256') == _sha(parts[name])
                 and story.get('mediaType') == media_by_name[name]
                 and story.get('rootExpandedName') == root.tag and story.get('storyKind') == local
                 and story.get('semanticQuality') == 'unknown' and story.get('observationStatus') ==
                 ('observed_native_structure_unreviewed' if supported else 'unsupported_story_raw_preserved'),
                 'Native story identity/status differs from original XML')
        if not supported:
            expected_unsupported.append(story)
            continue
        _require(story.get('nativeNodeCount') == len(actual_nodes), 'Native story node count differs from original inventory')
        for path, element in actual_nodes:
            node = position_nodes.get((name, path))
            _require(node is not None, 'Native inventory omits an actual XML node, including blank content')
            expected_order.append(node['nodeId'])
            for relation, parent_path in (
                    ('parentNativeNodeId', _nearest_native(context, path)), ('parentCellId', _ancestor(context, path, 'tc')),
                    ('parentTableId', _ancestor(context, path, 'tbl')), ('parentRowId', _ancestor(context, path, 'tr'))):
                parent = None if parent_path is None else position_nodes.get((name, parent_path))
                expected = None if parent is None else parent['nodeId']
                _require(node.get(relation) == expected, 'Native nearest-ancestor relation differs from original XML: ' + relation)
    _require(unsupported == expected_unsupported and expected_order == list(nodes),
             'Native story/node inventory is incomplete, invented or out of original DOM order')
    _require([node['sourceOrder'] for node in nodes.values()] == list(range(1, len(nodes) + 1)),
             'Native sourceOrder must be exact DOM emission order')
    tables = export.get('tableInventory')
    _require(isinstance(tables, list), 'Explicit native table inventory required')
    table_nodes = [node for node in nodes.values() if node['kind'] == 'table']
    _require(len(tables) == len(table_nodes), 'Native table inventory omits or invents a table')
    by_owner = {}
    for node in nodes.values():
        for relation in ('parentTableId', 'parentRowId', 'parentCellId'):
            by_owner.setdefault((relation, node.get(relation), node['kind']), []).append(node)
    for table, table_node in zip(tables, table_nodes):
        _require(isinstance(table, dict) and table.get('tableId') == table_node['nodeId']
                 and table.get('sourcePosition') == table_node['sourcePosition'] and table.get('inventoryComplete') is True
                 and table.get('semanticQuality') == 'unknown'
                 and table.get('topologyStatus') == 'all_native_rows_and_cells_observed_no_rendered_grid_claim'
                 and table.get('rowSpanStatus') == 'not_inferred', 'Native table identity/topology status differs from original inventory')
        part, path = table_node['sourcePosition']['partName'], table_node['sourcePosition']['nodePath']
        context, table_id = contexts[part], table_node['nodeId']
        for relation in ('parentCellId', 'parentTableId'):
            _require(table.get(relation) == table_node.get(relation), 'Native table parent relation differs from original')
        actual_table = context['positions'][path]
        grid = _direct(actual_table, 'tblGrid')
        grid_elements = [] if grid is None else [child for child in grid if _local(child) == 'gridCol']
        _require(isinstance(table.get('gridColumns'), list) and len(table['gridColumns']) == len(grid_elements),
                 'Native grid column inventory differs from original')
        for element, declared in zip(grid_elements, table['gridColumns']):
            _raw_node(context, element, declared, 'gridColumn')
        row_nodes = by_owner.get(('parentTableId', table_id, 'row'), [])
        _require(isinstance(table.get('rows'), list) and len(table['rows']) == len(row_nodes),
                 'Native table rows are missing or invented')
        nested = []
        for ri, (row, row_node) in enumerate(zip(table['rows'], row_nodes)):
            _require(isinstance(row, dict) and row.get('rowId') == row_node['nodeId'] and row.get('rowIndex') == ri
                     and row_node.get('rowIndex') == ri and row.get('sourcePosition') == row_node['sourcePosition'],
                     'Native row membership/order/position differs from original')
            actual_row = context['positions'][row_node['sourcePosition']['nodePath']]
            for prop in ('gridBefore', 'gridAfter'):
                _property(context, actual_row, 'trPr', prop, row.get(prop + 'Raw'), prop)
            cell_nodes = by_owner.get(('parentRowId', row_node['nodeId'], 'cell'), [])
            _require(isinstance(row.get('cells'), list) and len(row['cells']) == len(cell_nodes),
                     'Native cells, including blank cells, are missing or invented')
            for ci, (cell, cell_node) in enumerate(zip(row['cells'], cell_nodes)):
                _require(isinstance(cell, dict) and cell.get('cellId') == cell_node['nodeId']
                         and cell_node.get('cellId') == cell_node['nodeId'] and cell.get('rowIndex') == cell_node.get('rowIndex') == ri
                         and cell.get('cellIndex') == cell_node.get('cellIndex') == ci
                         and cell.get('sourcePosition') == cell_node['sourcePosition'], 'Native cell membership/order/position differs from original')
                actual_cell = context['positions'][cell_node['sourcePosition']['nodePath']]
                for prop in ('gridSpan', 'vMerge', 'hMerge'):
                    _property(context, actual_cell, 'tcPr', prop, cell.get(prop + 'Raw'), prop)
                for name, kind in (('childParagraphIds', 'paragraph'), ('nestedTableIds', 'table')):
                    expected = [node['nodeId'] for node in by_owner.get(('parentCellId', cell_node['nodeId'], kind), [])]
                    _require(cell.get(name) == expected, 'Native cell child inventory differs from original: ' + name)
                nested.extend(cell['nestedTableIds'])
        _require(table.get('nestedTableIds') == nested, 'Native nested table membership differs from original XML')


def validate_native_export(export, source_bytes, adapter_source_bytes):
    """Verify all package bytes and actual OOXML positions; do not normalize text."""
    try:
        return _validate_native_export(export, source_bytes, adapter_source_bytes)
    except NativeObservationError:
        raise
    except (KeyError, TypeError, AttributeError, IndexError, UnicodeError, RecursionError) as error:
        raise NativeObservationError('Malformed or unsupported native observation structure') from error


def _validate_native_export(export, source_bytes, adapter_source_bytes):
    _require(isinstance(export, dict) and export.get('schema') == EXPORT_SCHEMA
             and export.get('producerKind') == 'native_docx', 'Unsupported native observation producer')
    _require(isinstance(source_bytes, bytes) and isinstance(adapter_source_bytes, bytes),
             'Source and adapter must be actual bytes')
    source, observer = export.get('source'), export.get('observerIdentity')
    _require(isinstance(source, dict) and source.get('sourceSha256') == _sha(source_bytes)
             and source.get('mediaType') == DOCX_MEDIA and isinstance(source.get('sourceFilename'), str)
             and source['sourceFilename'].strip(), 'Original DOCX source identity is missing or stale')
    _identity(source.get('sourceRevisionSha256'), 'sourceRevisionSha256')
    _require(_captured(export.get('sourcePackage'), 'sourcePackage') == source_bytes,
             'Observed source package differs from original supplied bytes')
    _require(isinstance(observer, dict) and observer.get('adapterCodeSha256') == _sha(adapter_source_bytes)
             and observer.get('version') == 'ooxml-observation-2'
             and isinstance(observer.get('dependencyVersions'), dict) and observer['dependencyVersions']
             and isinstance(observer.get('config'), dict), 'Resolved observer/source/dependency identity required')
    _require(observer.get('configSha256') == _sha(_bytes(observer['config'])),
             'Observer configuration SHA does not match canonical resolved configuration')
    _require(source['sourceRevisionSha256'] == _sha(_bytes({'sourceSha256': source['sourceSha256'], 'observerIdentity': observer})),
             'Native source revision differs from actual source/observer identity')
    quality = export.get('quality')
    _require(isinstance(quality, dict) and quality.get('semanticQuality') == 'unknown'
             and quality.get('humanConfirmed') is False and quality.get('canonicalAdoption') == 'not_performed'
             and quality.get('originalGeometryStatus') == 'not_defined_for_native_docx'
             and quality.get('effectiveRenderingStatus') == 'unknown'
             and isinstance(quality.get('limitations'), list) and quality['limitations'],
             'Native observation cannot promote unknowns to semantic/human/visual acceptance')
    rows = export.get('rawParts')
    _require(isinstance(rows, list) and rows, 'Complete original package part capture required')
    parts = {}
    for row in rows:
        _require(isinstance(row, dict) and isinstance(row.get('name'), str)
                 and row['name'] not in parts, 'Package part names must be explicit and unique')
        name = row['name']
        _require(not name.startswith('/') and '\\' not in name
                 and all(segment not in ('', '.', '..') for segment in name.split('/')),
                 'Invalid original ZIP part name')
        _require(isinstance(row.get('mediaType'), str) and row['mediaType'], 'Part mediaType required')
        parts[name] = _captured(row, 'rawParts.' + name)
    try:
        with zipfile.ZipFile(io.BytesIO(source_bytes)) as package:
            entries = [item for item in package.infolist() if not item.is_dir()]
            _require(len(entries) == len({item.filename for item in entries}), 'Duplicate original ZIP entries')
            _require(set(parts) == {item.filename for item in entries}, 'Original package parts are missing or invented')
            for item in entries:
                _require(parts[item.filename] == package.read(item), 'Raw part does not match original ZIP entry: ' + item.filename)
    except (zipfile.BadZipFile, RuntimeError, OSError) as error:
        raise NativeObservationError('Original DOCX package is unreadable or unsupported') from error
    _require('word/document.xml' in parts and '[Content_Types].xml' in parts,
             'Original DOCX main part and content types required')
    # One immutable digest per actual captured part. Large stories can contain
    # tens of thousands of nodes; hashing the same 12 MB XML per node changes no
    # check and would multiply input work by the full native inventory size.
    part_digests = {name: _sha(raw) for name, raw in parts.items()}
    content_types = _xml_context(parts['[Content_Types].xml'])['root']
    ct_namespace = '{http://schemas.openxmlformats.org/package/2006/content-types}'
    overrides = {element.attrib['PartName'].lstrip('/'): element.attrib['ContentType']
                 for element in content_types if element.tag == ct_namespace + 'Override'}
    defaults = {element.attrib['Extension']: element.attrib['ContentType']
                for element in content_types if element.tag == ct_namespace + 'Default'}
    for row in rows:
        expected_type = overrides.get(row['name'], defaults.get(row['name'].rsplit('.', 1)[-1]))
        if row['name'] == '[Content_Types].xml':
            expected_type = 'application/xml'
        if expected_type is None:
            expected_type = 'application/vnd.openxmlformats-package.relationships+xml' if row['name'].endswith('.rels') else 'application/octet-stream'
        _require(row['mediaType'] == expected_type, 'Native part content type differs from actual package: ' + row['name'])
    nodes, orders, typed_positions, trees = {}, set(), set(), {}
    observations = export.get('nativeNodes')
    _require(isinstance(observations, list) and observations, 'Native node inventory required')
    for node in observations:
        _require(isinstance(node, dict) and isinstance(node.get('nodeId'), str) and node['nodeId']
                 and node['nodeId'] not in nodes and node.get('kind') in NATIVE_TAGS,
                 'Native nodes require unique IDs and an explicit supported kind')
        order = node.get('sourceOrder')
        _require(type(order) is int and order >= 0 and order not in orders, 'Native source order must be unique and nonnegative')
        orders.add(order)
        position = node.get('sourcePosition')
        _require(isinstance(position, dict) and position.get('kind') == 'docx_native'
                 and position.get('sourceSha256') == source['sourceSha256'], 'Native position requires original DOCX identity')
        _require(not any(key in node or key in position for key in ('pageNo', 'physicalPageNo', 'bbox')),
                 'Native DOCX cannot acquire original physical pages or boxes')
        name, path = position.get('partName'), position.get('nodePath')
        _require(name in parts and position.get('partSha256') == part_digests[name]
                 and isinstance(path, str) and path, 'Native package part/path identity is missing or stale')
        if name not in trees:
            trees[name] = _xml_context(parts[name])
        _require((name, path) not in typed_positions, 'Duplicate native position')
        typed_positions.add((name, path))
        context = trees[name]
        actual = context['positions'].get(path)
        _require(actual is not None and _local(actual) == NATIVE_TAGS[node['kind']],
                 'Native node path does not identify the declared actual OOXML kind')
        _require(node['nodeId'] == native_node_id(node['kind'], source['sourceSha256'], position['partSha256'], path, part_name=name),
                 'Native node ID differs from actual source/part/kind/path identity')
        _require(node.get('rawAttributes') == _attributes(context, actual)
                 and node.get('xmlLocalName') == _local(actual) and node.get('xmlNamespaceUri') == actual.tag[1:].split('}', 1)[0]
                 and node.get('semanticQuality') == 'unknown' and node.get('originalGeometryStatus') == 'not_defined_for_native_docx',
                 'Native node metadata/attributes or quality claim differs from original')
        properties = node.get('rawProperties')
        actual_properties = {child.tag: child for child in actual if _local(child) in ('pPr', 'rPr', 'tcPr', 'trPr', 'tblPr', 'tblGrid')}
        _require(isinstance(properties, dict) and set(properties) == set(actual_properties), 'Native direct property inventory differs from original')
        for property_tag, element in actual_properties.items():
            _raw_node(context, element, properties[property_tag], 'nativeNode.rawProperties')
        _validate_text(context, actual, node, source['sourceSha256'], name, position['partSha256'])
        nodes[node['nodeId']] = node
    _require([node['sourceOrder'] for node in observations] == sorted(orders), 'Native observations must retain source order')
    _validate_inventories(export, parts, source, nodes, trees)
    return {'schema': 'consense.native-observation-validation.v1', 'sourceSha256': source['sourceSha256'],
            'partCount': len(parts), 'nodeCount': len(nodes), 'tableCount': len(export['tableInventory']),
            'originalBytesVerified': True, 'nativePositionsVerified': True,
            'nativeTokenChannelsVerified': True, 'nativeInventoryMembershipVerified': True,
            'observerRevisionVerified': True,
            'displayedTextVerified': False, 'tableTopologySemanticsVerified': False,
            'semanticQuality': 'unknown', 'humanConfirmed': False}


def capture_native_observation(export_bytes, source_bytes, adapter_source_bytes, *, artifact_store):
    """Retain all actual bytes in CAS and seal the declared observation bundle."""
    _require(isinstance(artifact_store, ArtifactStore), 'Capture requires an actual ArtifactStore')
    export = _strict_json(export_bytes)
    validation = validate_native_export(export, source_bytes, adapter_source_bytes)
    raw_parts = []
    for row in export['rawParts']:
        raw_parts.append({key: copy.deepcopy(value) for key, value in row.items() if key != 'bytesBase64'})
        raw_parts[-1]['artifactDescriptor'] = artifact_store.put_bytes(_captured(row, row['name']))
    bundle = {'schema': BUNDLE_SCHEMA, 'producerKind': 'native_docx',
              'source': copy.deepcopy(export['source']), 'observerIdentity': copy.deepcopy(export['observerIdentity']),
              'sourcePackage': artifact_store.put_bytes(source_bytes),
              'adapterSource': artifact_store.put_bytes(adapter_source_bytes),
              'rawObservation': artifact_store.put_bytes(export_bytes), 'rawParts': raw_parts,
              'nativeNodes': copy.deepcopy(export['nativeNodes']),
              'tableInventory': copy.deepcopy(export['tableInventory']),
              'quality': copy.deepcopy(export['quality']),
              'storyInventory': copy.deepcopy(export['storyInventory']),
              'unsupportedStories': copy.deepcopy(export['unsupportedStories']),
              'relationshipInventory': copy.deepcopy(export['relationshipInventory']),
              'limitations': copy.deepcopy(export['quality']['limitations']), 'validation': validation,
              'semanticScopeVerified': False, 'humanConfirmed': False}
    bundle['bundleSha256'] = _sha(_bytes(bundle))
    validate_native_bundle(bundle, artifact_store=artifact_store)
    return bundle


def validate_native_bundle(bundle, *, artifact_store):
    try:
        return _validate_native_bundle(bundle, artifact_store=artifact_store)
    except NativeObservationError:
        raise
    except (KeyError, TypeError, AttributeError, IndexError, ArtifactStoreError) as error:
        raise NativeObservationError('Malformed native bundle or missing actual CAS descriptor') from error


def _validate_native_bundle(bundle, *, artifact_store):
    _require(isinstance(bundle, dict) and bundle.get('schema') == BUNDLE_SCHEMA
             and isinstance(artifact_store, ArtifactStore), 'Native bundle and actual ArtifactStore required')
    _require(bundle.get('bundleSha256') == _sha(_bytes({key: value for key, value in bundle.items() if key != 'bundleSha256'})),
             'Native observation bundle seal mismatch')
    source_bytes = artifact_store.read_asset(bundle['sourcePackage'])
    adapter_bytes = artifact_store.read_asset(bundle['adapterSource'])
    export = _strict_json(artifact_store.read_asset(bundle['rawObservation']))
    validation = validate_native_export(export, source_bytes, adapter_bytes)
    for key in ('source', 'observerIdentity', 'nativeNodes', 'tableInventory', 'quality', 'storyInventory', 'unsupportedStories', 'relationshipInventory'):
        _require(bundle.get(key) == export.get(key), 'Native bundle differs from retained observation: ' + key)
    _require(bundle.get('limitations') == export['quality']['limitations'], 'Native limitations differ from retained raw quality metadata')
    _require(bundle.get('validation') == validation and bundle.get('semanticScopeVerified') is False
             and bundle.get('humanConfirmed') is False, 'Native integrity validation cannot certify semantic/human acceptance')
    by_name = {row['name']: row for row in bundle['rawParts']}
    _require(len(by_name) == len(bundle['rawParts']) == len(export['rawParts']), 'Captured package parts are incomplete')
    for row in export['rawParts']:
        actual = by_name.get(row['name'])
        expected = {key: value for key, value in row.items() if key != 'bytesBase64'}
        _require(actual is not None and {key: value for key, value in actual.items() if key != 'artifactDescriptor'} == expected,
                 'Captured part descriptor metadata differs from retained raw observation')
        _require(artifact_store.read_asset(actual['artifactDescriptor']) == _captured(row, row['name']),
                 'Captured native part bytes differ from original observation')
    return validation
