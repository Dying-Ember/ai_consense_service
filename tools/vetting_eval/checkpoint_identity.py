"""Exact local checkpoint/tokenizer/shard/recipe inventory without inference."""
import json
from pathlib import Path, PurePosixPath
from token_windows import fail


MODEL_FILES = ('tokenizer.json', 'tokenizer_config.json', 'special_tokens_map.json',
    'sentencepiece.bpe.model', 'config.json', 'sentence_bert_config.json', 'modules.json',
    'config_sentence_transformers.json', 'model.safetensors', 'pytorch_model.bin',
    'model.safetensors.index.json', 'pytorch_model.bin.index.json',
    'adapter_config.json', 'adapter_model.safetensors', 'adapter_model.bin',
    'consense_model_recipe.json', 'training_recipe.json', 'consense-checkpoint.json',
    '1_Pooling/config.json')


def checkpoint_inventory(root, file_identity):
    root = Path(root).resolve()
    names = {name for name in MODEL_FILES if (root / name).is_file()}
    for index_name in ('model.safetensors.index.json', 'pytorch_model.bin.index.json'):
        if index_name not in names:
            continue
        def unique(pairs):
            value = {}
            for key, item in pairs:
                fail(key not in value, 'MODEL_SHARD_INDEX_INVALID', 'Duplicate model shard index field')
                value[key] = item
            return value
        try:
            index = json.loads((root / index_name).read_bytes(), object_pairs_hook=unique,
                               parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
        except (OSError, UnicodeError, ValueError) as error:
            fail(False, 'MODEL_SHARD_INDEX_INVALID', 'Shard index must be exact strict JSON', cause=str(error))
        weight_map = index.get('weight_map') if isinstance(index, dict) else None
        fail(isinstance(weight_map, dict) and bool(weight_map), 'MODEL_SHARD_INDEX_INVALID', 'Shard index requires explicit weight_map')
        for name in weight_map.values():
            fail(isinstance(name, str) and name and '\\' not in name
                 and not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts,
                 'MODEL_SHARD_PATH_INVALID', 'Shard must be a portable relative path inside its checkpoint')
            path = (root / name).resolve()
            fail(path.is_relative_to(root) and path.is_file(), 'MODEL_SHARD_UNAVAILABLE', 'Every declared shard must exist inside the snapshot')
            names.add(name)
    result = []
    for name in sorted(names):
        path = (root / name).resolve()
        fail(path.is_relative_to(root), 'MODEL_FILE_PATH_INVALID', 'Checkpoint file must stay within its snapshot')
        result.append({**file_identity(path), 'relativePath': name})
    return result
