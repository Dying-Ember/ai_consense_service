"""Real projection and geometry with explicit fake OCR; no weights or inference."""
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from PIL import Image, ImageDraw
import numpy as np
from ocr_response import OcrProjectionError, result_lines, recognize_vetting_page


class OcrResponseTest(unittest.TestCase):
    def image(self):
        return Image.new('RGB', (300, 180), 'white')

    def result(self):
        return SimpleNamespace(txts=['literal *not | 🧭'], scores=[.49],
            boxes=np.array([[[20,20],[120,20],[120,40],[20,40]]], dtype=np.float32))

    def test_cardinality_mismatch_cannot_silently_drop_text_tail(self):
        raw=self.result();raw.txts.append('necessary final condition')
        with self.assertRaises(OcrProjectionError):result_lines(raw,self.image())

    def test_missing_text_with_nonempty_geometry_is_not_verified_empty(self):
        raw=self.result();raw.txts=None
        with self.assertRaises(OcrProjectionError):result_lines(raw,self.image())

    def test_preserves_literal_text_low_score_and_default_response_shape(self):
        self.assertEqual(result_lines(self.result(),self.image()),
            [{'text':'literal *not | 🧭','confidence':.49,'bbox':[20.,20.,100.,20.]}])

    def test_rejects_nonfinite_geometry(self):
        raw=self.result();raw.boxes[0,0,0]=float('nan')
        with self.assertRaises(OcrProjectionError):result_lines(raw,self.image())

    def test_unknown_observer_leaves_original_text_and_instance_unchanged(self):
        raw=self.result()
        class UnsupportedEngine:
            def __call__(self,image):return raw
        engine=UnsupportedEngine();before=dict(engine.__dict__)
        payload=recognize_vetting_page(engine,self.image())
        self.assertEqual(payload['text'],raw.txts[0]);self.assertEqual(engine.__dict__,before)
        self.assertEqual(payload['lineStageDiagnostics']['status'],'unsupported_runtime')
        self.assertEqual(payload['qualityStatus'],'needs_review');self.assertFalse(payload['humanConfirmed'])

    def test_optional_table_failure_does_not_erase_completed_ocr(self):
        raw=self.result()
        with patch('ocr_table_geometry.recover_ruled_tables',side_effect=ValueError('test unavailable')):
            payload=recognize_vetting_page(lambda image:raw,self.image())
        self.assertEqual(payload['text'],raw.txts[0]);self.assertEqual(payload['rasterTableCandidates']['status'],'failed')

    def test_actual_grid_candidates_reference_only_returned_ocr_lines(self):
        image=self.image();draw=ImageDraw.Draw(image)
        for x in (10,140,280):draw.line((x,10,x,160),fill='black',width=2)
        for y in (10,60,110,160):draw.line((10,y,280,y),fill='black',width=2)
        payload=recognize_vetting_page(lambda image:self.result(),image)
        tables=payload['rasterTableCandidates']['result']['tables']
        self.assertTrue(tables)
        cells=[c for table in tables for c in table['cells']]
        assigned=[c for c in cells if c['sourceLineIds']]
        self.assertEqual(len(assigned),1);self.assertEqual(assigned[0]['sourceLineIds'],['ocr-line-0'])
        self.assertEqual(assigned[0]['text'],self.result().txts[0]);self.assertFalse(assigned[0]['nativeCell'])
        self.assertTrue(any(c['textStatus']=='no_assigned_ocr_text_unknown' for c in cells))

    def test_real_multipart_routes_keep_shared_response_and_vetting_diagnostics_separate(self):
        import io
        import vetting_ocr_api as api
        from fastapi.testclient import TestClient
        server=api.baseline
        buf=io.BytesIO();self.image().save(buf,format='PNG')
        raw=self.result()
        with patch.object(server,'_ocr',lambda image:raw), patch.object(server,'create_ocr_engine',side_effect=AssertionError('Model initialization forbidden')):
            client=TestClient(server.app)
            baseline=client.post('/ocr',files={'file':('page.png',buf.getvalue(),'image/png')})
            vetting=client.post('/vetting/ocr',files={'file':('page.png',buf.getvalue(),'image/png')})
            again=client.post('/ocr',files={'file':('page.png',buf.getvalue(),'image/png')})
        self.assertEqual(baseline.status_code,200);self.assertEqual(vetting.status_code,200);self.assertEqual(again.status_code,200)
        self.assertNotIn('lineStageDiagnostics',baseline.json());self.assertNotIn('lineStageDiagnostics',again.json())
        self.assertEqual(baseline.json()['lines'],again.json()['lines'])
        self.assertEqual(vetting.json()['text'],baseline.json()['text'])
        self.assertEqual(vetting.json()['lineStageDiagnostics']['status'],'unsupported_runtime')

    def test_real_route_reports_malformed_output_as_failure_without_a_shortened_body(self):
        import io
        import vetting_ocr_api as api
        from fastapi.testclient import TestClient
        server=api.baseline
        buf=io.BytesIO();self.image().save(buf,format='PNG')
        raw=self.result();raw.txts.append('necessary final condition')
        with patch.object(server,'_ocr',lambda image:raw):
            response=TestClient(server.app).post('/vetting/ocr',files={'file':('page.png',buf.getvalue(),'image/png')})
        self.assertEqual(response.status_code,503);self.assertNotIn('lines',response.json())

if __name__=='__main__':unittest.main()
