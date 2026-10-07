"""HTTP integration check using original allowlisted files, never evaluation answers.

The evaluation matrix is evidence matching plus a manual semantic review queue;
absence of a finding is never counted as proof that a negative case passed.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
from urllib.parse import quote as urlquote

import requests

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def norm(value):
    # Same whitespace-only normalization as the Java source-verification path.
    return re.sub(r'\s+', ' ', value or '').strip()


def words(value):
    return re.findall(r'[a-z0-9]+', norm(value).casefold())


def api(session, method, url, **kwargs):
    response = session.request(method, url, timeout=(10, 1200), **kwargs)
    response.raise_for_status()
    value = response.json()
    if isinstance(value, dict) and 'code' in value:
        if value['code'] != 0:
            raise RuntimeError(f'{method} {url}: {value}')
        return value.get('data')
    return value


def word_text(node, deleted=False, struck=False):
    deleted = deleted or node.tag in (W+'del', W+'moveFrom', W+'delText')
    if node.tag == W+'r':
        props = node.find(W+'rPr')
        if props is not None:
            struck = struck or any(p.tag in (W+'strike', W+'dstrike') and
                p.get(W+'val', 'true').lower() not in ('0', 'false', 'off') for p in props)
    if node.tag in (W+'t', W+'delText'):
        return '' if deleted or struck else (node.text or '')
    if node.tag in (W+'tab', W+'br', W+'cr'):
        return '' if deleted or struck else (' ' if node.tag == W+'tab' else '\n')
    value = ''.join(word_text(child, deleted, struck) for child in node)
    return value + ('\n' if node.tag == W+'p' and not deleted and not struck else '')


def effective_docx(path):
    with zipfile.ZipFile(path) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
        blocks = []
        for child in root.find(W+'body'):
            if child.tag == W+'tbl':
                for row in child.findall(W+'tr'):
                    blocks.append(' | '.join(norm(word_text(cell)) for cell in row.findall(W+'tc')))
            elif child.tag == W+'p':
                blocks.append(word_text(child))
        for name in archive.namelist():
            if re.fullmatch(r'word/(?:header|footer)\d+\.xml', name):
                blocks.append(word_text(ET.fromstring(archive.read(name))))
        for kind in ('footnote', 'endnote'):
            name = f'word/{kind}s.xml'
            if name in archive.namelist():
                root = ET.fromstring(archive.read(name))
                for note in root.findall(W+kind):
                    if int(note.get(W+'id', '0')) > 0:
                        blocks.append(word_text(note))
    return norm('\n\n'.join(blocks))


def load_documents(config, manifest):
    value = json.loads(manifest.read_text(encoding='utf-8')) if manifest else config['documents']
    documents = value.get('documents') if isinstance(value, dict) else value
    if not isinstance(documents, list) or not documents:
        raise ValueError('The source manifest must contain a nonempty documents list')
    seen = set()
    for document in documents:
        if not isinstance(document, dict) or not all(document.get(key) for key in ('key', 'role', 'path')):
            raise ValueError('Each source document needs key, role and path')
        if document['role'] not in ('tender', 'standard', 'project_fact', 'package_manifest'):
            raise ValueError(f"Unsupported source role: {document['role']}")
        path = Path(document['path'])
        if path.is_absolute() or any(part.startswith(('2d_', '2e_')) for part in path.parts):
            raise ValueError(f'Evaluation references cannot be uploaded as source: {path}')
        if path.suffix.lower() not in ('.docx', '.pdf'):
            raise ValueError(f'Only DOCX and PDF are supported in this integration manifest: {path}')
        if path.name in seen:
            raise ValueError(f'Source file names must be unique for independent quotation verification: {path.name}')
        seen.add(path.name)
    return documents


def audit_sources(documents, source_root, files):
    """Describe all source failures before the caller decides whether to review."""
    from pypdf import PdfReader
    rows, errors, known_names = [], [], {Path(d['path']).name for d in documents}
    for doc in documents:
        name = Path(doc['path']).name
        found = [file for file in files if file.get('fileName') == name]
        row = {'path': doc['path'], 'fileName': name, 'expectedRole': doc['role'],
               'scanType': doc.get('scanType'), 'expectedPhysicalPages': None,
               'errors': []}
        is_pdf = Path(doc['path']).suffix.lower() == '.pdf'
        if is_pdf:
            row['expectedPhysicalPages'] = len(PdfReader(source_root/doc['path']).pages)
        if len(found) != 1:
            row['errors'].append(f'Expected one backend source, found {len(found)}')
        else:
            file = found[0]
            row.update(sourceRole=file.get('sourceRole'), parseStatus=file.get('parseStatus'),
                       parsed=file.get('parsed'), physicalPages=file.get('pageCount'),
                       ocrUsed=file.get('ocrUsed'), parseMessage=file.get('parseMessage'),
                       textChars=file.get('textChars'), warnings=file.get('warnings', []))
            if not file.get('parsed') or file.get('parseStatus') not in ('PARSED', 'PARTIAL'):
                row['errors'].append('Source has no reviewable parsed text')
            if file.get('sourceRole') != doc['role']:
                row['errors'].append('Backend did not preserve the explicitly declared source role')
            if not is_pdf:
                if file.get('pageCount', 0) != 0:
                    row['errors'].append('DOCX physical page count must remain unknown')
            else:
                pages = row['expectedPhysicalPages']
                if file.get('pageCount') != pages:
                    row['errors'].append('PDF page count differs from the original')
                counts = re.search(r'PDF 共 (\d+) 页，已解析 (\d+) 页，OCR (\d+) 页', file.get('parseMessage') or '')
                if counts:
                    row.update(parsedPhysicalPages=int(counts.group(2)), ocrPages=int(counts.group(3)))
                    row['unparsedPhysicalPages'] = pages-row['parsedPhysicalPages']
                    blank = re.search(r'确认空白 (\d+) 页', file.get('parseMessage') or '')
                    row['confirmedBlankPages'] = int(blank.group(1)) if blank else None
                    if int(counts.group(1)) != pages or not 0 <= row['ocrPages'] <= row['parsedPhysicalPages'] <= pages:
                        row['errors'].append('Parser physical-page coverage counters are inconsistent')
                    if file.get('parseStatus') == 'PARSED' and row['parsedPhysicalPages'] != pages:
                        row['errors'].append('A PARSED PDF reports incomplete physical-page coverage')
                    if row['confirmedBlankPages'] is not None and not 0 <= row['confirmedBlankPages'] <= row['parsedPhysicalPages']-row['ocrPages']:
                        row['errors'].append('Confirmed blank-page count is inconsistent with page coverage')
                else:
                    row['errors'].append('PDF parser did not report physical-page coverage counters')
                if (doc['key'] in ('FT', 'AA', 'APL') or doc.get('scanType') == 'image_only_scan_candidate') and not file.get('ocrUsed'):
                    row['errors'].append('Known image scan was parsed without OCR')
        errors.extend({'fileName': name, 'message': error} for error in row['errors'])
        rows.append(row)
    extras = sorted({file.get('fileName') for file in files}-known_names)
    errors.extend({'fileName': name, 'message': 'Backend source is outside the recorded allowlist'} for name in extras)
    statuses = {}
    for row in rows:
        status = row.get('parseStatus', 'MISSING')
        statuses[status] = statuses.get(status, 0)+1
    return {'documents': rows, 'errors': errors, 'readyForReview': not errors,
            'expectedDocuments': len(documents), 'backendDocuments': len(files),
            'parseStatusCounts': statuses,
            'expectedPdfPages': sum(row.get('expectedPhysicalPages') or 0 for row in rows),
            'backendPdfPages': sum(row.get('physicalPages') or 0 for row in rows),
            'parsedPdfPages': sum(row.get('parsedPhysicalPages') or 0 for row in rows),
            'unparsedPdfPages': sum(row.get('unparsedPhysicalPages') or 0 for row in rows),
            'ocrPages': sum(row.get('ocrPages') or 0 for row in rows),
            'confirmedBlankPdfPages': sum(row.get('confirmedBlankPages') or 0 for row in rows),
            'pureScanDocuments': sum(row['scanType'] == 'image_only_scan_candidate' for row in rows),
            'pureScanPages': sum(row.get('expectedPhysicalPages') or 0 for row in rows if row['scanType'] == 'image_only_scan_candidate'),
            'note': 'Per-page parsed/OCR counts are captured from the parser status message. PARTIAL warnings remain visible; readiness means usable text, not complete OCR or semantic accuracy.'}


class SourceVerifier:
    def __init__(self, documents, root, output, model_url, session):
        self.paths = {Path(d['path']).name: (root/d['path']).resolve() for d in documents}
        self.output, self.model_url, self.session = output, model_url, session
        self.text, self.page_counts, self.page_methods = {}, {}, {}

    def path(self, evidence):
        name = evidence.get('fileName')
        if name not in self.paths:
            raise AssertionError(f'Evidence references a file outside the upload allowlist: {name}')
        return self.paths[name]

    def pdf_page(self, path, page):
        import pypdfium2 as pdfium
        from pypdf import PdfReader
        key = (str(path), page)
        if key in self.text:
            return self.text[key]
        reader = PdfReader(path)
        self.page_counts[str(path)] = len(reader.pages)
        assert 1 <= page <= len(reader.pages), (path, page)
        native = reader.pages[page-1].extract_text() or ''
        with pdfium.PdfDocument(path) as pdf:
            handle = pdf[page-1]
            textpage = handle.get_textpage()
            try:
                native_alternative = textpage.get_text_range()
            finally:
                textpage.close()
            if len(re.sub(r'\s', '', native)) >= 40:
                value = [norm(native), norm(native_alternative)]
                self.page_methods[key] = 'native_pdf_text'
            else:
                bitmap = handle.render(scale=200/72)
                image = bitmap.to_pil().copy()
                bitmap.close()
                image_data = io.BytesIO()
                image.save(image_data, format='PNG')
                response = self.session.post(self.model_url+'/ocr',
                    files={'file': (f'{path.stem}_P{page}.png', image_data.getvalue(), 'image/png')}, timeout=(10, 120))
                response.raise_for_status()
                parsed = response.json()
                save(self.output/'independent_ocr'/f'{path.stem}_P{page}.json', parsed)
                value = [norm(parsed['text'])]
                self.page_methods[key] = 'independent_ocr_200_dpi'
            handle.close()
        self.text[key] = value
        return value

    def check(self, evidence):
        path = self.path(evidence)
        quote = norm(evidence.get('quote'))
        assert evidence.get('anchor'), ('Evidence has no anchor', evidence)
        if not evidence.get('located'):
            return {'located': False, 'source_verified': False, 'reason': 'System marks quotation unlocated'}
        assert len(quote) >= 12, ('Located quotation too short', evidence)
        page = evidence.get('pageNo')
        if path.suffix.lower() == '.docx':
            assert page in (None, ''), ('Unrendered Word evidence invents a physical page', evidence)
            key = str(path)
            extraction_cache_used = key in self.text
            if key not in self.text:
                self.text[key] = effective_docx(path)
            assert quote in self.text[key], ('Located Word quote absent from independently extracted effective source', evidence)
            method = 'effective OOXML excluding explicit strike/dstrike and tracked deletion'
            text_method = 'explicit_deletions_excluded_ooxml'
            source_effectiveness = 'explicit_deletions_excluded; inherited_style_strike_and_contract_effect_unknown'
        else:
            match = re.fullmatch(r'P?(\d+)', str(page or ''))
            assert match, ('PDF evidence has no physical page', evidence)
            page_number = int(match.group(1))
            extraction_cache_used = (str(path), page_number) in self.text
            candidates = self.pdf_page(path, page_number)
            assert any(quote in text for text in candidates), ('Located PDF quote absent from independently parsed physical page', evidence)
            method = 'physical PDF page: native text or independently rerun OCR at 200 DPI'
            text_method = self.page_methods[(str(path), page_number)]
            source_effectiveness = 'unknown; PDF_visual_strike_deletion_or_annotation_not_resolved'
        bbox = evidence.get('bbox')
        if bbox is not None:
            assert len(bbox) == 4 and all(isinstance(x, (int, float)) and 0 <= x <= 1 for x in bbox), ('Invalid normalized box', evidence)
            assert bbox[0]+bbox[2] <= 1.00001 and bbox[1]+bbox[3] <= 1.00001, ('Box extends beyond page', evidence)
        return {'located': True, 'source_verified': True, 'method': method,
                'textExtractionMethod': text_method, 'verificationScope': 'literal_quotation_and_location',
                'extractionCacheUsed': extraction_cache_used, 'extractionCacheScope': 'same_process_source_extraction',
                'sourceEffectiveness': source_effectiveness, 'effectivenessVerified': False,
                'fileKey': evidence.get('fileKey'), 'fileName': path.name,
                'documentId': evidence.get('documentId'), 'anchor': evidence.get('anchor'), 'pageNo': page, 'bbox': bbox}


def evidence_identity(item):
    return tuple(str(item.get(key) or '') for key in ('side', 'documentId', 'fileKey', 'fileName', 'pageNo', 'anchor')) + (norm(item.get('quote')), bool(item.get('located')))


def evaluation_matrix(findings, documents, coverage=None):
    cases = json.loads((HERE/'vetting_cases.json').read_text(encoding='utf-8'))['cases']
    provided = {d['path'].replace('\\', '/') for d in documents}
    result = []
    for case in cases:
        matches = []
        for finding in findings:
            evidence = finding.get('evidence') or []
            matched = []
            for expected in case['evidence']:
                texts = [item.get('quote') or '' for item in evidence if item.get('fileKey') == expected['key']]
                matched.append(any(all(norm(needle).casefold() in norm(text).casefold() for needle in expected['needles']) for text in texts))
            if any(matched):
                matches.append({'code': finding['code'], 'source': finding.get('source'),
                    'verification': finding.get('verification'), 'all_evidence_matched': all(matched),
                    'matched_expected_sides': matched, 'title': finding.get('title'), 'body': finding.get('body')})
        missing = [e['path'] for e in case['evidence'] if e['path'] not in provided]
        result.append({'caseId': case['id'], 'category': case['category'], 'expectedJudgment': case['expected']['judgment'],
            'observedFindings': matches, 'missingSourceFiles': sorted(set(missing)),
            'automaticVerdict': 'NOT_SCORED',
            'observedCoverageWarnings': (coverage or {}).get('warnings', []) if case.get('kind') == 'coverage_guard' else [],
            'evidenceAssessment': 'source_not_uploaded' if missing else 'all_expected_evidence_observed' if any(m['all_evidence_matched'] for m in matches) else 'partial_or_no_evidence_match',
            'semanticReviewRequired': True, 'requiredFacts': case.get('required_facts', {}),
            'reviewInstruction': case['expected']['reason'], 'prohibitedClaims': case['expected']['prohibited_claims']})
    return {'cases': result, 'note': 'Gold used only after API runs to prepare this offline review matrix. Evidence matches do not grade judgment; absent findings do not prove negative cases passed.'}


def wait_run(session, prefix, job, output, args, label):
    run_id = job['id']
    deadline = time.monotonic()+args.max_wait
    last = None
    while True:
        marker = (job.get('status'), job.get('phase'), job.get('completedUnits'), job.get('totalUnits'))
        if marker != last:
            print(json.dumps({'run': label, 'id': run_id, 'status': marker[0], 'phase': marker[1],
                              'completed': marker[2], 'total': marker[3], 'message': job.get('message')}, ensure_ascii=False), flush=True)
            last = marker
        save(output/f'{label}_job.json', job)
        if str(job.get('status')).upper() in ('COMPLETED', 'COMPLETE', 'SUCCEEDED', 'SUCCESS'):
            return job
        if str(job.get('status')).upper() in ('FAILED', 'ERROR', 'CANCELLED'):
            raise RuntimeError(f'Run failed: {job}')
        if time.monotonic() > deadline:
            raise TimeoutError(f'Run {run_id} exceeded {args.max_wait} seconds')
        time.sleep(args.poll_interval)
        job = api(session, 'GET', prefix+'/runs/'+urlquote(run_id, safe=''))


def exports(session, prefix, output, findings):
    report = api(session, 'GET', prefix+'/report.json', params={'lang': 'en'})
    assert report.get('job', {}).get('status') == 'COMPLETED', 'Exported report does not identify a completed review'
    assert {f['code']: f['status'] for f in report.get('findings', [])} == {f['code']: f['status'] for f in findings}, 'Report does not preserve current finding identities and human statuses'
    save(output/'report.json', report)
    descriptors = {}
    for extension, mime in [('docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'), ('pdf', 'application/pdf')]:
        response = session.get(prefix+'/report.'+extension, params={'lang': 'en'}, timeout=(10, 300))
        response.raise_for_status()
        assert response.headers.get('Content-Type', '').split(';')[0] == mime, (extension, response.headers)
        data = response.content
        assert data.startswith(b'PK\x03\x04' if extension == 'docx' else b'%PDF-'), (extension, data[:100])
        if extension == 'docx':
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                assert 'word/document.xml' in archive.namelist()
                assert archive.testzip() is None
                visible = norm(word_text(ET.fromstring(archive.read('word/document.xml'))))
        else:
            from pypdf import PdfReader
            pages = PdfReader(io.BytesIO(data)).pages
            assert len(pages) > 0
            visible = norm('\n'.join(page.extract_text() or '' for page in pages))
        assert all(f['code'] in visible for f in findings), ('Export omits finding identifiers', extension)
        (output/('report.'+extension)).write_bytes(data)
        descriptors[extension] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'contentType': mime}
    return descriptors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--project-id')
    parser.add_argument('--reuse-project', action='store_true', help='Upload and run against an explicitly named existing test project')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--resume-completed', action='store_true',
                        help='Resume source verification and exports for the completed run in the existing output directory; no upload or new review')
    modes.add_argument('--upload-only', action='store_true',
                       help='Upload and audit original sources, then stop without creating a review job')
    modes.add_argument('--review-existing', action='store_true',
                       help='Verify the recorded original manifest and backend sources, then create a new review without uploading')
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--documents-manifest', type=Path,
                        help='Explicit DOCX/PDF source allowlist; evaluation comments and Q&A are rejected')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--model-url', default='http://127.0.0.1:8868')
    parser.add_argument('--poll-interval', type=float, default=2)
    parser.add_argument('--max-wait', type=float, default=7200)
    parser.add_argument('--rerun', action='store_true')
    parser.add_argument('--skip-status-check', action='store_true',
                        help='Keep the real human finding statuses; skip the synthetic Handled mutation used by integration checks')
    parser.add_argument('--upload-per-file', action='store_true',
                        help='Upload each original separately within its role group to measure individual parse durations')
    parser.add_argument('--sample-resources', action='store_true',
                        help='Observe nvidia-smi and existing psutil process/system RAM at one-second intervals')
    parser.add_argument('--resource-note', default='', help='Label model warm/cold state and other conditions in sampled resource evidence')
    args = parser.parse_args()
    if (args.reuse_project or args.resume_completed or args.review_existing) and not args.project_id:
        parser.error('--reuse-project, --resume-completed and --review-existing require --project-id')
    if args.review_existing and args.out is None:
        parser.error('--review-existing requires an explicit --out containing the recorded upload manifest')
    if args.upload_only and args.rerun:
        parser.error('--upload-only cannot start a --rerun')
    if args.resume_completed and args.rerun:
        parser.error('--resume-completed cannot start a --rerun; it only verifies an already completed task')
    if args.skip_status_check and args.rerun:
        parser.error('--skip-status-check cannot be combined with the synthetic human-status --rerun check')
    args.out = args.out or WORKSPACE/'tmp/vetting_end_to_end'
    config = json.loads((HERE/'samples.json').read_text(encoding='utf-8'))
    if args.review_existing and args.documents_manifest is None:
        args.documents_manifest = args.out.resolve()/args.project_id/'upload_manifest.json'
    documents = load_documents(config, args.documents_manifest)
    source_root = (args.source_root or WORKSPACE/config['source_directory']).resolve()
    project = args.project_id or 'vetting-e2e-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+uuid.uuid4().hex[:8]
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,100}', project), 'Use a project ID containing only letters, numbers, underscore and hyphen'
    output = args.out.resolve()/project
    output.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    base = args.base_url.rstrip('/')
    prefix = base+'/api/vetting/'+project
    summary = {'projectId': project, 'baseUrl': base, 'output': str(output), 'startedAt': datetime.now(timezone.utc).isoformat()}
    if (args.resume_completed or args.review_existing) and (output/'summary.json').exists():
        summary = json.loads((output/'summary.json').read_text(encoding='utf-8'))
        summary.pop('error', None)
        summary['resumedAt' if args.resume_completed else 'reviewRequestedAt'] = datetime.now(timezone.utc).isoformat()
    previous_mode = summary.get('mode')
    summary['mode'] = 'upload_only' if args.upload_only else 'review_existing' if args.review_existing else 'resume_completed' if args.resume_completed else 'upload_and_review'
    summary['sourceRoot'] = str(source_root)
    save(output/'summary.json', summary)
    sampler = None
    if args.sample_resources:
        from resource_sample import ResourceSampler
        sampler_output = output
        if args.review_existing or args.resume_completed:
            sampler_output = output/'resources'/(summary['mode']+'-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+uuid.uuid4().hex[:8])
            sampler_output.mkdir(parents=True, exist_ok=False)
            if summary.get('sampledResources'):
                summary.setdefault('resourceHistory', []).append({'mode': previous_mode,
                    'output': summary.get('resourceOutput', str(output)), 'summary': summary['sampledResources']})
        summary['resourceOutput'] = str(sampler_output)
        sampler = ResourceSampler(sampler_output, args.resource_note)
    try:
        existing = api(session, 'GET', base+'/api/projects')
        exists = any(p['id'] == project for p in existing)
        if args.reuse_project or args.resume_completed or args.review_existing:
            assert exists, 'The explicitly named test project does not exist'
        else:
            assert not exists, 'Test project already exists; use a unique --project-id'
            created = api(session, 'POST', base+'/api/projects', json={'id': project,
                'nameZhHans': 'Vetting端到端验收 '+project, 'nameEn': 'Vetting integration '+project, 'contractNo': '20250101'})
            save(output/'project.json', created)
        if sampler:
            sampler.start()
        with ExitStack() as handles:
            multipart, manifest = [], []
            for doc in documents:
                path = (source_root/doc['path']).resolve()
                assert path.is_relative_to(source_root) and path.is_file(), path
                assert not any(part.startswith(('2d_', '2e_')) for part in path.relative_to(source_root).parts), path
                mime = 'application/pdf' if path.suffix.lower() == '.pdf' else 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
                multipart.append((doc['role'], ('files', (path.name, handles.enter_context(path.open('rb')), mime))))
                source_hash = hashlib.sha256(path.read_bytes()).hexdigest()
                assert not doc.get('sha256') or doc['sha256'] == source_hash, ('Original source SHA differs from the explicit manifest', doc['path'])
                manifest.append({**doc, 'sha256': source_hash, 'bytes': path.stat().st_size})
            if args.resume_completed or args.review_existing:
                recorded_manifest = json.loads((output/'upload_manifest.json').read_text(encoding='utf-8'))
                assert recorded_manifest == manifest, 'The original upload manifest or source bytes changed; do not resume quotation verification against different inputs'
            else:
                save(output/'upload_manifest.json', manifest)
                uploaded = {}
                upload_clock = time.monotonic()
                upload_timings = []
                role_timings = []
                summary['uploadStartedAt'] = datetime.now(timezone.utc).isoformat()
                save(output/'summary.json', summary)
                for source_role in dict.fromkeys(doc['role'] for doc in documents):
                    selected = [item for role, item in multipart if role == source_role]
                    print(json.dumps({'phase': 'upload', 'projectId': project, 'role': source_role,
                                      'documents': len(selected)}, ensure_ascii=False), flush=True)
                    if sampler:
                        sampler.set_phase('upload_parse:'+source_role)
                    role_clock = time.monotonic()
                    role_started = datetime.now(timezone.utc).isoformat()
                    uploaded[source_role] = [] if args.upload_per_file else None
                    for batch in ([ [item] for item in selected ] if args.upload_per_file else [selected]):
                        batch_clock = time.monotonic()
                        batch_started = datetime.now(timezone.utc).isoformat()
                        name = batch[0][1][0] if args.upload_per_file else None
                        if args.upload_per_file:
                            print(json.dumps({'phase': 'upload_parse_file', 'projectId': project,
                                'role': source_role, 'fileName': name}, ensure_ascii=False), flush=True)
                        result = api(session, 'POST', prefix+'/package/upload',
                            params={'sourceRole': source_role}, files=batch)
                        timing = {'sourceRole': source_role, 'documents': len(batch), 'fileName': name,
                            'startedAt': batch_started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
                            'uploadParseSeconds': time.monotonic()-batch_clock}
                        upload_timings.append(timing)
                        if args.upload_per_file:
                            uploaded[source_role].append({'fileName': name, 'result': result})
                        else:
                            uploaded[source_role] = result
                        save(output/'upload_timings.json', upload_timings)
                        save(output/'upload_result.json', uploaded)
                        save(output/'files.json', api(session, 'GET', prefix+'/files'))
                    role_timings.append({'sourceRole': source_role, 'documents': len(selected),
                        'startedAt': role_started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
                        'uploadParseSeconds': time.monotonic()-role_clock})
                    save(output/'upload_role_timings.json', role_timings)
                summary['uploadFinishedAt'] = datetime.now(timezone.utc).isoformat()
                summary['uploadParseSeconds'] = time.monotonic()-upload_clock
                summary['uploadParseTimings'] = upload_timings
                summary['uploadRoleTimings'] = role_timings
                save(output/'upload_result.json', uploaded)
        if sampler:
            sampler.set_phase('source_audit')
        files = api(session, 'GET', prefix+'/files')
        save(output/'files.json', files)
        audit = audit_sources(documents, source_root, files)
        save(output/'source_audit.json', audit)
        summary.update(uploadedDocuments=len(files), readyForReview=audit['readyForReview'],
                       sourceAudit={key: value for key, value in audit.items() if key != 'documents'})
        if args.upload_only:
            latest = api(session, 'GET', prefix+'/runs/latest')
            assert latest is None, 'Upload-only integration requires a project with no review job; it did not start a job'
            summary.update(status='uploaded', uploadedDocuments=len(files), readyForReview=audit['readyForReview'],
                           sourceAudit={key: value for key, value in audit.items() if key != 'documents'},
                           reviewStarted=False, reviewRunId=None,
                           finishedAt=datetime.now(timezone.utc).isoformat())
            print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
            return
        assert audit['readyForReview'], ('Source audit failed; review will not start', audit['errors'])
        for doc in documents:
            found = [f for f in files if f['fileName'] == Path(doc['path']).name]
            assert len(found) == 1 and found[0].get('parsed'), ('Uploaded file not parsed', doc, found)
            if args.documents_manifest:
                assert found[0].get('sourceRole') == doc['role'], ('Backend did not preserve the explicitly supplied source role', doc, found[0])
            if Path(doc['path']).suffix.lower() == '.docx':
                assert found[0].get('pageCount', 0) == 0, ('DOCX physical page count must remain unknown', found[0])
            else:
                from pypdf import PdfReader
                page_count = len(PdfReader(source_root/doc['path']).pages)
                assert found[0].get('pageCount') == page_count, ('PDF page count differs from original', doc, found[0])
                if doc['key'] in ('FT', 'AA', 'APL'):
                    assert found[0].get('ocrUsed'), ('Known scan was parsed without OCR', doc, found[0])
        if args.resume_completed:
            recorded = json.loads((output/'primary_job.json').read_text(encoding='utf-8'))
            job = api(session, 'GET', prefix+'/runs/'+urlquote(recorded['id'], safe=''))
            latest = api(session, 'GET', prefix+'/runs/latest')
            assert job['id'] == latest['id'] and job['status'] == 'COMPLETED', 'Resuming exports requires the recorded run to remain the latest completed run'
        else:
            job = api(session, 'POST', prefix+'/runs', params={'lang': 'en'})
        if sampler:
            sampler.set_phase('index_and_review')
        summary.update(status='reviewing', reviewStarted=True, reviewRunId=job['id'])
        save(output/'summary.json', summary)
        completed = wait_run(session, prefix, job, output, args, 'primary')
        summary['reviewJobStartedAt'] = completed.get('startedAt')
        summary['reviewJobFinishedAt'] = completed.get('finishedAt')
        if completed.get('startedAt') and completed.get('finishedAt'):
            summary['indexReviewSeconds'] = (datetime.fromisoformat(completed['finishedAt'])
                - datetime.fromisoformat(completed['startedAt'])).total_seconds()
        if sampler:
            sampler.set_phase('independent_verification_and_exports')
        findings = api(session, 'GET', prefix+'/findings')
        save(output/'findings.json', findings)
        assert findings, 'Integration corpus produced no findings'
        verifier = SourceVerifier(documents, source_root, output, args.model_url.rstrip('/'), session)
        verified = []
        for finding in findings:
            evidence = finding.get('evidence') or []
            fetched = api(session, 'GET', prefix+'/findings/'+urlquote(finding['code'], safe='')+'/evidence')
            filename = re.sub(r'[^A-Za-z0-9_-]', '_', finding['code'])[:100]+'-'+hashlib.sha256(finding['code'].encode()).hexdigest()[:8]+'.json'
            save(output/'evidence'/filename, fetched)
            assert sorted(evidence_identity(e) for e in evidence) == sorted(evidence_identity(e) for e in fetched.get('items', [])), ('Finding and evidence endpoint differ', finding['code'])
            checked = [verifier.check(e) for e in evidence]
            verified.append({'code': finding['code'], 'verification': finding.get('verification'), 'sides': checked})
        save(output/'quote_verification.json', verified)
        matrix = evaluation_matrix(findings, documents, completed.get('coverage'))
        save(output/'evaluation_matrix.json', matrix)
        rule = next((f for f in findings if str(f.get('source', '')).upper() == 'RULE'), None)
        if rule and not args.skip_status_check:
            handled = api(session, 'POST', prefix+'/findings/'+urlquote(rule['code'], safe='')+'/status', params={'status': 'Handled'})
            assert handled['status'] == 'Handled'
            save(output/'handled_finding.json', handled)
            if args.rerun:
                rerun = api(session, 'POST', prefix+'/runs', params={'lang': 'en'})
                wait_run(session, prefix, rerun, output, args, 'rerun')
                later = api(session, 'GET', prefix+'/findings')
                save(output/'rerun_findings.json', later)
                retained = [f for f in later if f.get('fingerprint') == rule['fingerprint']]
                assert len(retained) == 1 and retained[0]['code'] == rule['code'] and retained[0]['status'] == 'Handled', 'Rerun lost the human-reviewed finding identity/status'
                summary['rerunPreservesStatusCodeFingerprint'] = True
        elif args.rerun:
            raise AssertionError('No rule finding available for stable-identity rerun check')
        report_findings = api(session, 'GET', prefix+'/findings')
        save(output/'report_findings.json', report_findings)
        report_verification = [{'code': f['code'], 'verification': f.get('verification'),
            'sides': [verifier.check(e) for e in f.get('evidence') or []]} for f in report_findings]
        save(output/'report_quote_verification.json', report_verification)
        descriptors = exports(session, prefix, output, report_findings)
        summary.update(status='passed', uploadedDocuments=len(documents), findings=len(findings),
            exportedFindings=len(report_findings),
            locatedQuotesVerified=sum(sum(e['source_verified'] for e in row['sides']) for row in report_verification),
            pdfQuotesNeedingVisualEffectivenessReview=sum(sum(str(e.get('textExtractionMethod', '')).endswith(('pdf_text', 'ocr_200_dpi')) for e in row['sides']) for row in report_verification),
            independentlyOcrMatchedQuotes=sum(sum(e.get('textExtractionMethod') == 'independent_ocr_200_dpi' for e in row['sides']) for row in report_verification),
            quotationVerificationScope='Literal quotation and source location; source_verified does not establish that a visually struck/deleted/annotated PDF clause is effective. DOCX excludes explicit run deletions; inherited style and contractual effect remain unknown.',
            exports=descriptors, ruleStatusUpdateChecked=rule is not None and not args.skip_status_check,
            statusMutationSkipped=args.skip_status_check,
            professionalApproval='No professional approval is implied by this integration run or its exported finding statuses.',
            rerunRequested=args.rerun,
            evaluationCases=len(matrix['cases']), evaluationSemanticAccuracy='not scored; review evaluation_matrix.json',
            coverage=completed.get('coverage'), finishedAt=datetime.now(timezone.utc).isoformat())
        print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    except Exception as error:
        summary.update(status='failed', error=f'{type(error).__name__}: {error}')
        raise
    finally:
        if sampler:
            summary['sampledResources'] = sampler.stop()
        save(output/'summary.json', summary)
        session.close()


if __name__ == '__main__':
    main()
