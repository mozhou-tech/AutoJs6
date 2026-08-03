# PP-OCRv6 tiny Android integration

## Decision

`ocr.paddle` ships an in-process Android OCR engine. It uses ONNX Runtime 1.28.0,
OpenCV, and the official PP-OCRv6 tiny detection and recognition ONNX models. It
does not use WebView, WebAssembly, a network service, or a separately installed
plugin.

An enabled and authorized external Paddle OCR plugin remains compatible and is
preferred when present. If discovery or invocation fails, the host falls back to
the embedded engine. Packaged INRT applications always use the embedded engine.

## Components

- Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` native libraries,
  extracted from the official AAR and linked by the existing RapidOCR C++ module.
- Native algorithm baseline: RapidOCR 3.9.2, adapted to this project's Android
  C++ interface.
- Detection: `PaddlePaddle/PP-OCRv6_tiny_det_onnx`, revision
  `2ba1506c0380b8f0b03dd142459aac66d4421f6c`.
- Recognition: `PaddlePaddle/PP-OCRv6_tiny_rec_onnx`, revision
  `2612ab37152ae0a677521bae4e1e3d4fb4cf7c30`.
- License: Apache-2.0 for ONNX Runtime, RapidOCR, and the PaddleOCR models.

The user-selected source model is
`https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec`; the bundled ONNX file is
the official PaddlePaddle ONNX counterpart rather than a locally converted model.

## Data flow

1. Convert the Android `Bitmap` from RGBA to BGR.
2. Resize detection input to a multiple of 32, preserving aspect ratio.
3. Normalize with the values from the official detection `inference.yml`.
4. Run DB detection, threshold the probability map, expand polygons, and recover
   coordinates in the source bitmap.
5. Perspective-crop each text polygon.
6. Resize each crop to height 48 with dynamic width and normalize to `[-1, 1]`.
7. Run the recognition model and CTC-decode its 6906-class output using the
   official 6904-character dictionary plus blank and space tokens.
8. Return text, mean recognition confidence, and axis-aligned bounds through the
   existing `OcrResult` API.

## Defaults and compatibility

- CPU execution provider, four threads by default.
- Detection side length defaults to 960 and honors `detLongSize` when supplied.
- Official PP-OCRv6 thresholds are used unless `scoreThreshold` overrides the
  recognition threshold.
- Orientation classification is disabled because the tiny pipeline does not ship
  an orientation model.
- `useSlim` and `useOpenCL` remain accepted for script compatibility but do not
  change this CPU ONNX engine.
- RapidOCR and PaddleOCR use separate native model instances while sharing one
  ONNX Runtime library.

## Reproducibility

Model files are stored with source revision and SHA-256 metadata. Runtime binaries
are downloaded from Maven Central using the version in `version.properties`.
Release validation must cover arm64-v8a, armeabi-v7a, x86, and x86_64 plus an INRT
packaging smoke test.
