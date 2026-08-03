package com.benjaminwan.ocrlibrary

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * In-process PP-OCRv6 tiny engine backed by Android ONNX Runtime.
 * It owns a native model instance separate from [OcrEngine].
 */
class PaddleOcrV6Engine(context: Context, numThread: Int = DEFAULT_NUM_THREADS) {

    init {
        System.loadLibrary("RapidOcr")
        require(
            initNative(
                context.assets,
                context.cacheDir.absolutePath,
                numThread.coerceAtLeast(1),
                DET_MODEL,
                REC_MODEL,
                REC_DICTIONARY,
            )
        ) { "Failed to initialize embedded PP-OCRv6 tiny engine" }
    }

    fun detect(
        input: Bitmap,
        output: Bitmap,
        maxSideLen: Int = DEFAULT_DET_SIDE_LEN,
        boxScoreThresh: Float = DEFAULT_BOX_SCORE_THRESHOLD,
        boxThresh: Float = DEFAULT_BOX_THRESHOLD,
        unClipRatio: Float = DEFAULT_UNCLIP_RATIO,
    ): OcrResult = detectNative(
        input,
        output,
        maxSideLen,
        boxScoreThresh,
        boxThresh,
        unClipRatio,
    )

    private external fun initNative(
        assetManager: AssetManager,
        openCvPluginDir: String,
        numThread: Int,
        detName: String,
        recName: String,
        keysName: String,
    ): Boolean

    private external fun detectNative(
        input: Bitmap,
        output: Bitmap,
        maxSideLen: Int,
        boxScoreThresh: Float,
        boxThresh: Float,
        unClipRatio: Float,
    ): OcrResult

    companion object {
        const val DEFAULT_NUM_THREADS = 4
        const val DEFAULT_DET_SIDE_LEN = 960
        const val DEFAULT_BOX_SCORE_THRESHOLD = 0.4f
        const val DEFAULT_BOX_THRESHOLD = 0.2f
        const val DEFAULT_UNCLIP_RATIO = 1.4f

        private const val DET_MODEL = "paddleocr/PP-OCRv6_tiny_det.onnx"
        private const val REC_MODEL = "paddleocr/PP-OCRv6_tiny_rec.onnx"
        private const val REC_DICTIONARY = "paddleocr/ppocrv6_tiny_dict.txt"
    }
}
