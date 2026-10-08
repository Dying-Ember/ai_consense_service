"""Small, staged document/retrieval benchmark. No generative vetting verdicts."""
from __future__ import annotations

import argparse
import contextlib
import copy
from datetime import datetime, timezone
import gc
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import sys
import time
import traceback
import uuid
from experiment_log import DEFAULT_ROOT as DEFAULT_LOG_ROOT, append_record

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]
CLAUSE = re.compile(r"^\s*((?:SCC\d+(?:\.\d+)*|PRE\.[A-Z]\d+(?:\.\d+)*))(?:\.[A-Z0-9])?", re.I)


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".pending")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def norm(value):
    return re.sub(r"\s+", " ", value).casefold().replace("’", "'").replace("‘", "'")


def tokens(value):
    # Keep clause identifiers as tokens, alongside ordinary terms.
    return re.findall(r"[a-z0-9]+(?:[./()-][a-z0-9]+)*", value.casefold())


def release_cuda(model):
    del model
    gc.collect()
    import torch
    if torch.cuda.is_available():
        torch.cuda.empty_cache()


def gpu_stats():
    import torch
    return {
        "allocated_peak_mib": round(torch.cuda.max_memory_allocated() / 2**20, 1),
        "reserved_peak_mib": round(torch.cuda.max_memory_reserved() / 2**20, 1),
    } if torch.cuda.is_available() else {}


def runtime_device(requested):
    import torch
    if requested == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable; use --device cpu explicitly")
    torch.set_num_threads(4)
    if requested == "cuda":
        torch.cuda.reset_peak_memory_stats()
    return requested


def probe(args, config):
    import psutil
    import torch
    packages = ["torch", "transformers", "sentence-transformers", "rapidocr", "onnxruntime", "qdrant-client"]
    data = {"packages": {x: importlib.metadata.version(x) for x in packages},
            "cpu_threads": os.cpu_count(), "ram_gib": round(psutil.virtual_memory().total / 2**30, 1),
            "cuda_available": torch.cuda.is_available(), "torch_cuda": torch.version.cuda,
            "models": {k: config[k] for k in ("embedding", "reranker")}}
    if torch.cuda.is_available():
        data.update(gpu=torch.cuda.get_device_name(0),
                    free_total_gpu_bytes=list(torch.cuda.mem_get_info()))
    try:
        data["nvidia_smi"] = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=name,memory.used,memory.total,driver_version", "--format=csv,noheader"],
            text=True).strip()
    except (FileNotFoundError, subprocess.CalledProcessError):
        pass
    save(args.out / "environment.json", data)
    print(json.dumps(data, indent=2), flush=True)


def source_path(args, config, doc):
    root = args.source_root or WORKSPACE / config["source_directory"]
    path = (root / doc["path"]).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Source path escapes configured root")
    if not path.is_file():
        raise FileNotFoundError(path)
    return path


def ocr_settings(args):
    return {name: getattr(args, 'ocr_' + name, default) for name, default in
            (('max_side_len', 2000), ('text_score', 0.5), ('box_thresh', 0.5),
             ('intra_threads', 4), ('inter_threads', 1))}


def requested_ocr_recipe(args):
    from ocr_runtime import ocr_cache_recipe, content_identity
    recipe = ocr_cache_recipe(args.out / 'ocr_models', require_verified_defaults=args.offline, **ocr_settings(args))
    recipe['pilotImplementation'] = content_identity(__file__)
    recipe['raster'] = {'dpi': args.dpi, 'pdfiumScale': args.dpi / 72}
    return recipe


def ocr(args, config):
    from ocr_runtime import create_ocr_engine, identity_hash, validate_ocr_cache, OCR_CACHE_SCHEMA, local_model_paths
    engine, recipe = None, None
    documents = {d["key"]: d for d in config["documents"]}
    cases = []
    for case in config["ocr_cases"]:
        doc = documents[case["key"]]
        path = source_path(args, config, doc)
        case_id = f'{case["key"]}_{case["page"]}'
        cached = args.out / "ocr" / f"{case_id}.json"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if cached.exists() and not args.refresh:
            data = read(cached)
            # Legacy bytes must never be silently signed with today's settings.
            if data.get('ocrCacheSchemaVersion') != OCR_CACHE_SCHEMA:
                raise RuntimeError(f'Stale or unsigned OCR cache: {cached}; run ocr --refresh. Do not hand-patch signatures.')
            recipe = recipe or requested_ocr_recipe(args)
            validate_ocr_cache(data, recipe=recipe, source_hash=digest, key=case['key'],
                               page=case['page'], dpi=args.dpi, cache=cached)
        else:
            if recipe is None:
                try:
                    recipe = requested_ocr_recipe(args)
                except FileNotFoundError:
                    if args.offline:
                        raise
                    # Preserve the legacy online first-load route. Compute the
                    # complete identity after downloaded weights exist, before inference.
                    engine = create_ocr_engine(args.out / 'ocr_models', **ocr_settings(args))
                    recipe = requested_ocr_recipe(args)
            if engine is None:
                local = {'local_paths': local_model_paths(args.out / 'ocr_models')} if args.offline else {}
                engine = create_ocr_engine(args.out / 'ocr_models', **ocr_settings(args), **local)
                # RapidOCR can repair/redownload an existing corrupt weight.
                # Never sign output with bytes from before such a replacement.
                if requested_ocr_recipe(args) != recipe:
                    raise RuntimeError('OCR weights/configuration changed during engine initialization; rerun with verified local weights in a fresh output')
            import pypdfium2 as pdfium
            from PIL import ImageDraw
            with pdfium.PdfDocument(path) as pdf:
                page = pdf[case["page"] - 1]
                bitmap = page.render(scale=args.dpi / 72)
                image = bitmap.to_pil().copy()
                bitmap.close()
                page.close()
            start = time.perf_counter()
            result = engine(image)
            lines = []
            if result.txts is not None:
                for text, score, box in zip(result.txts, result.scores, result.boxes):
                    lines.append({"text": text, "score": float(score), "polygon": box.tolist()})
            data = {"key": case["key"], "page": case["page"], "source": doc["path"],
                    "source_hash": digest, "dpi": args.dpi, "image_size": list(image.size),
                    'ocrCacheSchemaVersion': OCR_CACHE_SCHEMA, 'ocrCacheRecipe': recipe,
                    'ocrCacheSignature': identity_hash(recipe),
                    "engine": "RapidOCR/ONNXRuntime CPU baseline", "seconds": time.perf_counter() - start,
                    "lines": lines, "text": "\n".join(x["text"] for x in lines),
                    "limitations": ["No table-cell or deletion-state interpretation", "Selected pages only"]}
            cached.parent.mkdir(parents=True, exist_ok=True)
            image.save(cached.with_suffix(".png"))
            overlay = image.copy()
            draw = ImageDraw.Draw(overlay)
            for line in lines:
                draw.polygon([tuple(p) for p in line["polygon"]], outline="red", width=2)
            overlay.save(cached.with_name(case_id + "_boxes.png"))
            save(cached, data)
        data["needle_checks"] = {s: norm(s) in norm(data["text"]) for s in case["needles"]}
        save(cached, data)
        cases.append({k: data[k] for k in ("key", "page", "seconds", "needle_checks")})
        print(json.dumps(cases[-1], ensure_ascii=False), flush=True)
    checked = sum(len(c["needle_checks"]) for c in cases)
    passed = sum(sum(c["needle_checks"].values()) for c in cases)
    save(args.out / "ocr_report.json", {"cases": cases, "anchor_checks_passed": passed,
                                       "anchor_checks_total": checked,
                                       'ocrCacheSchemaVersion': OCR_CACHE_SCHEMA,
                                       'ocrCacheRecipe': recipe, 'ocrCacheSignature': identity_hash(recipe) if recipe else None,
                                       "note": "Anchor smoke checks, not character accuracy or document completeness"})


def docx_blocks(path):
    from docx import Document
    from docx.oxml.ns import qn
    import xml.etree.ElementTree as ET
    import zipfile
    doc = Document(path)
    result = []

    def effective(node):
        pieces = {"text": [], "deleted_text": [], "struck_text": [], "original_text": []}

        def walk(item, deleted=False, struck=False):
            deleted = deleted or item.tag in (qn("w:del"), qn("w:moveFrom"), qn("w:delText"))
            if item.tag == qn("w:r"):
                props = item.find(qn("w:rPr"))
                if props is not None:
                    struck = struck or any(p.tag in (qn("w:strike"), qn("w:dstrike")) and
                        p.get(qn("w:val"), "true").lower() not in ("0", "false", "off") for p in props)
            if item.tag in (qn("w:t"), qn("w:delText"), qn("w:tab"), qn("w:br"), qn("w:cr")):
                text = item.text or "" if item.tag in (qn("w:t"), qn("w:delText")) else " "
                pieces["original_text"].append(text)
                pieces["deleted_text" if deleted else "struck_text" if struck else "text"].append(text)
                return
            for child in item:
                walk(child, deleted, struck)
            if item.tag == qn("w:p"):
                pieces["original_text"].append("\n")
                pieces["deleted_text" if deleted else "struck_text" if struck else "text"].append("\n")

        walk(node)
        return {key: "".join(value).strip() for key, value in pieces.items()}

    for index, element in enumerate(doc.element.body):
        if element.tag == qn("w:p"):
            data = effective(element)
            if data["original_text"]:
                result.append({**data, "location": f"body/{index}/paragraph", "page": None, "kind": "paragraph"})
        elif element.tag == qn("w:tbl"):
            for row_index, row in enumerate(element.xpath("./w:tr")):
                cells = [effective(cell) for cell in row.xpath("./w:tc")]
                data = {key: " | ".join(cell[key] for cell in cells) for key in ("text", "deleted_text", "struck_text", "original_text")}
                result.append({**data, "cells": [cell["text"] for cell in cells], "page": None,
                               "location": f"body/{index}/table-row/{row_index}", "kind": "table_row"})
    # Word pages are not inferred from paragraph count. Headers and footers stay separate.
    seen = set()
    for section_index, section in enumerate(doc.sections):
        for kind in ("header", "footer"):
            part = getattr(section, kind)
            if part.part.partname in seen:
                continue
            seen.add(part.part.partname)
            data = effective(part._element)
            if data["original_text"]:
                result.append({**data, "page": None, "kind": kind,
                               "location": f"section/{section_index}/{kind}"})
    with zipfile.ZipFile(path) as archive:
        for kind in ('footnote', 'endnote'):
            name = f'word/{kind}s.xml'
            if name not in archive.namelist():
                continue
            root = ET.fromstring(archive.read(name))
            for note in root.findall(qn('w:'+kind)):
                note_id = int(note.get(qn('w:id'), '0'))
                if note_id <= 0:
                    continue
                data = effective(note)
                if data['original_text']:
                    result.append({**data, 'page': None, 'kind': kind,
                                   'location': f'{kind}/{note_id}'})
    return result


def pdf_blocks(path, key, digest, out, *, ocr_recipe=None, dpi=200):
    from pypdf import PdfReader
    result, pending = [], []
    reader = PdfReader(path)
    for n, page in enumerate(reader.pages, 1):
        text = page.extract_text() or ""
        block = {"text": text, "page": n, "kind": "pdf_page", "location": f"pdf-page/{n}"}
        # Pilot heuristic is per page; drawings / corrupt text still require manual review.
        if len(re.sub(r"\s", "", text)) < 40:
            cache = out / "ocr" / f"{key}_{n}.json"
            if cache.exists():
                from ocr_runtime import validate_ocr_cache, OCR_CACHE_SCHEMA
                data = read(cache)
                if data.get('ocrCacheSchemaVersion') != OCR_CACHE_SCHEMA or ocr_recipe is None:
                    raise RuntimeError(f'Stale or unsigned OCR cache: {cache}; run ocr --refresh, then parse with the same OCR parameters')
                recipe = ocr_recipe() if callable(ocr_recipe) else ocr_recipe
                validate_ocr_cache(data, recipe=recipe, source_hash=digest, key=key, page=n, dpi=dpi, cache=cache)
                block.update(text=data["text"], kind="ocr_page", lines=data["lines"],
                             image_size=data["image_size"], dpi=data["dpi"], ocrCacheSignature=data['ocrCacheSignature'])
            else:
                block.update(text="", kind="needs_ocr")
                pending.append(n)
        result.append(block)
    return result, pending


def split_blocks(blocks, size, overlap):
    # Group nearby DOCX paragraphs / table rows until a clause or size boundary.
    # PDF chunks are never allowed to silently cross physical pages.
    chunks, buffer, length, heading = [], [], 0, ""

    def flush():
        nonlocal buffer, length
        if not buffer:
            return
        raw = "\n".join(b["text"] for b in buffer)
        for start in range(0, len(raw), size - overlap):
            end = min(len(raw), start + size)
            text = raw[start:end]
            if text.strip():
                chunks.append({"text": text, "clause": heading, "page": buffer[0]["page"],
                               "locations": [b["location"] for b in buffer],
                               "buffer_start": start, "buffer_end": end,
                               "has_struck_text": any(b.get("struck_text") for b in buffer),
                               "has_deleted_text": any(b.get("deleted_text") for b in buffer)})
            if end == len(raw):
                break
        buffer, length = [], 0

    for block in blocks:
        if not block["text"].strip() or block["kind"] in ("header", "footer"):
            continue
        match = CLAUSE.match(block["text"])
        if match or (buffer and (block["page"] != buffer[0]["page"] or length + len(block["text"]) > size)):
            flush()
        if match:
            heading = match.group(1).upper()
        buffer.append(block)
        length += len(block["text"]) + 1
    flush()
    return chunks


def parse(args, config):
    if args.chunk_size <= args.overlap or args.overlap < 0:
        raise ValueError("Require chunk-size > overlap >= 0")
    start, corpus, report = time.perf_counter(), [], []
    recipe = None
    def expected_ocr_recipe():
        nonlocal recipe
        recipe = recipe or requested_ocr_recipe(args)
        return recipe
    args.out.mkdir(parents=True, exist_ok=True)
    with (args.out / "blocks.jsonl").open("w", encoding="utf-8") as block_file:
        for doc in config["documents"]:
            path = source_path(args, config, doc)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            pending = []
            if path.suffix.lower() == ".docx":
                blocks = docx_blocks(path)
            elif path.suffix.lower() == ".pdf":
                blocks, pending = pdf_blocks(path, doc["key"], digest, args.out,
                                             ocr_recipe=expected_ocr_recipe, dpi=args.dpi)
            else:
                raise ValueError(f"Unsupported type: {path.suffix}")
            for block in blocks:
                block_file.write(json.dumps({**doc, "source_hash": digest, **block}, ensure_ascii=False) + "\n")
            chunks = split_blocks(blocks, args.chunk_size, args.overlap)
            for chunk in chunks:
                identity = f'{doc["path"]}:{digest}:{chunk["locations"]}:{chunk["buffer_start"]}'
                corpus.append({**doc, **chunk, "source_hash": digest,
                               "id": str(uuid.uuid5(uuid.NAMESPACE_URL, identity)),
                               "content": f'{doc["key"]} {chunk["clause"]}\n{chunk["text"]}'})
            entry = {**doc, "source_hash": digest, "blocks": len(blocks), "chunks": len(chunks),
                     "characters": sum(len(b["text"]) for b in blocks), "pending_ocr_pages": pending,
                     "status": "partial" if pending else "parsed_pilot"}
            report.append(entry)
            print(json.dumps(entry, ensure_ascii=False), flush=True)
    with (args.out / "chunks.jsonl").open("w", encoding="utf-8") as f:
        for chunk in corpus:
            f.write(json.dumps(chunk, ensure_ascii=False) + "\n")
    save(args.out / "parse_report.json", {"seconds": time.perf_counter() - start, "documents": report,
                                        "chunks": len(corpus), "chunk_size": args.chunk_size, "overlap": args.overlap,
                                        'ocrCacheRecipe': recipe,
                                        "note": "Pilot clause grouping; DOCX locations are body positions, not rendered pages"})


def load_chunks(args):
    return [json.loads(line) for line in (args.out / "chunks.jsonl").read_text(encoding="utf-8").splitlines()]


def fingerprint(chunks):
    return hashlib.sha256(json.dumps(chunks, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def embedding_signature(args, config, chunks):
    return {"signature_version": 2, "corpus": fingerprint(chunks), "model": config["embedding"],
            "max_tokens": args.embed_max_tokens, "batch_size": args.batch_size, "device": args.device,
            "normalized": True, "dtype": "float16" if args.device == "cuda" else "float32"}


def require_index_signature(stored, expected):
    if stored != expected:
        raise RuntimeError("Index is stale or uses a different embedding configuration; run index first")


def model_runtime(args, config):
    common = {"device": args.device, "dtype": "float16" if args.device == "cuda" else "float32"}
    return {"embedding": {**config["embedding"], **common, "maxTokens": args.embed_max_tokens,
                          "batchSize": args.batch_size, "normalized": True},
            "reranker": {**config["reranker"], **common, "maxTokens": args.rerank_max_tokens,
                         "batchSize": args.rerank_batch}}


def embed_model(args, config):
    import torch
    from sentence_transformers import SentenceTransformer
    dtype = torch.float16 if args.device == "cuda" else torch.float32
    model = SentenceTransformer(model_path(config["embedding"], args.offline), revision=config["embedding"]["revision"],
                                device=args.device, model_kwargs={"torch_dtype": dtype},
                                local_files_only=args.offline)
    model.max_seq_length = args.embed_max_tokens
    return model


def rerank_model(args, config):
    import torch
    from sentence_transformers import CrossEncoder
    dtype = torch.float16 if args.device == "cuda" else torch.float32
    return CrossEncoder(model_path(config["reranker"], args.offline), revision=config["reranker"]["revision"],
                        device=args.device, max_length=args.rerank_max_tokens, model_kwargs={"torch_dtype": dtype},
                        local_files_only=args.offline)


def model_path(model, offline):
    # Loading a local snapshot also prevents tokenizer heuristics from making
    # model_info network requests in otherwise offline Transformers versions.
    if offline:
        from huggingface_hub import snapshot_download
        return snapshot_download(model["name"], revision=model["revision"], local_files_only=True)
    return model["name"]


def index(args, config):
    import numpy as np
    from qdrant_client import QdrantClient, models
    runtime_device(args.device)
    chunks = load_chunks(args)
    cache = args.out / "embeddings.npy"
    signature = embedding_signature(args, config, chunks)
    metadata = args.out / "index_metadata.json"
    start = time.perf_counter()
    if cache.exists() and metadata.exists() and read(metadata)["signature"] == signature and not args.refresh:
        vectors = np.load(cache)
        stats, seconds = {}, 0
    else:
        model = embed_model(args, config)
        vectors = model.encode([c["content"] for c in chunks], batch_size=args.batch_size,
                               normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=True)
        stats, seconds = gpu_stats(), time.perf_counter() - start
        np.save(cache, vectors)
        del model
        gc.collect()
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    client = QdrantClient(path=str(args.out / "qdrant"))
    # Collection name binds corpus and embedding settings; unrelated indexes are retained.
    collection = "pilot_" + hashlib.sha256(json.dumps(signature, sort_keys=True).encode()).hexdigest()[:16]
    try:
        if not client.collection_exists(collection):
            client.create_collection(collection, vectors_config=models.VectorParams(size=vectors.shape[1], distance=models.Distance.COSINE))
        for start_idx in range(0, len(chunks), 128):
            client.upsert(collection, points=[models.PointStruct(id=c["id"], vector=vectors[i].tolist(), payload=c)
                          for i, c in enumerate(chunks[start_idx:start_idx + 128], start_idx)])
        count = client.count(collection, exact=True).count
        if count != len(chunks):
            raise RuntimeError(f"Index count mismatch: {count} != {len(chunks)}")
    finally:
        client.close()
    save(metadata, {"signature": signature, "collection": collection, "count": count,
                    "runtime": model_runtime(args, config),
                    "dimensions": int(vectors.shape[1]), "embedding_seconds": seconds,
                    "index_stage_seconds": time.perf_counter() - start, "gpu": stats,
                    "mode": "Qdrant Python local persistence; not server throughput"})
    print(json.dumps(read(metadata), indent=2), flush=True)


def expected_match(chunk, expectation):
    return chunk["key"] == expectation["key"] and all(norm(s) in norm(chunk["text"]) for s in expectation["contains"])


def json_fingerprint(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode("utf-8")).hexdigest()


def ordered_pair_identity(pairs, candidate_ids):
    """Bind exact query/content bytes and ordering without altering source text."""
    if len(pairs) != len(candidate_ids):
        raise ValueError("Candidate identity count does not match rerank pairs")
    rows = [{"id": identity, "querySha256": hashlib.sha256(query.encode("utf-8")).hexdigest(),
             "contentSha256": hashlib.sha256(content.encode("utf-8")).hexdigest()}
            for identity, (query, content) in zip(candidate_ids, pairs)]
    return {"schemaVersion": 2, "pairs": rows, "orderedInputSha256": json_fingerprint(rows),
            "meaning": "Exact UTF-8 source pair identity; no normalization or semantic claim."}


def pair_token_observations(ranker, pairs, max_tokens, batch_size=4):
    """Mirror ST 5.2 predict batching/tokenization on CPU, without model forward."""
    effective_max = int(ranker.tokenizer.model_max_length)
    if effective_max <= 0 or batch_size <= 0:
        raise ValueError("Effective tokenizer limit and batch size must be positive")
    encoded = ranker.tokenizer([pair[0] for pair in pairs], [pair[1] for pair in pairs],
                               padding=False, truncation=False, return_length=True)
    lengths = [len(ids) for ids in encoded["input_ids"]]
    if len(lengths) != len(pairs):
        raise RuntimeError("Tokenizer pair observation count does not match rerank inputs")
    batches, retained, retained_hashes = [], [], []
    for start in range(0, len(pairs), batch_size):
        # This is the exact call used by the pinned CrossEncoder.predict. No explicit
        # max_length argument: AutoTokenizer.model_max_length is the effective limit.
        features = ranker.tokenizer(pairs[start:start+batch_size], padding=True,
                                    truncation=True, return_tensors="pt")
        values = {key: tensor.tolist() for key, tensor in features.items()}
        if len(values["input_ids"]) != len(pairs[start:start+batch_size]):
            raise RuntimeError("Actual tokenizer batch count does not match rerank inputs")
        batch = {"start": start, "rows": len(values["input_ids"]),
                 "modelInputSha256": json_fingerprint(values),
                 "tensorDtypes": {key: str(tensor.dtype) for key, tensor in features.items()},
                 "paddedTokens": [len(row) for row in values["input_ids"]]}
        batches.append(batch)
        for ids, mask in zip(values["input_ids"], values["attention_mask"]):
            visible = [token for token, used in zip(ids, mask) if used]
            retained.append(len(visible))
            retained_hashes.append(json_fingerprint(visible))
    if len(retained) != len(lengths):
        raise RuntimeError("Retained token observation count does not match rerank inputs")
    return {"schemaVersion": 2, "untruncatedTokens": lengths, "retainedTokens": retained,
            "retainedInputSha256": retained_hashes, "batches": batches,
            "predictInputSha256": json_fingerprint([batch["modelInputSha256"] for batch in batches]),
            "pairs": len(lengths), "requestedMaxTokens": max_tokens, "effectiveMaxTokens": effective_max,
            "batchSize": batch_size, "tokenizerClass": type(ranker.tokenizer).__name__,
            "truncationSide": ranker.tokenizer.truncation_side, "paddingSide": ranker.tokenizer.padding_side,
            "pairsOverLimit": sum(length > effective_max for length in lengths),
            "pairsActuallyTruncated": sum(before > after for before, after in zip(lengths, retained)),
            "maximumTokens": max(lengths, default=0),
            "meaning": "Exact pinned ST5.2 predict batch tokenizer invocation, including padding/special tokens; CPU diagnostic, no model forward, no semantic completeness proof."}


def evaluate(args, config):
    import numpy as np
    import torch
    from qdrant_client import QdrantClient, models
    from rank_bm25 import BM25Okapi
    runtime_device(args.device)
    chunks = load_chunks(args)
    meta = read(args.out / "index_metadata.json")
    signature = meta["signature"]
    require_index_signature(signature, embedding_signature(args, config, chunks))
    for case in config["queries"]:
        for expected in case["expected"]:
            if not any(c["role"] == case["role"] and expected_match(c, expected) for c in chunks):
                raise RuntimeError(f'Expected source span absent from corpus: {case["id"]}: {expected}')
    model = embed_model(args, config)
    start = time.perf_counter()
    query_vectors = model.encode([q["query"] for q in config["queries"]], batch_size=args.batch_size,
                                 normalize_embeddings=True, convert_to_numpy=True)
    query_vector_identity = {"sha256": hashlib.sha256(query_vectors.tobytes(order="C")).hexdigest(),
                             "shape": list(query_vectors.shape), "dtype": str(query_vectors.dtype)}
    query_seconds, embedding_gpu = time.perf_counter() - start, gpu_stats()
    del model
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
    client = QdrantClient(path=str(args.out / "qdrant"))
    candidates, retrieval_times = [], []
    try:
        for case, vector in zip(config["queries"], query_vectors):
            started = time.perf_counter()
            scope = [c for c in chunks if c["role"] == case["role"]]
            bm25 = BM25Okapi([tokens(c["content"]) for c in scope])
            scores = bm25.get_scores(tokens(case["query"]))
            lexical = [scope[i] for i in np.argsort(-scores)[:args.candidates] if scores[i] > 0]
            result = client.query_points(meta["collection"], query=vector.tolist(), limit=args.candidates,
                query_filter=models.Filter(must=[models.FieldCondition(key="role", match=models.MatchValue(value=case["role"]))]))
            dense = [p.payload for p in result.points]
            fusion, lookup = {}, {}
            for ranked in (dense, lexical):
                for rank, chunk in enumerate(ranked, 1):
                    fusion[chunk["id"]] = fusion.get(chunk["id"], 0) + 1 / (60 + rank)
                    lookup[chunk["id"]] = chunk
            hybrid = [lookup[i] for i in sorted(fusion, key=fusion.get, reverse=True)[:args.candidates]]
            candidates.append((case, dense, hybrid))
            retrieval_times.append(time.perf_counter() - started)
    finally:
        client.close()
    ranker = rerank_model(args, config)
    reports, rerank_seconds, diagnostic_seconds = [], 0, 0
    for case, dense, hybrid in candidates:
        pairs = [(case["query"], c["content"]) for c in hybrid]
        diagnostic_started = time.perf_counter()
        pair_identity = ordered_pair_identity(pairs, [c["id"] for c in hybrid])
        token_observations = pair_token_observations(ranker, pairs, args.rerank_max_tokens, args.rerank_batch)
        diagnostic_elapsed = time.perf_counter() - diagnostic_started
        diagnostic_seconds += diagnostic_elapsed
        started = time.perf_counter()
        scores = ranker.predict(pairs, batch_size=args.rerank_batch,
                                show_progress_bar=False, convert_to_numpy=True)
        elapsed = time.perf_counter() - started
        rerank_seconds += elapsed
        ranked = [hybrid[i] for i in np.argsort(-np.asarray(scores).reshape(-1))]
        stages = {"dense": dense[:args.top_k], "hybrid": hybrid[:args.top_k], "reranked": ranked[:args.top_k]}
        checks = {name: [any(expected_match(c, exp) for c in found) for exp in case["expected"]]
                  for name, found in stages.items()}
        found_in_candidates = [any(expected_match(c, exp) for c in hybrid) for exp in case["expected"]]
        reports.append({"id": case["id"], "query": case["query"], "expected": case["expected"],
                        "checks": checks, "hybrid_candidate_checks": found_in_candidates, "rerank_seconds": elapsed,
                        "candidateIds": [c["id"] for c in hybrid], "pairTokenObservations": token_observations,
                        "orderedPairIdentity": pair_identity, "diagnostic_seconds": diagnostic_elapsed,
                        "rankedCandidateScores": [{"id": hybrid[i]["id"], "score": float(np.asarray(scores).reshape(-1)[i])}
                                                  for i in np.argsort(-np.asarray(scores).reshape(-1))],
                        "top_results": {name: [{k: c.get(k) for k in ("id", "key", "role", "path", "page", "clause", "locations", "text")} for c in found]
                                        for name, found in stages.items()}})
        print(json.dumps({"id": case["id"], "checks": checks, "seconds": round(elapsed, 3)}), flush=True)
    denominator = sum(len(c["expected"]) for c in config["queries"])
    summary = {name: {"evidence_recall_at_k": sum(sum(r["checks"][name]) for r in reports) / denominator,
                      "all_evidence_queries": sum(all(r["checks"][name]) for r in reports), "queries": len(reports)}
               for name in ("dense", "hybrid", "reranked")}
    candidate_recall = sum(sum(r["hybrid_candidate_checks"]) for r in reports) / denominator
    data = {"schemaVersion": 2, "summary": summary, "hybrid_candidate_recall": candidate_recall, "top_k": args.top_k,
            "candidates": args.candidates, "expected_spans": denominator,
            "timing": {"query_embedding_seconds": query_seconds, "retrieval_seconds": sum(retrieval_times),
                       "rerank_seconds": rerank_seconds, "pair_diagnostic_seconds": diagnostic_seconds},
            "gpu": {"embedding": embedding_gpu, "reranker": gpu_stats()},
            "models": {k: config[k] for k in ("embedding", "reranker")}, "runtime": model_runtime(args, config),
            "actualReranker": {"device": str(ranker.model.device),
                               "dtype": str(next(ranker.model.parameters()).dtype),
                               "tokenizerEffectiveMaxTokens": int(ranker.tokenizer.model_max_length)},
            "queryVectorIdentity": query_vector_identity,
            "index_signature": signature,
            "cases": reports, "limitations": ["Small hand-selected smoke dataset", "No conflict verdict or false-positive accuracy measured",
                "Selected OCR pages; partial scan coverage", "BM25 computed in Python; Qdrant local mode", "Max-token truncation may lose long clause context"]}
    save(args.out / "retrieval_report.json", data)
    del ranker
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    print(json.dumps({"summary": summary, "candidate_recall": candidate_recall, "timing": data["timing"], "gpu": data["gpu"]}, indent=2), flush=True)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=HERE / "samples.json")
    parser.add_argument("--source-root", type=Path)
    parser.add_argument("--out", type=Path, default=WORKSPACE / "tmp" / "vetting_eval")
    parser.add_argument("--log-root", type=Path, default=DEFAULT_LOG_ROOT)
    parser.add_argument("--baseline-id", help="Existing experiment ID for an explicit comparison")
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--dpi", type=int, default=200)
    parser.add_argument('--ocr-max-side-len', type=int, default=2000)
    parser.add_argument('--ocr-text-score', type=float, default=0.5)
    parser.add_argument('--ocr-box-thresh', type=float, default=0.5)
    parser.add_argument('--ocr-intra-threads', type=int, default=4)
    parser.add_argument('--ocr-inter-threads', type=int, default=1)
    parser.add_argument("--chunk-size", type=int, default=1400)
    parser.add_argument("--overlap", type=int, default=200)
    parser.add_argument("--batch-size", "--embed-batch-size", dest="batch_size", type=int, default=4)
    parser.add_argument("--rerank-batch", "--rerank-batch-size", dest="rerank_batch", type=int, default=4)
    parser.add_argument("--max-tokens", type=int, default=512,
                        help="Legacy fallback for either model without an explicit independent token limit")
    parser.add_argument("--embed-max-tokens", type=int)
    parser.add_argument("--rerank-max-tokens", type=int)
    parser.add_argument("--candidates", type=int, default=50)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("command", choices=("probe", "ocr", "parse", "index", "evaluate"))
    args = parser.parse_args(argv)
    args.embed_max_tokens = args.max_tokens if args.embed_max_tokens is None else args.embed_max_tokens
    args.rerank_max_tokens = args.max_tokens if args.rerank_max_tokens is None else args.rerank_max_tokens
    if min(args.batch_size, args.rerank_batch, args.max_tokens, args.embed_max_tokens,
           args.rerank_max_tokens, args.candidates, args.top_k, args.dpi) <= 0:
        parser.error("Batch, token, candidate, top-k and dpi values must be positive")
    if max(args.max_tokens, args.embed_max_tokens, args.rerank_max_tokens) > 8192:
        parser.error("Pinned embedding and reranker token limits must not exceed 8192")
    if args.top_k > args.candidates:
        parser.error("top-k cannot exceed candidates")
    from ocr_runtime import ocr_parameters
    try:
        ocr_parameters(**ocr_settings(args))
    except ValueError as error:
        parser.error(str(error))
    return args


REPORT_FILES = {"probe": ("environment.json",), "ocr": ("ocr_report.json",),
                "parse": ("parse_report.json", "chunks.jsonl"), "index": ("index_metadata.json",),
                "evaluate": ("retrieval_report.json",)}


def file_identity(path):
    path = Path(path).resolve()
    if not path.is_file():
        return {"path": str(path), "exists": False, "bytes": None, "sha256": None}
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return {"path": str(path), "exists": True, "bytes": path.stat().st_size, "sha256": hasher.hexdigest()}


def archive_file(path, target, copy_bytes=True):
    before = file_identity(path)
    if before["exists"] and copy_bytes:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        snapshot = file_identity(target)
        after = file_identity(path)
        if before["sha256"] != snapshot["sha256"] or before != after:
            raise RuntimeError("Input or report changed while preserving its bytes: " + str(path))
        return {"original": before, "snapshot": snapshot}
    return {"original": before, "snapshot": None}


def cache_paths(args, config):
    paths = [args.out/name for name in ("environment.json", "ocr_report.json", "parse_report.json",
             "chunks.jsonl", "index_metadata.json", "retrieval_report.json", "embeddings.npy")]
    for item in config.get("ocr_cases", []):
        base = args.out/"ocr"/(str(item["key"])+"_"+str(item["page"]))
        paths.extend((base.with_suffix(".json"), base.with_suffix(".png"), base.with_name(base.name+"_boxes.png")))
    return paths


def capture_inputs(args, run_directory):
    config_archive = archive_file(args.config, run_directory/"before"/"config.json")
    config = read(config_archive["snapshot"]["path"]) if config_archive["snapshot"] else {}
    inputs = {"config": config_archive, "reportsAndCaches": [], "originalSources": [],
              "toolSource": file_identity(__file__), "runtimeSources": []}
    for name in ("eval.py", "ocr_runtime.py", "requirements.txt", "experiment_log.py", "resource_sample.py"):
        inputs["runtimeSources"].append(archive_file(HERE/name, run_directory/"before/runtime"/name))
    if args.command in {"ocr", "parse"}:
        inputs['requestedOcrParameters'] = ocr_settings(args)
        # Weight files are hashed, never copied to every experiment run.
        model_root = args.out / 'ocr_models'
        inputs['localOcrWeights'] = [file_identity(path) for path in sorted(model_root.rglob('*'))
            if path.is_file() and path.suffix.lower() in {'.onnx', '.txt'}] if model_root.is_dir() else []
        try:
            distribution = importlib.metadata.distribution("rapidocr")
            for name in ("config.yaml", "default_models.yaml"):
                inputs["runtimeSources"].append(archive_file(distribution.locate_file("rapidocr/"+name),
                    run_directory/"before/runtime/rapidocr"/name))
        except importlib.metadata.PackageNotFoundError:
            inputs["ocrLibraryConfiguration"] = "unknown: rapidocr is not installed"
    for path in cache_paths(args, config):
        relative = path.relative_to(args.out)
        # Vector arrays are hashed, not duplicated; never copy Qdrant/HF directories.
        inputs["reportsAndCaches"].append(archive_file(path, run_directory/"before"/relative, path.suffix != ".npy"))
    if args.command in {"ocr", "parse"}:
        for doc in config.get("documents", []):
            try:
                info = file_identity(source_path(args, config, doc))
                inputs["originalSources"].append({"key": doc.get("key"), "role": doc.get("role"), **info})
            except Exception as error:
                inputs["originalSources"].append({"key": doc.get("key"), "path": doc.get("path"),
                    "exists": False, "error": f"{type(error).__name__}: {error}"})
    return config, inputs


def capture_outputs(args, config, run_directory):
    paths = [args.out/name for name in REPORT_FILES[args.command]]
    if args.command == "ocr":
        paths += [p for p in cache_paths(args, config) if p.parent == args.out/"ocr"]
    artifacts, reports, errors = [], {}, []
    for path in paths:
        target = run_directory/"after"/path.relative_to(args.out)
        try:
            observation = archive_file(path, target)
            artifacts.append(observation)
            if observation["snapshot"] and path.name in REPORT_FILES[args.command] and path.suffix == ".json":
                reports[path.name] = read(observation["snapshot"]["path"])
        except Exception as error:
            errors.append(f"{path}: {type(error).__name__}: {error}")
            artifacts.append({"original": file_identity(path), "snapshot": file_identity(target),
                              "captureError": errors[-1]})
    if args.command == "index":
        artifacts.append(archive_file(args.out/"embeddings.npy", run_directory/"after"/"embeddings.npy", False))
    return artifacts, reports, errors


class OutputTee:
    def __init__(self, original, saved):
        self.original, self.saved = original, saved

    def write(self, text):
        self.saved.write(text)
        return self.original.write(text)

    def flush(self):
        self.saved.flush()
        self.original.flush()

    def __getattr__(self, name):
        return getattr(self.original, name)


def rejected_cli_invocation(argv, exit_code, error_text):
    """Record a zero-call argparse rejection without trusting invalid output paths."""
    created = datetime.now(timezone.utc)
    identifier = 'pilot-preflight-'+created.strftime('%Y%m%dT%H%M%S%fZ')+'-'+uuid.uuid4().hex[:8]

    def requested_option(name):
        value = None
        for index, item in enumerate(argv):
            if item.startswith(name + '='):
                value = item.split('=', 1)[1]
            elif item == name and index + 1 < len(argv) and not argv[index + 1].startswith('--'):
                value = argv[index + 1]
        return value

    def within_workspace(value):
        try:
            path = Path(value).resolve()
            return path if path.is_relative_to((WORKSPACE/'tmp').resolve()) and not path.is_file() else None
        except (ValueError, OSError):
            return None

    requested_root = requested_option('--log-root')
    accepted_root = within_workspace(requested_root) if requested_root else None
    root = accepted_root or DEFAULT_LOG_ROOT
    # Rejected raw --out/config values never determine a write/read destination.
    directory = root/'preflight_artifacts'/identifier
    directory.mkdir(parents=True, exist_ok=False)
    raw = {'argv': list(argv), 'argvSha256': json_fingerprint(list(argv)),
           'exitCode': exit_code, 'stderr': error_text,
           'requestedLogRoot': requested_root, 'acceptedRequestedLogRoot': str(accepted_root) if accepted_root else None,
           'actualLogRoot': str(root), 'outputPathPolicy': 'Do not create or read raw --out/config paths; rejected custom log-root falls back to trusted default',
           'createdAtUtc': created.isoformat()}
    save(directory/'rejection.json', raw)
    (directory/'stderr.txt').write_text(error_text, encoding='utf-8')
    sources = [file_identity(HERE/name) for name in ('eval.py', 'ocr_runtime.py', 'experiment_log.py')]
    artifacts = [file_identity(directory/name) for name in ('rejection.json', 'stderr.txt')]
    record = {'schemaVersion': 1, 'experimentId': identifier, 'status': 'started',
              'scope': 'driver_preflight: CLI parameter rejection; zero OCR/model/index/evaluate calls; not a model experiment',
              'parameters': {'rawArgv': list(argv), 'effectiveParameters': None},
              'inputFingerprints': {'rawArgvSha256': raw['argvSha256'], 'toolSource': copy.deepcopy(sources)},
              'measurements': {'exitCode': None, 'actualModelCalls': 0, 'actualOcrCalls': 0,
                               'actualIndexesCreated': 0, 'wholeMachineSampledResources': None},
              'evaluation': {'technicalStatus': 'parameter_rejected', 'qualityAccepted': None,
                             'semanticAcceptance': 'not_evaluated'}, 'artifacts': copy.deepcopy(artifacts)}
    append_record(root, record, 'started')
    record['status'] = 'failed'
    record['measurements'].update(exitCode=exit_code, error=error_text,
                                   acceptedRequestedLogRoot=raw['acceptedRequestedLogRoot'])
    append_record(root, record, 'failed')


def main(argv=None):
    raw_argv = list(sys.argv[1:] if argv is None else argv)
    errors = io.StringIO()
    try:
        with contextlib.redirect_stderr(errors):
            args = parse_args(raw_argv)
    except SystemExit as error:
        message = errors.getvalue()
        sys.stderr.write(message)
        if error.code:
            try:
                rejected_cli_invocation(raw_argv, error.code, message)
            except Exception as log_error:
                sys.stderr.write(f'Unable to preserve CLI rejection log: {type(log_error).__name__}: {log_error}\n')
        raise
    started = datetime.now(timezone.utc)
    experiment_id = "pilot-"+args.command+"-"+started.strftime("%Y%m%dT%H%M%S%fZ")+"-"+uuid.uuid4().hex[:8]
    run_directory = args.out/"experiment_runs"/experiment_id
    run_directory.mkdir(parents=True, exist_ok=False)
    config, inputs, preparation_error = {}, {}, None
    try:
        config, inputs = capture_inputs(args, run_directory)
        if not inputs["config"]["original"]["exists"]:
            raise FileNotFoundError(args.config)
    except Exception as error:
        preparation_error = error
        # Even malformed/missing config gets a started + failed event and its bytes.
        inputs.setdefault("config", {"original": file_identity(args.config),
                          "snapshot": file_identity(run_directory/"before"/"config.json")})
        inputs["preparationError"] = f"{type(error).__name__}: {error}"
    arguments = {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()}
    packages = {}
    for name in ("torch", "sentence-transformers", "transformers", "rapidocr", "onnxruntime", "qdrant-client", "rank-bm25", "pypdf", "pypdfium2", "python-docx"):
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = None
    record = {"schemaVersion": 1, "experimentId": experiment_id, "status": "started",
        "scope": "pilot_"+args.command+": staged Python validation, not Java full44 review or contractual semantic acceptance.",
        "baselineId": args.baseline_id, "changedFactors": [],
        "parameters": {"commandArguments": arguments, "pinnedModels": {k: config.get(k) for k in ("embedding", "reranker")},
                       "installedLibraryVersions": packages,
                       "retrievalConstants": {"rrf": 60, "bm25": "installed BM25Okapi defaults"}},
        "inputFingerprints": copy.deepcopy(inputs),
        "measurements": {"startedAtUtc": started.isoformat(), "originalArgv": list(sys.argv[1:] if argv is None else argv),
                         "actualCalls": 0 if args.command == "probe" else None, "wholeMachineSampledResources": None},
        "evaluation": {"qualityAccepted": None, "semanticAcceptance": "not_evaluated",
                       "limits": ["Execution/anchor or retrieval-span metrics are not contractual accuracy.",
                                  "Reports may reuse cache; post-run observation does not prove new inference."]},
        "artifacts": []}
    save(run_directory/"started_record.json", record)
    append_record(args.log_root, record, "started")
    sampler, resources, caught, sampling_errors = None, None, None, []
    wall_start = time.perf_counter()
    stdout_path, stderr_path = run_directory/"stdout.txt", run_directory/"stderr.txt"
    with stdout_path.open("w", encoding="utf-8", newline="") as stdout_file, stderr_path.open("w", encoding="utf-8", newline="") as stderr_file:
        with contextlib.redirect_stdout(OutputTee(sys.stdout, stdout_file)), contextlib.redirect_stderr(OutputTee(sys.stderr, stderr_file)):
            try:
                if preparation_error is not None:
                    raise preparation_error
                if args.command != "probe":
                    try:
                        from resource_sample import ResourceSampler
                        sampler = ResourceSampler(run_directory, "Pilot CLI stage "+args.command+". Whole-machine sampled resources include other activity; not isolated model memory or absolute peaks.")
                        sampler.set_phase(args.command)
                        sampler.start()
                    except Exception as error:
                        sampling_errors.append(f"start: {type(error).__name__}: {error}")
                globals()[args.command](args, config)
            except BaseException as error:
                caught = error
                traceback.print_exc(file=stderr_file)
            finally:
                if sampler is not None:
                    try:
                        resources = sampler.stop()
                    except Exception as error:
                        sampling_errors.append(f"stop: {type(error).__name__}: {error}")
    record["status"] = "failed" if caught is not None else "completed"
    record["measurements"].update(finishedAtUtc=datetime.now(timezone.utc).isoformat(),
        wallSeconds=time.perf_counter()-wall_start, wholeMachineSampledResources=resources,
        resourceSamplingErrors=sampling_errors,
        resourceObservation="unknown: probe did not sample" if args.command == "probe" else "observational sampling; missing/error values remain unknown")
    if caught is not None:
        record["measurements"]["error"] = f"{type(caught).__name__}: {caught}"
    try:
        artifacts, reports, output_errors = capture_outputs(args, config, run_directory)
        record["artifacts"].extend(artifacts)
        record["measurements"]["postRunReports"] = reports
        if output_errors:
            raise RuntimeError("Output capture failed: " + "; ".join(output_errors))
    except Exception as error:
        record["measurements"]["outputCaptureError"] = f"{type(error).__name__}: {error}"
        if caught is None:
            caught = error
            record["status"] = "failed"
            record["measurements"]["error"] = record["measurements"]["outputCaptureError"]
    record["artifacts"].extend(file_identity(p) for p in (stdout_path, stderr_path))
    for name in ("resource_summary.json", "resource_samples.jsonl"):
        if (run_directory/name).exists(): record["artifacts"].append(file_identity(run_directory/name))
    save(run_directory/"finished_record.json", record)
    append_record(args.log_root, record, "failed" if record["status"] == "failed" else "finished")
    if caught is not None:
        raise caught
    return record


if __name__ == "__main__":
    main()
