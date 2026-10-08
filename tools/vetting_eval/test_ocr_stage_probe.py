"""Exercise real installed output filtering code without loading OCR weights.

Detection/classification/recognition are explicit small synthetic fixtures.
Installed RapidOCR build_final_output and filter_by_text_score execute unchanged.
"""
import ast
import importlib.metadata
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock

import cv2
import numpy as np
from ocr_stage_probe import observe_ocr_stages, plain
import ocr_stage_probe

LIB = Path(importlib.metadata.distribution("rapidocr").locate_file("rapidocr"))


class Output:
    def __init__(self, **values):
        self.txts = self.scores = self.boxes = None
        self.__dict__.update(values)

    def __len__(self):
        return len(self.txts) if self.txts is not None else 0


def load_functions(path, names, namespace, in_class=False):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    nodes = next(x.body for x in tree.body if isinstance(x, ast.ClassDef) and x.name == "RapidOCR") if in_class else tree.body
    functions = [x for x in nodes if isinstance(x, ast.FunctionDef) and x.name in names]
    assert {x.name for x in functions} == set(names)
    mod = ast.Module(body=[ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0), *functions], type_ignores=[])
    exec(compile(ast.fix_missing_locations(mod), str(path), "exec"), namespace)


NAMESPACE = {"np": np, "cv2": cv2, "RapidOCROutput": Output, "VisRes": lambda **kwargs: None}
load_functions(LIB / "utils/process_img.py", ["map_boxes_to_original", "map_img_to_original"], NAMESPACE)
load_functions(LIB / "utils/utils.py", ["filter_by_indices"], NAMESPACE)
load_functions(LIB / "main.py", ["build_final_output", "filter_by_text_score"], NAMESPACE, True)


class FixtureEngine:
    build_final_output = NAMESPACE["build_final_output"]
    filter_by_text_score = NAMESPACE["filter_by_text_score"]

    def __init__(self, texts=("fixture", " ", "low", "edge", "good"), scores=(.9, .8, .499999, .5, .99), duplicate=False):
        self.texts, self.scores, self.duplicate = list(texts), list(scores), duplicate
        self.text_score = .5
        self.return_word_box = False
        self.cfg = SimpleNamespace(Global=SimpleNamespace(text_score=.5, font_path=None), Rec=SimpleNamespace(lang_type="ch", font_path=None))
        self.text_cls = SimpleNamespace(cls_thresh=.9)
        self.text_det = SimpleNamespace(postprocess_op=self.postprocess)

    def preprocess_img(self, image):
        return image.copy(), {"preprocess": {"ratio_h": 1.0, "ratio_w": 1.0}}

    def postprocess(self, *args):
        boxes = np.array([[[2, 5 + i * 12], [20, 5 + i * 12], [20, 15 + i * 12], [2, 15 + i * 12]] for i in range(len(self.texts))], dtype=np.float32)
        if self.duplicate:
            boxes[1] = boxes[0]
        return boxes[::-1].copy(), np.array([.8 + .01 * i for i in range(len(boxes))], dtype=np.float32)

    def detect_and_crop(self, image, operations):
        raw, scores = self.text_det.postprocess_op(None, None)
        boxes = raw[::-1].copy()  # A real reading-order change; scores stay raw.
        operations["padding_1"] = {"top": 0, "left": 0}
        crops = [np.arange(10 * (20 + i) * 3, dtype=np.uint8).reshape(10, 20 + i, 3) for i in range(len(boxes))]
        return crops, SimpleNamespace(boxes=boxes, scores=scores, elapse=0)

    def cls_and_rotate(self, images):
        output = [x.copy() for x in images]
        labels = [("0", .98) for _ in images]
        if len(images) > 1:
            output[1] = cv2.rotate(output[1], 1)
            labels[1] = ("180", .99)
        return output, SimpleNamespace(cls_res=labels, elapse=0)

    def recognize_txt(self, images):
        return SimpleNamespace(imgs=images, txts=self.texts.copy(), scores=self.scores.copy(), word_results=[None] * len(images), elapse=0)

    def run(self):
        original = np.zeros((100, 100, 3), dtype=np.uint8)
        image, operations = self.preprocess_img(original)
        crops, detected = self.detect_and_crop(image, operations)
        rotated, classified = self.cls_and_rotate(crops)
        recognized = self.recognize_txt(rotated)
        return self.build_final_output(original, detected, classified, recognized, crops, operations)


def simple(result):
    return {"texts": result.txts, "scores": result.scores, "boxes": result.boxes.tolist() if result.boxes is not None else None}


class ProbeTests(unittest.TestCase):
    def test_native_scalar_regression_keeps_original_failure_and_safe_metadata(self):
        with self.assertRaises(TypeError):
            json.dumps({"rotationPolicyTriggered": np.float32(.99) > .9})
        encoded = json.dumps(plain({"nested": [np.bool_(True), np.float32(.5), np.array([1, 2])]}))
        self.assertEqual(json.loads(encoded), {"nested": [True, .5, [1, 2]]})

    def test_real_numpy_classification_scalar_has_serializable_complete_probe(self):
        class NumpyClassificationEngine(FixtureEngine):
            def cls_and_rotate(self, images):
                rotated, classification = super().cls_and_rotate(images)
                classification.cls_res = [(label, np.float32(score)) for label, score in classification.cls_res]
                return rotated, classification

        baseline = simple(NumpyClassificationEngine().run())
        engine, observations = NumpyClassificationEngine(), {}
        with observe_ocr_stages(engine, observations):
            output = engine.run()
        self.assertEqual(simple(output), baseline)
        encoded = json.dumps(observations)
        restored = json.loads(encoded)
        self.assertEqual(restored["lineStageProbe"]["metadataErrors"], [])
        rotation = restored["lineStageProbe"]["pages"][0]["lines"][1]["classification"]["rotationPolicyTriggered"]
        self.assertIs(type(rotation), bool)
        self.assertTrue(rotation)

    def test_real_blank_and_score_filters_preserve_full_detection_mapping_and_original_output(self):
        baseline = simple(FixtureEngine().run())
        engine, observations = FixtureEngine(), {}
        original_attributes = engine.__dict__.copy()
        original_postprocessor = engine.text_det.postprocess_op
        with observe_ocr_stages(engine, observations):
            output = engine.run()
        self.assertEqual(simple(output), baseline)
        probe = observations["lineStageProbe"]
        self.assertEqual(probe["metadataErrors"], [])
        page = probe["pages"][0]
        self.assertTrue(page["complete"])
        self.assertEqual(page["finalDetectionOrdinals"], [0, 3, 4])
        self.assertEqual([x["terminalStage"] for x in page["lines"]], ["retained", "blank_recognition_text", "below_text_score", "retained", "retained"])
        self.assertTrue(page["lines"][1]["classification"]["pixelsChanged"])
        self.assertTrue(all(x["recognition"]["matchesClassifiedImage"] for x in page["lines"]))
        self.assertEqual(page["lines"][0]["detectorRawMatches"][0]["rawDetectionOrdinalZeroBased"], 4)
        self.assertEqual(engine.__dict__, original_attributes)
        self.assertIs(engine.text_det.postprocess_op, original_postprocessor)
        self.assertTrue(probe["wrappedInstanceRestored"])
        self.assertEqual(probe["status"], "complete")
        self.assertEqual(probe["counts"], {"rawDetectorBoxes": 5, "detected": 5, "classified": 5, "recognized": 5,
                                          "nonemptyBeforeScoreFilter": 4, "retained": 3, "blankDeleted": 1,
                                          "lowScoreDeleted": 1, "unknownDisposition": 0, "pages": 1})

    def test_duplicate_detector_polygons_do_not_fabricate_confidence_association(self):
        engine, observations = FixtureEngine(duplicate=True), {}
        with observe_ocr_stages(engine, observations):
            engine.run()
        lines = observations["lineStageProbe"]["pages"][0]["lines"]
        self.assertEqual(observations["lineStageProbe"]["metadataErrors"], [])
        self.assertIsNone(lines[0]["detectorConfidence"])
        self.assertEqual(len(lines[0]["detectorRawMatches"]), 2)
        self.assertTrue(observations["lineStageProbe"]["unknownReasons"])

    def test_page_state_reset_does_not_reuse_line_ids_or_removed_rows(self):
        engine, observations = FixtureEngine(), {}
        with observe_ocr_stages(engine, observations):
            engine.run(); engine.run()
        data = observations["lineStageProbe"]
        self.assertEqual(data["metadataErrors"], [])
        self.assertEqual(len(data["pages"]), 2)
        self.assertNotEqual(data["pages"][0]["lines"][0]["lineId"], data["pages"][1]["lines"][0]["lineId"])

    def test_no_surviving_scores_is_not_a_missing_probe_capture(self):
        engine, observations = FixtureEngine(texts=(" ", "low"), scores=(.9, .1)), {}
        with observe_ocr_stages(engine, observations):
            output = engine.run()
        self.assertIsNone(output.txts)
        data = observations["lineStageProbe"]
        self.assertEqual(data["metadataErrors"], [])
        self.assertEqual(data["pages"][0]["finalRows"], [])
        self.assertEqual([x["terminalStage"] for x in data["pages"][0]["lines"]], ["blank_recognition_text", "below_text_score"])

    def test_original_pipeline_exception_restores_every_method_and_detector(self):
        class FailingEngine(FixtureEngine):
            def recognize_txt(self, images):
                raise LookupError("synthetic recognition failure")

        engine, observations = FailingEngine(), {}
        original_attributes = engine.__dict__.copy()
        original_postprocessor = engine.text_det.postprocess_op
        with self.assertRaisesRegex(LookupError, "synthetic recognition failure"):
            with observe_ocr_stages(engine, observations):
                engine.run()
        self.assertEqual(engine.__dict__, original_attributes)
        self.assertIs(engine.text_det.postprocess_op, original_postprocessor)
        data = observations["lineStageProbe"]
        self.assertTrue(data["wrappedInstanceRestored"])
        self.assertEqual(data["status"], "unknown_incomplete")
        self.assertEqual(data["pipelineExceptionObserved"], {"type": "LookupError"})
        self.assertEqual(data["counts"]["detected"], 5)
        self.assertIsNone(data["counts"]["recognized"])
        self.assertTrue(all(line["terminalStage"] == "unknown" for line in data["pages"][0]["lines"]))

    def test_exception_after_complete_ocr_still_restores_shared_instance(self):
        engine, observations = FixtureEngine(), {}
        original_attributes = engine.__dict__.copy()
        original_postprocessor = engine.text_det.postprocess_op
        expected_error = RuntimeError("synthetic consumer failure")
        try:
            with observe_ocr_stages(engine, observations):
                engine.run()
                raise expected_error
        except RuntimeError as error:
            self.assertIs(error, expected_error)
        self.assertEqual(engine.__dict__, original_attributes)
        self.assertIs(engine.text_det.postprocess_op, original_postprocessor)
        self.assertTrue(observations["lineStageProbe"]["wrappedInstanceRestored"])

    def test_repeated_contexts_on_one_engine_do_not_stack_wrappers_or_pages(self):
        engine = FixtureEngine()
        original_postprocessor = engine.text_det.postprocess_op
        first, second = {}, {}
        with observe_ocr_stages(engine, first):
            first_output = simple(engine.run())
        with observe_ocr_stages(engine, second):
            second_output = simple(engine.run())
        self.assertEqual(first_output, second_output)
        self.assertEqual(len(first["lineStageProbe"]["pages"]), 1)
        self.assertEqual(len(second["lineStageProbe"]["pages"]), 1)
        self.assertEqual(first["lineStageProbe"]["counts"], second["lineStageProbe"]["counts"])
        self.assertIs(engine.text_det.postprocess_op, original_postprocessor)
        self.assertNotIn("preprocess_img", engine.__dict__)

    def test_preexisting_instance_method_override_is_restored_exactly(self):
        engine, observations = FixtureEngine(), {}
        original_preprocess = engine.preprocess_img
        def existing_override(image):
            return original_preprocess(image)
        engine.preprocess_img = existing_override
        original_attributes = engine.__dict__.copy()
        with observe_ocr_stages(engine, observations):
            engine.run()
        self.assertIs(engine.preprocess_img, existing_override)
        self.assertEqual(engine.__dict__, original_attributes)
        self.assertTrue(observations["lineStageProbe"]["wrappedInstanceRestored"])

    def test_fixture_executes_installed_methods_without_model_runtime_imports(self):
        original_import = __import__
        def forbid_neural_import(name, *args, **kwargs):
            if name.split(".")[0] in ("rapidocr", "onnxruntime", "torch"):
                raise AssertionError("Probe must not import an OCR/model runtime: " + name)
            return original_import(name, *args, **kwargs)
        with mock.patch("builtins.__import__", forbid_neural_import):
            spec = importlib.util.spec_from_file_location("probe_import_control", ocr_stage_probe.__file__)
            isolated = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(isolated)
            observations = {}
            engine = FixtureEngine()
            with isolated.observe_ocr_stages(engine, observations):
                # Metadata methods exercise the unchanged installed final-output
                # functions with synthetic NN-stage fixtures only.
                engine.run()
        self.assertTrue(observations["lineStageProbe"]["wrappedInstanceRestored"])

    def test_nested_context_rejected_before_wrapping_outer_instance(self):
        engine, outer, inner = FixtureEngine(), {}, {}
        with observe_ocr_stages(engine, outer):
            outer_method, outer_postprocessor = engine.preprocess_img, engine.text_det.postprocess_op
            with self.assertRaisesRegex(RuntimeError, "Nested OCR stage observation"):
                with observe_ocr_stages(engine, inner):
                    self.fail("Nested context body must not run")
            self.assertIs(engine.preprocess_img, outer_method)
            self.assertIs(engine.text_det.postprocess_op, outer_postprocessor)
            engine.run()
        self.assertEqual(outer["lineStageProbe"]["status"], "complete")
        self.assertEqual(inner["lineStageProbe"]["status"], "rejected_nested")
        self.assertFalse(inner["lineStageProbe"]["pages"])

    def test_unsupported_coordinate_mapper_is_unknown_and_original_ocr_unchanged(self):
        class DifferentInterfaceEngine(FixtureEngine):
            def build_final_output(self, *args):
                return super().build_final_output(*args)

        baseline = simple(DifferentInterfaceEngine().run())
        engine, observations = DifferentInterfaceEngine(), {}
        original_attributes = engine.__dict__.copy()
        with observe_ocr_stages(engine, observations):
            actual = simple(engine.run())
        self.assertEqual(actual, baseline)
        self.assertEqual(engine.__dict__, original_attributes)
        data = observations["lineStageProbe"]
        self.assertEqual(data["status"], "unsupported_runtime")
        self.assertTrue(data["unknownReasons"])
        self.assertTrue(data["wrappedInstanceRestored"])
        self.assertIsNone(data["counts"]["detected"])

    def test_metadata_mapping_exception_cannot_change_original_output(self):
        baseline = simple(FixtureEngine().run())
        engine, observations = FixtureEngine(), {}
        with observe_ocr_stages(engine, observations) as probe:
            def metadata_only_failure(*args):
                raise ValueError("synthetic copied-coordinate failure")
            probe.coordinate_mapper = metadata_only_failure
            actual = simple(engine.run())
        self.assertEqual(actual, baseline)
        data = observations["lineStageProbe"]
        self.assertTrue(data["metadataErrors"])
        self.assertTrue(data["unknownReasons"])
        self.assertEqual(data["status"], "unknown_incomplete")
        self.assertFalse(data["pages"][0]["mappingVerified"])
        self.assertEqual(data["counts"]["retained"], 3)
        self.assertIsNone(data["counts"]["lowScoreDeleted"])
        self.assertTrue(data["wrappedInstanceRestored"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
