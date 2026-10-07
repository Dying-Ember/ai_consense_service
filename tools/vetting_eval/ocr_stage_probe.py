"""Temporary per-line observation of an already-created RapidOCR engine.

No OCR/native imports, no weights, no expected text, no recognition calls here.
The original methods receive the original arguments and return original objects.
JSON snapshots and copied coordinate transforms are diagnostic metadata only.
Use observe_ocr_stages() inside the caller's engine lock. No file or model IO.
"""
import hashlib
import time

PROBE_TAG = "ocr-stage-probe-v1"
PROBE_SCHEMA = "rapidocr-line-flow-observation-v1"
_ACTIVE_MARKER = "_consense_ocr_stage_probe_active"
_METHODS = ("preprocess_img", "detect_and_crop", "cls_and_rotate", "recognize_txt", "build_final_output", "filter_by_text_score")
_COUNT_FIELDS = ("rawDetectorBoxes", "detected", "classified", "recognized", "nonemptyBeforeScoreFilter", "retained", "blankDeleted", "lowScoreDeleted", "unknownDisposition")


def plain(value):
    if hasattr(value, "tolist"):
        return plain(value.tolist())
    if isinstance(value, (list, tuple)):
        return [plain(x) for x in value]
    if isinstance(value, dict):
        return {k: plain(v) for k, v in value.items()}
    if hasattr(value, "item"):
        return plain(value.item())
    return value


def image_identity(value):
    return {"shape": list(value.shape), "dtype": str(value.dtype),
            "pixelsSha256": hashlib.sha256(value.tobytes()).hexdigest()}


def output_rows(value):
    texts, scores, boxes = value.txts, value.scores, value.boxes
    if texts is None:
        return []
    return [{"text": str(t), "confidence": float(s), "originalImagePolygon": plain(b)}
            for t, s, b in zip(texts, scores, boxes)]


class CallProxy:
    def __init__(self, original, after):
        object.__setattr__(self, "original", original)
        object.__setattr__(self, "after", after)

    def __getattr__(self, name):
        return getattr(self.original, name)

    def __setattr__(self, name, value):
        setattr(self.original, name, value)

    def __call__(self, *args, **kwargs):
        result = self.original(*args, **kwargs)
        self.after(result)
        return result


class LineStageProbe:
    def __init__(self, engine, observations):
        if not isinstance(observations, dict):
            raise TypeError("OCR observations must be a dict")
        if "lineStageProbe" in observations:
            raise ValueError("Use a fresh observations container for each context")
        self.engine = engine
        self.data = observations.setdefault("lineStageProbe", {
            "tag": PROBE_TAG, "schema": PROBE_SCHEMA, "pages": [],
            "metadataErrors": [], "metadataSeconds": [], "parametersChanged": False,
            "additionalNeuralCalls": 0, "expectedTextOrRegionRead": False,
            "thirdPartyFilesModified": False, "unknownReasons": [], "status": "ready",
            "counts": {name: None for name in _COUNT_FIELDS}, "wrappedInstanceRestored": False,
            "runtimeInterface": {"engineType": type(engine).__module__ + "." + type(engine).__qualname__,
                                 "versionStatus": "not_read_by_probe", "capabilityStatus": "not_checked"}})
        self.current = None
        self.originals = {}
        self.coordinate_mapper = None
        self._restore_states = []
        self._active = False
        self._entered = False

    def unknown(self, stage, reason):
        self.data["unknownReasons"].append({"stage": stage, "reason": reason})

    def measure(self, action):
        started = time.perf_counter()
        try:
            action()
        except Exception as error:
            self.data["metadataErrors"].append({"type": type(error).__name__, "message": str(error)})
            self.unknown("metadata", "Observation or mapping failed: " + type(error).__name__)
            if self.current is not None:
                self.current["mappingVerified"] = False
        finally:
            self.data["metadataSeconds"].append(time.perf_counter() - started)

    def wrap(self, name, before=None, after=None):
        original = getattr(self.engine, name)
        self.originals[name] = original

        def observed(*args, **kwargs):
            if before is not None:
                self.measure(lambda: before(args, kwargs))
            result = original(*args, **kwargs)
            if after is not None:
                self.measure(lambda: after(args, kwargs, result))
            return result

        self.assign(self.engine, name, observed)

    def assign(self, owner, name, value):
        state = (owner, name, name in owner.__dict__, owner.__dict__.get(name))
        self._restore_states.append(state)
        setattr(owner, name, value)

    def restore(self):
        failures = []
        for owner, name, existed, previous in reversed(self._restore_states):
            try:
                if existed:
                    setattr(owner, name, previous)
                else:
                    delattr(owner, name)
            except Exception as error:
                failures.append({"type": type(error).__name__, "message": str(error)})
        self._restore_states.clear()
        self._active = False
        self.data["wrappedInstanceRestored"] = not failures
        if failures:
            self.data["metadataErrors"].extend(failures)
            self.unknown("restore", "Instance restoration was incomplete")

    def summarize(self):
        for name in _COUNT_FIELDS:
            values = [page["counts"].get(name) for page in self.data["pages"]]
            self.data["counts"][name] = sum(values) if values and all(value is not None for value in values) else None
        self.data["counts"]["pages"] = len(self.data["pages"])
        if self.data["status"] in ("unsupported_runtime", "rejected_nested"):
            return
        complete = bool(self.data["pages"]) and all(page.get("complete") and page["mappingVerified"] for page in self.data["pages"])
        self.data["status"] = "complete" if complete and not self.data["metadataErrors"] and not self.data.get("pipelineExceptionObserved") else "unknown_incomplete"
        if not self.data["pages"]:
            self.unknown("pipeline", "No page observation was produced")

    def start_page(self, args, kwargs):
        self.current = {"pageOrdinalZeroBased": len(self.data["pages"]), "originalImageShape": list(args[0].shape),
                        "lines": [], "mappingVerified": True, "complete": False, "finalRows": [],
                        "counts": {name: None for name in _COUNT_FIELDS}}
        self.data["pages"].append(self.current)

    def preprocessed(self, args, kwargs, result):
        self.current["preprocess"] = {"imageShape": list(result[0].shape), "operationRecord": plain(result[1])}

    def detector_postprocessed(self, result):
        def record():
            boxes, scores = result
            self.current["counts"]["rawDetectorBoxes"] = len(boxes)
            self.current["detectorBeforeReadingOrder"] = [
                {"rawDetectionOrdinalZeroBased": i, "polygon": plain(box), "confidence": float(scores[i])}
                for i, box in enumerate(boxes)]
        self.measure(record)

    def detected(self, args, kwargs, result):
        crops, det = result
        boxes = plain(det.boxes)
        self.current["counts"]["detected"] = len(boxes)
        if len(crops) != len(boxes):
            raise ValueError("Detection box/crop count mismatch")
        original_detections = self.current.get("detectorBeforeReadingOrder", [])
        rows = []
        for i, (box, crop) in enumerate(zip(boxes, crops)):
            matches = [x for x in original_detections if x["polygon"] == box]
            rows.append({"lineId": f"page-{self.current['pageOrdinalZeroBased']}:det-{i}", "detectionOrdinalZeroBased": i,
                         "detectorPreprocessedPolygon": box, "detectorRawMatches": matches,
                         "detectorConfidence": matches[0]["confidence"] if len(matches) == 1 else None,
                         "detectorConfidenceAssociation": "exact_polygon_to_unsorted_postprocess" if len(matches) == 1 else "unknown_duplicate_or_missing_polygon",
                         "cropBeforeClassification": image_identity(crop), "terminalStage": "unknown"})
            if len(matches) != 1:
                self.unknown("detector_confidence", "Detection ordinal " + str(i) + " has duplicate or missing original polygon")
        self.current["lines"] = rows
        self.current["operationRecordAfterDetection"] = plain(args[1])

    def classified(self, args, kwargs, result):
        images, cls = result
        rows = self.current["lines"]
        self.current["counts"]["classified"] = len(cls.cls_res)
        if len(images) != len(rows) or len(cls.cls_res) != len(rows):
            raise ValueError("Classification order/count differs")
        for row, image, classification in zip(rows, images, cls.cls_res):
            label, score = classification
            row["classification"] = {"label": str(label), "confidence": float(score), "threshold": float(self.engine.text_cls.cls_thresh),
                                     "rotationPolicyTriggered": bool("180" in label and score > self.engine.text_cls.cls_thresh),
                                     "imageAfterClassification": image_identity(image)}
            row["classification"]["pixelsChanged"] = row["cropBeforeClassification"]["pixelsSha256"] != row["classification"]["imageAfterClassification"]["pixelsSha256"]

    def recognized(self, args, kwargs, result):
        rows = self.current["lines"]
        self.current["counts"]["recognized"] = len(result.txts)
        if len(args[0]) != len(rows) or len(result.txts) != len(rows) or len(result.scores) != len(rows):
            raise ValueError("Recognizer order/count differs")
        for row, image, text, score in zip(rows, args[0], result.txts, result.scores):
            identity = image_identity(image)
            row["recognition"] = {"text": str(text), "confidence": float(score), "inputImage": identity,
                                  "matchesClassifiedImage": identity == row["classification"]["imageAfterClassification"]}
            if not row["recognition"]["matchesClassifiedImage"]:
                raise ValueError("Recognized crop is not the classified ordinal crop")

    def before_build(self, args, kwargs):
        original_image, det, cls, rec, crops, operations = args
        h, w = original_image.shape[:2]
        mapped = self.coordinate_mapper(det.boxes.copy(), operations, h, w)
        if len(mapped) != len(self.current["lines"]):
            raise ValueError("Copied original-coordinate mapping count differs")
        for row, box in zip(self.current["lines"], mapped):
            row["originalImagePolygonBeforeFiltering"] = plain(box)
            row["originalPageNormalizedPolygon"] = [[float(p[0]) / w, float(p[1]) / h] for p in box]
            row["blankTextFilter"] = {"textIsNonempty": bool(row["recognition"]["text"].strip()), "mappingVerified": False}
        self.current["metadataOnlyCoordinateMapOfCopiedBoxes"] = True

    def before_score_filter(self, args, kwargs):
        incoming = output_rows(args[0])
        self.current["counts"]["nonemptyBeforeScoreFilter"] = len(incoming)
        ids = [i for i, row in enumerate(self.current["lines"]) if row["recognition"]["text"].strip()]
        expected = [{"text": self.current["lines"][i]["recognition"]["text"],
                     "confidence": self.current["lines"][i]["recognition"]["confidence"],
                     "originalImagePolygon": self.current["lines"][i]["originalImagePolygonBeforeFiltering"]} for i in ids]
        self.current["beforeScoreFilter"] = incoming
        self.current["nonemptyDetectionOrdinals"] = ids
        if incoming != expected:
            raise ValueError("Actual blank-filter output differs from original ordinal rows")
        for i, row in enumerate(self.current["lines"]):
            row["blankTextFilter"]["mappingVerified"] = True
            row["blankTextFilter"]["kept"] = i in ids
        self.current["scoreThreshold"] = float(self.engine.text_score)

    def scored(self, args, kwargs, result):
        observed = output_rows(result)
        before = self.current["beforeScoreFilter"]
        ids = self.current["nonemptyDetectionOrdinals"]
        kept = [(i, row) for i, row in zip(ids, before) if row["confidence"] >= self.engine.text_score]
        if observed != [row for i, row in kept]:
            raise ValueError("Actual confidence-filter output differs from ordered original rows")
        self.current["afterScoreFilter"] = observed
        final_ids = [i for i, row in kept]
        self.current["finalDetectionOrdinals"] = final_ids
        for i, row in enumerate(self.current["lines"]):
            row["scoreFilter"] = {"entered": i in ids, "kept": i in final_ids, "threshold": float(self.engine.text_score), "mappingVerified": True}
            row["finalOrdinalZeroBased"] = final_ids.index(i) if i in final_ids else None
            row["terminalStage"] = "retained" if i in final_ids else ("blank_recognition_text" if i not in ids else "below_text_score")
        stages = [row["terminalStage"] for row in self.current["lines"]]
        self.current["counts"].update(retained=stages.count("retained"), blankDeleted=stages.count("blank_recognition_text"),
                                      lowScoreDeleted=stages.count("below_text_score"), unknownDisposition=stages.count("unknown"))

    def built(self, args, kwargs, result):
        final = output_rows(result)
        self.current["finalRows"] = final
        self.current["counts"]["retained"] = len(final)
        if final != self.current.get("afterScoreFilter", []):
            raise ValueError("Final output differs after confidence filtering")
        self.current["complete"] = True

    def __enter__(self):
        if self._entered:
            raise RuntimeError("Each OCR observation context is single use")
        self._entered = True
        if getattr(self.engine, _ACTIVE_MARKER, None) is not None:
            self.data["status"] = "rejected_nested"
            self.unknown("attachment", "Nested observation would stack wrappers")
            raise RuntimeError("Nested OCR stage observation is not allowed")
        missing = [name for name in _METHODS if not callable(getattr(self.engine, name, None))]
        detector = getattr(self.engine, "text_det", None)
        postprocessor = getattr(detector, "postprocess_op", None)
        build = getattr(self.engine, "build_final_output", None)
        namespace = getattr(getattr(build, "__func__", None), "__globals__", {})
        self.coordinate_mapper = namespace.get("map_boxes_to_original")
        if missing or not callable(postprocessor) or not callable(self.coordinate_mapper) or not hasattr(self.engine, "__dict__") or not hasattr(detector, "__dict__"):
            self.data["status"] = "unsupported_runtime"
            self.data["runtimeInterface"]["capabilityStatus"] = "unknown_unsupported"
            self.unknown("attachment", "Required runtime methods/postprocessor/coordinate mapper or instance attributes differ")
            self.data["wrappedInstanceRestored"] = True
            return self
        self.data["runtimeInterface"]["capabilityStatus"] = "checked"
        try:
            self.assign(self.engine, _ACTIVE_MARKER, self)
            self.attach()
            self._active = True
            self.data["status"] = "observing"
        except Exception as error:
            self.data["status"] = "unsupported_runtime"
            self.data["runtimeInterface"]["capabilityStatus"] = "unknown_attachment_failed"
            self.data["metadataErrors"].append({"type": type(error).__name__, "message": str(error)})
            self.unknown("attachment", "Wrapping failed; original instance restored")
            self.restore()
        return self

    def __exit__(self, error_type, error, traceback):
        if error_type is not None:
            self.data["pipelineExceptionObserved"] = {"type": error_type.__name__}
        if self._active:
            self.restore()
        self.summarize()
        return False

    def attach(self):
        self.wrap("preprocess_img", before=self.start_page, after=self.preprocessed)
        self.assign(self.engine.text_det, "postprocess_op", CallProxy(self.engine.text_det.postprocess_op, self.detector_postprocessed))
        self.wrap("detect_and_crop", after=self.detected)
        self.wrap("cls_and_rotate", after=self.classified)
        self.wrap("recognize_txt", after=self.recognized)
        self.wrap("build_final_output", before=self.before_build, after=self.built)
        self.wrap("filter_by_text_score", before=self.before_score_filter, after=self.scored)
        return self


def observe_ocr_stages(engine, observations):
    """Observe one locked engine use and restore all instance wrappers on exit.

    Metadata goes to observations['lineStageProbe']; OCR returns are unchanged.
    An unsupported runtime emits unknown metadata and runs without wrappers.
    Nested contexts are rejected before any additional wrapping. The caller
    owns the engine lock and persists/project its original result separately.
    """
    return LineStageProbe(engine, observations)
