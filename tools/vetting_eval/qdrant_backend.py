"""One shared storage backend; changing it requires an explicitly verified index."""
from pathlib import Path


def connect(state, environ):
    from qdrant_client import QdrantClient
    url = environ.get('CONSENSE_QDRANT_URL')
    if url:
        return QdrantClient(url=url, api_key=environ.get('CONSENSE_QDRANT_API_KEY'), timeout=120)
    from qdrant_client.local import distances
    if not getattr(distances.cosine_similarity, '_consense_readonly', False):
        original = distances.cosine_similarity

        def readonly_cosine(query, vectors):
            # Qdrant Local 1.19.1 normalizes this matrix in place during reads.
            return original(query, vectors.copy())

        readonly_cosine._consense_readonly = True
        distances.cosine_similarity = readonly_cosine
    root = Path(state)
    root.mkdir(parents=True, exist_ok=True)
    return QdrantClient(path=str(root / 'qdrant'))
