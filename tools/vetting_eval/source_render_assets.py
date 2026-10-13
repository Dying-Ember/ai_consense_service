"""Isolated source-render overlay: page evidence is independent of model input.

This prototype does not register an index or alter canonical v1 assets. The
caller owns the identity-bound source-review decision; it is not authenticated
as a human review. Actual CAS bytes are checked for every admitted handle.
"""
from __future__ import annotations
from io import BytesIO
from pathlib import Path
import copy, hashlib, json, math

SCHEMA = 'consense.source-render-assets.v1'
ASSET_SCHEMA = 'consense.source-render-asset.v1'

class SourceRenderError(ValueError):
    pass

def require(ok, reason):
    if not ok: raise SourceRenderError(reason)

def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
        separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()

def descriptor_identity(value):
    return value.get('sha256'), value.get('byteCount')

def read_json(store, descriptor):
    try: return json.loads(store.read_asset(descriptor))
    except (OSError, ValueError, TypeError) as error:
        raise SourceRenderError('json_proof_or_projection_bytes_unavailable') from error

def actual_png(store, descriptor):
    from PIL import Image
    try:
        raw = store.read_asset(descriptor)
        with Image.open(BytesIO(raw)) as image:
            require(image.format == 'PNG', 'actual_image_mime_not_png')
            rgb = image.convert('RGB'); rgb.load()
            return raw, rgb.copy()
    except (OSError, ValueError, TypeError) as error:
        if isinstance(error, SourceRenderError): raise
        raise SourceRenderError('actual_image_bytes_unavailable') from error

def validate_source_render_overlay(envelope, projection, store):
    from canonical_projection import validate_projection
    validate_projection(projection)
    allowed = {'schema','projectionDescriptor','projectionSha256','sourceSha256','sourceRevisionSha256',
        'scopePolicySha256','sourceArtifactDescriptor','viewportProofDescriptor','reviewBindingDescriptor',
        'assets','overlayCodeSha256','overlaySha256','combinedEvidenceIdentitySha256','limitations'}
    require(isinstance(envelope, dict) and set(envelope) == allowed and envelope.get('schema') == SCHEMA,
            'unsupported_or_extra_overlay_fields')
    require(envelope['overlaySha256'] == digest({k:v for k,v in envelope.items() if k != 'overlaySha256'}), 'overlay_seal_mismatch')
    require(envelope['overlayCodeSha256'] == hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'overlay_reader_identity_mismatch')
    require(envelope['projectionSha256'] == projection['projectionSha256'], 'stale_canonical_projection')
    source = projection['source']
    for key in ('sourceSha256','sourceRevisionSha256'):
        require(envelope[key] == source[key], 'source_or_revision_mismatch')
    require(envelope['scopePolicySha256'] == projection['manifest']['scope']['scopePolicySha256'], 'scope_policy_mismatch')
    require(envelope['sourceArtifactDescriptor']['sha256'] == source['sourceSha256'], 'parent_source_bytes_identity_mismatch')
    expected_identity = digest({'canonicalProjectionSha256': projection['projectionSha256'],
        'sourceSha256': source['sourceSha256'], 'sourceRevisionSha256':source['sourceRevisionSha256'],
        'scopePolicySha256':envelope['scopePolicySha256'], 'viewportProofSha256':envelope['viewportProofDescriptor']['sha256'],
        'reviewBindingSha256':envelope['reviewBindingDescriptor']['sha256'], 'assetIdentities':[a.get('assetId') for a in envelope['assets']],
        'overlayCodeSha256':envelope['overlayCodeSha256']})
    require(envelope['combinedEvidenceIdentitySha256'] == expected_identity, 'overlay_index_identity_mismatch')
    units = {u['unitId']:u for u in projection['canonical']['units']}
    require(len(units) == 1 and all(u['scope'] == 'table_group' for u in units.values()), 'prototype_requires_one_complete_table_group')
    require(isinstance(envelope['assets'], list) and envelope['assets'], 'source_assets_missing')
    asset_keys = {'schema','assetId','kind','unitId','sourceSha256','sourceRevisionSha256','physicalPageNo',
        'blockIds','mediaType','imageDescriptor','widthPixels','heightPixels','rgbSha256','sourceViewport',
        'imageToOriginalTransform','imageRegions','viewportProofSha256','reviewBindingSha256','contentMeaning'}
    expected_pages = {p for u in units.values() for p in u['physicalPages']}
    require({a.get('physicalPageNo') for a in envelope['assets']} == expected_pages, 'partial_whole_group_source_assets')
    require(len(envelope['assets']) == len(expected_pages), 'duplicate_or_region_asset_not_supported')
    for asset in envelope['assets']:
        require(isinstance(asset, dict) and set(asset) == asset_keys and asset['schema'] == ASSET_SCHEMA, 'illegal_source_asset_fields')
        require(asset['kind'] == 'source_page_render', 'region_render_not_admitted_without_crop_pixel_proof')
        require(asset['contentMeaning'] == 'actual_full_original_page_render; independent_of_actual_model_input', 'source_render_cannot_relabel_model_input')
        require(asset['mediaType'] == 'image/png', 'unsupported_source_asset_media')
        require(asset['unitId'] in units, 'asset_unit_unadopted')
        unit = units[asset['unitId']]
        parts = [p for p in unit['parts'] if p['physicalPageNo'] == asset['physicalPageNo']]
        require(parts and asset['blockIds'] == [p['blockId'] for p in parts], 'source_asset_membership_incomplete_or_wrong')
        require(asset['sourceSha256'] == source['sourceSha256'] and asset['sourceRevisionSha256'] == source['sourceRevisionSha256'], 'asset_source_mismatch')
        require(asset['viewportProofSha256'] == envelope['viewportProofDescriptor']['sha256'] and
                asset['reviewBindingSha256'] == envelope['reviewBindingDescriptor']['sha256'], 'asset_proof_or_review_binding_mismatch')
        require(asset['assetId'] == 'source-render:' + digest({k:v for k,v in asset.items() if k != 'assetId'}), 'source_asset_identity_mismatch')
    require(read_json(store,envelope['projectionDescriptor']) == projection, 'actual_projection_bytes_differ')
    canonical_reference=projection['manifest']['reference']
    require(canonical_reference.get('actor')=='codex' and canonical_reference.get('method')=='original_source_review' and
            canonical_reference.get('sourceSha256')==source['sourceSha256'], 'canonical_source_review_reference_missing')
    canonical_binding=read_json(store,canonical_reference.get('artifactDescriptor'))
    require(canonical_binding.get('schema')=='consense.exact-source-reviewed-canonical-binding.v1' and
            canonical_binding.get('sourceSha256')==source['sourceSha256'], 'canonical_source_review_binding_missing')
    bound_geometry=canonical_binding.get('geometryProof',{})
    require(descriptor_identity(envelope['viewportProofDescriptor'])==(bound_geometry.get('sha256'),bound_geometry.get('bytes')),
            'canonical_source_review_geometry_proof_mismatch')
    reviewed_blocks=[p['blockId'] for u in projection['canonical']['units'] for p in u['parts']]
    reviewed_cells=[c['cellId'] for u in projection['canonical']['units'] for p in u['parts'] for c in p.get('cells',[])]
    require(canonical_binding.get('reviewedNewTableBlockIds')==reviewed_blocks and
            canonical_binding.get('reviewedNewCellIds')==reviewed_cells, 'canonical_source_review_membership_mismatch')
    for descriptor in canonical_binding.get('actualSourceReviewReceipts',[]):store.read_asset(descriptor)
    store.read_asset(envelope['sourceArtifactDescriptor'])
    proof = read_json(store,envelope['viewportProofDescriptor'])
    review = read_json(store,envelope['reviewBindingDescriptor'])
    require(proof.get('schema') == 'consense.actual-selected-parent-viewport-proof.v1' and
            proof.get('sourceSha256') == source['sourceSha256'], 'source_viewport_proof_mismatch')
    require(proof.get('modelsOrGpuUsed') is False and proof.get('oldCaptureTransformUpdated') is False, 'unexpected_proof_execution_meaning')
    require(review.get('schema') == 'consense.source-render-overlay-review-binding.v1' and
            review.get('actor') == 'codex' and review.get('method') == 'identity_bound_original_source_review' and
            review.get('sourceSha256') == source['sourceSha256'] and
            review.get('sourceRevisionSha256') == source['sourceRevisionSha256'] and
            review.get('projectionSha256') == projection['projectionSha256'] and
            review.get('viewportProofSha256') == envelope['viewportProofDescriptor']['sha256'] and
            review.get('physicalPages') == sorted(expected_pages), 'explicit_source_render_review_binding_missing')
    require(isinstance(review.get('sourceReviewReceipts'), list) and review['sourceReviewReceipts'], 'source_review_receipt_bytes_missing')
    for descriptor in review['sourceReviewReceipts']: store.read_asset(descriptor)
    actual_review_identities={descriptor_identity(d) for d in review['sourceReviewReceipts']}
    for field in ('sourceReview','independentAliasReview','newCaptureColdReplay'):
        frozen=canonical_binding.get(field,{})
        require((frozen.get('sha256'),frozen.get('bytes')) in actual_review_identities, 'canonical_frozen_review_receipt_missing:'+field)
    require(proof.get('selectedSourceValidation',{}).get('status') == 'verified' and
            proof['selectedSourceValidation'].get('parentSourceSha256') == source['sourceSha256'], 'source_object_clone_proof_missing')
    frames = {r['originalPhysicalPageNo']:r for r in proof.get('frames',[])}
    result=[]
    for asset in envelope['assets']:
        row = frames.get(asset['physicalPageNo']); require(row is not None, 'source_asset_page_not_proven')
        require(row.get('parentSourceSha256') == source['sourceSha256'] and row.get('actualModelPngParentProviderPixelsEqual') is True, 'source_pixels_not_proven')
        object_frame = row['parentPdfObjectFrame']; runtime=row['parentRuntimePointFrame']; recipe=row['renderRecipe']
        require(object_frame == row['providerPdfObjectFrame'] and runtime == row['providerRuntimePointFrame'], 'source_provider_viewport_frame_mismatch')
        require(object_frame['mediaBox'] == object_frame['cropBox'] and object_frame['mediaBox'][:2] == [0.0,0.0] and
                object_frame['rotation'] == 0 and object_frame['userUnit'] == 1, 'unsupported_source_object_viewport')
        require(runtime['rotation'] == 0 and runtime['bbox'][:2] == [0.0,0.0], 'unsupported_executed_viewport')
        require(recipe['scale'] == 300/72 and recipe['dpi'] == 300 and recipe['pageExtent'] == 'complete_source_viewport', 'unproven_render_recipe')
        require(all(math.isclose(a,b,rel_tol=0,abs_tol=1e-4) for a,b in zip(runtime['sizePt'],object_frame['mediaBox'][2:])), 'source_object_runtime_extent_mismatch')
        viewport={'pdfObjectFrame':copy.deepcopy(object_frame),'runtimePointFrame':copy.deepcopy(runtime),
                  'renderRecipe':copy.deepcopy(recipe),'validContinuousInputDomain':copy.deepcopy(row['paddleTransform']['validContinuousInputDomain']),
                  'integerBitmapPaddingMeaning':row['paddleTransform']['integerBitmapPaddingMeaning']}
        require(asset['sourceViewport'] == viewport, 'source_viewport_metadata_changed')
        expected_transform=copy.deepcopy(row['paddleTransform']['transform'])
        expected_transform['inputFrame']['artifactName']='source-render.png'
        expected_transform['inputFrame']['sha256']=asset['imageDescriptor']['sha256']
        require(asset['imageToOriginalTransform'] == expected_transform, 'source_render_affine_or_frame_changed')
        require(descriptor_identity(asset['imageDescriptor']) == (row['sourcePng']['sha256'],row['sourcePng']['bytes']), 'source_render_bytes_not_bound_to_proof')
        raw,image=actual_png(store,asset['imageDescriptor'])
        require([asset['widthPixels'],asset['heightPixels']] == list(image.size) == row['actualModelPngSize'], 'source_render_dimensions_changed')
        require(asset['rgbSha256'] == hashlib.sha256(image.tobytes()).hexdigest() == row['actualModelPngRgbSha256'], 'source_render_pixels_changed')
        part_map={p['blockId']:p for p in units[asset['unitId']]['parts']}
        expected_regions=[]; scale=recipe['scale']; wpt,hpt=runtime['sizePt']; iw,ih=image.size
        for block_id in asset['blockIds']:
            part=part_map[block_id]; normalized=part['bbox']; require(part['bboxConvention']=='normalized_xyxy_original_page','source_region_geometry_unknown')
            pixels=[normalized[0]*wpt*scale,normalized[1]*hpt*scale,normalized[2]*wpt*scale,normalized[3]*hpt*scale]
            require(0 <= pixels[0] <= pixels[2] <= wpt*scale and 0 <= pixels[1] <= pixels[3] <= hpt*scale, 'source_region_enters_padding')
            expected_regions.append({'blockId':block_id,'bboxOriginalNormalized':copy.deepcopy(normalized),
                'bboxConvention':'normalized_xyxy_original_page','bboxImageNormalized':[pixels[0]/iw,pixels[1]/ih,pixels[2]/iw,pixels[3]/ih],
                'bboxImagePixels':pixels,'imageCoordinatesMeaning':'actual_integer_bitmap_frame; no target-width stretch'})
        require(asset['imageRegions']==expected_regions,'source_region_or_padding_geometry_changed')
        result.append({'assetId':asset['assetId'],'kind':asset['kind'],'mediaType':asset['mediaType'],
            'sourceSha256':source['sourceSha256'],'sourceRevisionSha256':source['sourceRevisionSha256'],
            'physicalPageNo':asset['physicalPageNo'],'unitId':asset['unitId'],'blockIds':copy.deepcopy(asset['blockIds']),
            'byteCount':len(raw),'artifactDescriptor':copy.deepcopy(asset['imageDescriptor']),
            'sha256':asset['imageDescriptor']['sha256'],'widthPixels':iw,'heightPixels':ih,
            'imageRegions':copy.deepcopy(expected_regions),'imageToOriginalTransform':copy.deepcopy(expected_transform),
            'combinedEvidenceIdentitySha256':envelope['combinedEvidenceIdentitySha256'],'qualityAccepted':False})
    return result

def seal_overlay(envelope):
    envelope=copy.deepcopy(envelope)
    envelope['combinedEvidenceIdentitySha256']=digest({'canonicalProjectionSha256':envelope['projectionSha256'],
        'sourceSha256':envelope['sourceSha256'],'sourceRevisionSha256':envelope['sourceRevisionSha256'],
        'scopePolicySha256':envelope['scopePolicySha256'],'viewportProofSha256':envelope['viewportProofDescriptor']['sha256'],
        'reviewBindingSha256':envelope['reviewBindingDescriptor']['sha256'],
        'assetIdentities':[a['assetId'] for a in envelope['assets']],'overlayCodeSha256':envelope['overlayCodeSha256']})
    envelope['overlaySha256']=digest({k:v for k,v in envelope.items() if k != 'overlaySha256'})
    return envelope

def build_source_page_render_overlay(projection, store, source_descriptor, proof_descriptor, review_descriptor,
                                     projection_descriptor, page_images, code_sha256):
    proof=read_json(store,proof_descriptor)
    frames={r['originalPhysicalPageNo']:r for r in proof['frames']}
    assets=[]
    for unit in projection['canonical']['units']:
        for physical in unit['physicalPages']:
            row=frames[physical]; image_descriptor=page_images[physical]
            raw,image=actual_png(store,image_descriptor); wpt,hpt=row['parentRuntimePointFrame']['sizePt']; scale=row['renderRecipe']['scale']; iw,ih=image.size
            transform=copy.deepcopy(row['paddleTransform']['transform'])
            transform['inputFrame']['artifactName']='source-render.png'; transform['inputFrame']['sha256']=image_descriptor['sha256']
            parts=[p for p in unit['parts'] if p['physicalPageNo']==physical]
            regions=[]
            for part in parts:
                b=part['bbox']; pixels=[b[0]*wpt*scale,b[1]*hpt*scale,b[2]*wpt*scale,b[3]*hpt*scale]
                regions.append({'blockId':part['blockId'],'bboxOriginalNormalized':copy.deepcopy(b),
                    'bboxConvention':'normalized_xyxy_original_page','bboxImageNormalized':[pixels[0]/iw,pixels[1]/ih,pixels[2]/iw,pixels[3]/ih],
                    'bboxImagePixels':pixels,'imageCoordinatesMeaning':'actual_integer_bitmap_frame; no target-width stretch'})
            asset={'schema':ASSET_SCHEMA,'kind':'source_page_render','unitId':unit['unitId'],
                'sourceSha256':projection['source']['sourceSha256'],'sourceRevisionSha256':projection['source']['sourceRevisionSha256'],
                'physicalPageNo':physical,'blockIds':[p['blockId'] for p in parts],'mediaType':'image/png',
                'imageDescriptor':copy.deepcopy(image_descriptor),'widthPixels':iw,'heightPixels':ih,
                'rgbSha256':hashlib.sha256(image.tobytes()).hexdigest(),
                'sourceViewport':{'pdfObjectFrame':copy.deepcopy(row['parentPdfObjectFrame']),
                    'runtimePointFrame':copy.deepcopy(row['parentRuntimePointFrame']),'renderRecipe':copy.deepcopy(row['renderRecipe']),
                    'validContinuousInputDomain':copy.deepcopy(row['paddleTransform']['validContinuousInputDomain']),
                    'integerBitmapPaddingMeaning':row['paddleTransform']['integerBitmapPaddingMeaning']},
                'imageToOriginalTransform':transform,'imageRegions':regions,'viewportProofSha256':proof_descriptor['sha256'],
                'reviewBindingSha256':review_descriptor['sha256'],
                'contentMeaning':'actual_full_original_page_render; independent_of_actual_model_input'}
            asset['assetId']='source-render:'+digest(asset); assets.append(asset)
    envelope={'schema':SCHEMA,'projectionDescriptor':projection_descriptor,'projectionSha256':projection['projectionSha256'],
        'sourceSha256':projection['source']['sourceSha256'],'sourceRevisionSha256':projection['source']['sourceRevisionSha256'],
        'scopePolicySha256':projection['manifest']['scope']['scopePolicySha256'],'sourceArtifactDescriptor':source_descriptor,
        'viewportProofDescriptor':proof_descriptor,'reviewBindingDescriptor':review_descriptor,'assets':assets,
        'overlayCodeSha256':code_sha256,'limitations':['No model input relabeling','Not whole-source quality acceptance',
            'source_region_render remains rejected until actual crop pixel proof is implemented']}
    envelope=seal_overlay(envelope); validate_source_render_overlay(envelope,projection,store)
    return envelope
