"""Pinned MinerU 2605 complete-target CUDA LoRA compatibility runner.

Default preflight reads the processor on CPU only. CUDA modes require an explicit
resource lease. This offline runner never changes the serving model registry.
"""
from __future__ import annotations

import argparse
from collections import Counter
import gc
import importlib.metadata
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback

try:
    from .dataset import OTSL, read_json, sha256, validate_manifest, write_new
except ImportError:
    from dataset import OTSL, read_json, sha256, validate_manifest, write_new

MODEL = "opendatalab/MinerU2.5-Pro-2605-1.2B"
REVISION = "08aaea840498d49ce16247b6263196cf02814885"


def dependencies():
    result = {}
    for name in ("torch", "transformers", "peft", "accelerate", "Pillow", "safetensors"):
        try:
            result[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            result[name] = None
    return result


def encode_complete(processor, record, max_context):
    import torch
    from PIL import Image
    image = Image.open(record["imagePath"]).convert("RGB")
    messages = [{"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": [{"type": "image"},
                 {"type": "text", "text": record["prompt"]}]}]
    prompt = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = processor(text=[prompt], images=[image], return_tensors="pt", padding=False,
                       truncation=False)
    target = record["targetText"]
    ids = processor.tokenizer.encode(target, add_special_tokens=False)
    if processor.tokenizer.decode(ids, skip_special_tokens=False) != target:
        raise ValueError("Tokenizer cannot preserve complete literal target exactly")
    eos = processor.tokenizer.eos_token_id
    if eos is None:
        raise ValueError("Explicit target EOS is required")
    target_ids = torch.tensor([ids + [eos]], dtype=inputs.input_ids.dtype)
    prefix_count = inputs.input_ids.shape[1]
    full_count = prefix_count + target_ids.shape[1]
    if full_count > max_context:
        raise ValueError(f"Complete input+target requires {full_count} tokens; cap {max_context}; truncation forbidden")
    inputs["input_ids"] = torch.cat([inputs.input_ids, target_ids], dim=1)
    inputs["attention_mask"] = torch.cat([inputs.attention_mask, torch.ones_like(target_ids)], dim=1)
    labels = inputs.input_ids.clone()
    labels[:, :prefix_count] = -100
    inputs["labels"] = labels
    special = {}
    for token in OTSL:
        token_ids = processor.tokenizer.encode(token, add_special_tokens=False)
        if len(token_ids) != 1:
            raise ValueError(f"Raw OTSL token vocabulary changed: {token}")
        token_id = token_ids[0]
        expected = ids.count(token_id)
        supervised = int((labels[:, prefix_count:] == token_id).sum())
        if expected != supervised:
            raise ValueError(f"Assistant OTSL loss mask lost {token}")
        special[token] = {"id": token_id, "assistantSupervisedCount": supervised}
    observation = {"sampleId": record["record"]["sampleId"], "prompt": prompt,
                   "completeTargetSha256": record["record"]["target"]["sha256"],
                   "inputTokens": prefix_count, "targetTokensIncludingEos": len(ids) + 1,
                   "completeTokens": full_count, "targetComplete": True,
                   "truncation": False, "imageGrid": inputs.image_grid_thw.tolist(),
                   "processorImageTensorShape": list(inputs.pixel_values.shape),
                   "lossMask": {"promptSupervisedTokens": int((labels[:, :prefix_count] != -100).sum()),
                                "assistantSupervisedTokens": int((labels[:, prefix_count:] != -100).sum()),
                                "eosSupervised": labels[0, -1].item() == eos,
                                "otsl": special}}
    return inputs, observation


def inventory_model(snapshot):
    files = []
    for p in sorted(snapshot.rglob("*")):
        if p.is_file():
            files.append({"path": p.relative_to(snapshot).as_posix(), "bytes": p.stat().st_size,
                          "sha256": sha256(p)})
    return files


def cuda_observation(torch, name):
    torch.cuda.synchronize()
    return {"stage": name, "allocatedBytes": torch.cuda.memory_allocated(),
            "reservedBytes": torch.cuda.memory_reserved(),
            "peakAllocatedBytes": torch.cuda.max_memory_allocated(),
            "peakReservedBytes": torch.cuda.max_memory_reserved(),
            "freeAndTotalDeviceBytes": list(torch.cuda.mem_get_info())}


def parameter_runtime(model, *, expected_dtype=None):
    counts = Counter()
    for name, parameter in model.named_parameters():
        if parameter.device.type != "cuda":
            raise RuntimeError(f"Model parameter left off CUDA: {name}")
        if expected_dtype is not None and parameter.dtype != expected_dtype:
            raise RuntimeError(f"Model parameter dtype differs: {name}")
        counts[(str(parameter.device), str(parameter.dtype), parameter.requires_grad)] += parameter.numel()
    return [{"device": device, "dtype": dtype, "requiresGrad": gradient, "parameterCount": count}
            for (device, dtype, gradient), count in sorted(counts.items())]


def load_model(snapshot, dtype, attention):
    import torch
    from transformers import Qwen2VLForConditionalGeneration
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA required; no CPU model fallback")
    if dtype == "bfloat16" and not torch.cuda.is_bf16_supported():
        raise RuntimeError("Selected CUDA device does not support bfloat16")
    torch.cuda.reset_peak_memory_stats()
    return Qwen2VLForConditionalGeneration.from_pretrained(
        snapshot, local_files_only=True, torch_dtype=getattr(torch, dtype),
        attn_implementation=attention).to("cuda")


def generate(model, processor, encoded, observations, output, max_output):
    import torch
    results = []
    model.eval()
    for inputs, observation in zip(encoded, observations):
        prefix = observation["inputTokens"]
        generation_inputs = {k: v for k, v in inputs.items() if k != "labels"}
        generation_inputs["input_ids"] = inputs.input_ids[:, :prefix]
        generation_inputs["attention_mask"] = inputs.attention_mask[:, :prefix]
        generation_inputs = {k: v.to("cuda") for k, v in generation_inputs.items()}
        remaining = model.config.text_config.max_position_embeddings - prefix
        if max_output > remaining:
            raise ValueError("Full requested output reserve does not fit; silent output cap forbidden")
        with torch.inference_mode():
            ids = model.generate(**generation_inputs, do_sample=False, use_cache=True,
                                 max_new_tokens=max_output)[0, prefix:].tolist()
        eos = processor.tokenizer.eos_token_id
        stopped = bool(ids and ids[-1] == eos)
        text = processor.tokenizer.decode(ids[:-1] if stopped else ids, skip_special_tokens=False)
        results.append({"sampleId": observation["sampleId"], "text": text,
                        "finishReason": "stop" if stopped else "length", "tokenIds": ids,
                        "inputTokens": prefix, "outputTokens": len(ids)})
    write_new(output, {"outputs": results})
    return results


def run(args):
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    args.output.mkdir(parents=True, exist_ok=False)
    manifest, records, preflight = validate_manifest(args.manifest)
    receipt = {"schema": "consense.mineru-lora-pilot.v1", "mode": args.mode,
               "status": "running", "baseModel": MODEL, "baseRevision": REVISION,
               "manifestSha256": preflight["manifestSha256"], "purpose": manifest["purpose"],
               "cudaLease": args.cuda_lease, "dependencyVersions": dependencies(),
               "snapshotPath": str(args.snapshot.resolve()),
               "dependencyOverlay": str(args.dependency_overlay.resolve()) if args.dependency_overlay else None,
               "dtype": args.dtype, "attention": args.attention, "trainingSteps": 0,
               "qualityImprovementClaim": False, "parserReloadAccepted": False,
               "fullInputAndTarget": True, "truncation": False, "stages": []}
    started = time.perf_counter()
    try:
        from transformers import AutoProcessor
        processor = AutoProcessor.from_pretrained(args.snapshot, local_files_only=True, use_fast=False)
        config = read_json(args.snapshot / "config.json")
        if config.get("model_type") != "qwen2_vl" or config.get("hidden_size") != 896:
            raise ValueError("Pilot expects the pinned 2605 Qwen2-VL architecture")
        cap = min(config["text_config"]["max_position_embeddings"], args.max_context)
        if cap != args.max_context:
            raise ValueError("Requested context exceeds checkpoint effective context")
        encoded, observations = [], []
        for record in records:
            inputs, obs = encode_complete(processor, record, cap)
            encoded.append(inputs)
            observations.append(obs)
        write_new(args.output / "data-preflight.json", preflight)
        write_new(args.output / "complete-input-target-observations.json", observations)
        receipt["completeInputTargetPreflight"] = True
        if args.mode == "preflight":
            receipt["status"] = "cpu_preflight_complete_no_model_loaded"
            return receipt
        if not args.cuda_lease:
            raise ValueError("CUDA execution requires an explicit resource lease")
        import torch
        torch.set_num_threads(args.threads)
        torch.manual_seed(args.seed)
        model = load_model(args.snapshot, args.dtype, args.attention)
        receipt["loadedParameterRuntime"] = parameter_runtime(model, expected_dtype=getattr(torch, args.dtype))
        receipt["stages"].append(cuda_observation(torch, "model_loaded"))
        receipt["parameterCount"] = sum(p.numel() for p in model.parameters())
        baseline = generate(model, processor, encoded, observations, args.output / "baseline-raw.json", args.max_output)
        write_new(args.output / "baseline.json", {"manifestSha256": preflight["manifestSha256"], "outputs": baseline})
        receipt["stages"].append(cuda_observation(torch, "baseline_generated"))
        if args.mode == "baseline":
            receipt["status"] = "cuda_baseline_complete_no_training"
            return receipt
        from peft import LoraConfig, get_peft_model
        model.requires_grad_(False)
        # Explicit module names stop LoRA from touching similarly named ViT
        # projections. LLM only; vision/projector/embeddings/lm_head stay frozen.
        suffixes = {"q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"}
        targets = [name for name, module in model.named_modules()
                   if ".layers." in name and "visual" not in name
                   and name.rsplit(".", 1)[-1] in suffixes and isinstance(module, torch.nn.Linear)]
        if len(targets) != 24 * 7:
            raise ValueError(f"Expected 168 explicit LLM projections, got {len(targets)}")
        model = get_peft_model(model, LoraConfig(r=args.rank, lora_alpha=args.rank * 2,
                                                lora_dropout=0, target_modules=targets,
                                                task_type="CAUSAL_LM", bias="none"))
        model.config.use_cache = False
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
        if hasattr(model, "enable_input_require_grads"):
            model.enable_input_require_grads()
        trainable = [(n, p) for n, p in model.named_parameters() if p.requires_grad]
        if not trainable or any("lora_" not in n or "visual" in n for n, p in trainable):
            raise ValueError("Unexpected trainable module; frozen vision contract failed")
        receipt["trainableParameterCount"] = sum(p.numel() for _, p in trainable)
        receipt["trainableNames"] = [n for n, _ in trainable]
        receipt["loraParameterRuntime"] = parameter_runtime(model)
        receipt["targetModules"] = targets
        optimizer = torch.optim.AdamW([p for _, p in trainable], lr=args.learning_rate)
        training = [i for i, record in enumerate(records) if record["source"]["partition"] == "train"]
        if not training:
            raise ValueError("No training partition")
        losses, grads = [], []
        receipt["gradientAudit"] = []
        result = loss = batch = None
        model.train()
        for step in range(args.steps):
            optimizer.zero_grad(set_to_none=True)
            for micro in range(args.gradient_accumulation):
                index = training[(step * args.gradient_accumulation + micro) % len(training)]
                batch = {k: v.to("cuda") for k, v in encoded[index].items()}
                result = model(**batch, use_cache=False)
                loss = result.loss
                if not torch.isfinite(loss).item():
                    raise RuntimeError("Nonfinite complete-target loss")
                losses.append({"step": step, "micro": micro, "sampleId": observations[index]["sampleId"],
                               "loss": loss.item()})
                receipt["stages"].append(cuda_observation(torch, f"forward_{step}_{micro}"))
                (loss / args.gradient_accumulation).backward()
                receipt["stages"].append(cuda_observation(torch, f"backward_{step}_{micro}"))
            grads = [p.grad for _, p in trainable if p.grad is not None]
            if not grads or any(not torch.isfinite(g).all().item() for g in grads):
                raise RuntimeError("Missing/nonfinite LoRA gradients")
            grad_norm = torch.nn.utils.clip_grad_norm_([p for _, p in trainable], 1.0, error_if_nonfinite=True)
            receipt["gradientAudit"].append({"step": step, "gradientTensorCount": len(grads),
                                             "preClipGlobalNorm": grad_norm.item(), "allFinite": True})
            optimizer.step()
            receipt["trainingSteps"] += 1
            receipt["stages"].append(cuda_observation(torch, f"optimizer_{step}"))
        write_new(args.output / "losses.json", losses)
        model.save_pretrained(args.output / "adapter", safe_serialization=True)
        model.eval()
        model.gradient_checkpointing_disable()
        merged = model.merge_and_unload()
        receipt["mergedParameterRuntime"] = parameter_runtime(merged, expected_dtype=getattr(torch, args.dtype))
        merged.config.use_cache = True
        merged.save_pretrained(args.output / "merged", safe_serialization=True)
        processor.save_pretrained(args.output / "merged")
        trained = generate(merged, processor, encoded, observations, args.output / "merged-memory-raw.json", args.max_output)
        write_new(args.output / "merged-memory.json", {"manifestSha256": preflight["manifestSha256"], "outputs": trained})
        write_new(args.output / "merged-artifact-manifest.json", inventory_model(args.output / "merged"))
        receipt["exportComplete"] = True
        del optimizer, model, merged, trainable, grads, result, loss, batch
        gc.collect()
        torch.cuda.empty_cache()
        receipt["stages"].append(cuda_observation(torch, "parent_models_released_before_reload"))
        # A new process reads only the exported HF directory. Retained parent
        # tensors or an in-memory merged object never count as reload evidence.
        command = [sys.executable, str(Path(__file__).resolve()), "--manifest", str(args.manifest.resolve()),
                   "--snapshot", str((args.output / "merged").resolve()), "--output", str((args.output / "fresh-reload").resolve()),
                   "--mode", "baseline", "--cuda-lease", args.cuda_lease, "--dtype", args.dtype,
                   "--attention", args.attention, "--max-context", str(args.max_context),
                   "--max-output", str(args.max_output), "--threads", str(args.threads)]
        if args.dependency_overlay:
            command.extend(["--dependency-overlay", str(args.dependency_overlay.resolve())])
        with (args.output / "fresh-reload.stdout.txt").open("xb") as stdout, (args.output / "fresh-reload.stderr.txt").open("xb") as stderr:
            code = subprocess.call(command, stdout=stdout, stderr=stderr)
        receipt["freshProcessReloadExitCode"] = code
        if code != 0:
            raise RuntimeError("Fresh-process exported model reload failed; inspect preserved stderr")
        fresh = read_json(args.output / "fresh-reload" / "baseline.json")
        receipt["freshReloadGenerationTokenIdsEqual"] = [x["tokenIds"] for x in fresh["outputs"]] == [x["tokenIds"] for x in trained]
        if not receipt["freshReloadGenerationTokenIdsEqual"]:
            raise RuntimeError("Fresh exported model generation differs from merged in-memory model")
        receipt["status"] = "cuda_tiny_train_export_fresh_reload_complete_parser_gate_pending"
        return receipt
    except BaseException as error:
        receipt["status"] = "failed_preserved"
        receipt["errorClass"] = type(error).__name__
        receipt["error"] = str(error)
        (args.output / "failure-traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
        raise
    finally:
        receipt["elapsedSeconds"] = time.perf_counter() - started
        write_new(args.output / "receipt.json", receipt)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--snapshot", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--mode", choices=("preflight", "baseline", "train"), default="preflight")
    p.add_argument("--cuda-lease", default="")
    p.add_argument("--dependency-overlay", type=Path,
                   help="Isolated PEFT/accelerate --target directory; never installs into serving environment")
    p.add_argument("--dtype", choices=("bfloat16", "float16"), default="bfloat16")
    p.add_argument("--attention", choices=("eager", "sdpa"), default="sdpa")
    p.add_argument("--steps", type=int, default=1)
    p.add_argument("--gradient-accumulation", type=int, default=1)
    p.add_argument("--rank", type=int, default=8)
    p.add_argument("--learning-rate", type=float, default=2e-5)
    p.add_argument("--max-context", type=int, default=8192)
    p.add_argument("--max-output", type=int, default=512)
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--seed", type=int, default=20261010)
    args = p.parse_args()
    if args.steps < 0 or args.rank < 1 or args.gradient_accumulation < 1 or not math.isfinite(args.learning_rate) or args.learning_rate <= 0:
        p.error("Invalid pilot parameters")
    if args.max_context < 1 or args.max_output < 1 or args.threads < 1:
        p.error("Context, output and thread budgets must be positive")
    if args.dependency_overlay:
        if not args.dependency_overlay.is_dir():
            p.error("Dependency overlay directory is missing")
        sys.path.insert(0, str(args.dependency_overlay.resolve()))
    receipt = run(args)
    print(json.dumps({k: receipt[k] for k in ("status", "trainingSteps", "elapsedSeconds", "qualityImprovementClaim")}))


if __name__ == "__main__":
    main()
