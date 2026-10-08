"""Vetting OCR extension of the existing local API.

Run this entry point to include /vetting/ocr. The immutable embedding/index
core and shared /ocr response retain their original source identity.
"""
import io
import argparse
from fastapi import File, HTTPException, UploadFile
import server as baseline
from ocr_response import recognize_vetting_page

app = baseline.app


@app.post('/vetting/ocr')
def vetting_ocr(file: UploadFile = File(...)):
    from PIL import Image, UnidentifiedImageError
    raw = file.file.read(25*1024*1024+1)
    if len(raw) > 25*1024*1024:
        raise HTTPException(413, 'Raster upload exceeds 25 MiB')
    try:
        image = Image.open(io.BytesIO(raw))
        if image.width*image.height > 40_000_000:
            raise HTTPException(413, 'Raster exceeds 40 million pixels')
        image = image.convert('RGB')
    except (UnidentifiedImageError, OSError, ValueError) as error:
        raise HTTPException(422, 'Upload a readable raster page image') from error
    try:
        with baseline.LOCK:
            if baseline._ocr is None:
                baseline._ocr = baseline.create_ocr_engine()
            return recognize_vetting_page(baseline._ocr, image)
    except Exception as error:
        raise HTTPException(503, f'OCR unavailable: {type(error).__name__}: {error}') from error


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=18081)
    args = parser.parse_args()
    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port, workers=1)
