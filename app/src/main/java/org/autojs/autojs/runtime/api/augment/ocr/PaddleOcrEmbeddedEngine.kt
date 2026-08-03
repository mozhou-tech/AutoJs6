package org.autojs.autojs.runtime.api.augment.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.benjaminwan.ocrlibrary.PaddleOcrV6Engine
import com.benjaminwan.ocrlibrary.TextBlock
import org.autojs.plugin.paddle.ocr.api.OcrOptions

/** In-process PP-OCRv6 tiny implementation backed by ONNX Runtime Android. */
internal object PaddleOcrEmbeddedEngine {

    @Volatile
    private var engine: PaddleOcrV6Engine? = null

    private fun getEngine(context: Context, options: OcrOptions): PaddleOcrV6Engine {
        engine?.let { return it }
        return synchronized(this) {
            engine ?: PaddleOcrV6Engine(
                context.applicationContext,
                options.cpuThreadNum.coerceAtLeast(1),
            ).also { engine = it }
        }
    }

    fun recognizeText(context: Context, bitmap: Bitmap, options: OcrOptions): List<String> =
        detect(context, bitmap, options).map { it.text }

    fun detect(context: Context, bitmap: Bitmap, options: OcrOptions): List<EmbeddedOcrResult> {
        require(!bitmap.isRecycled) { "Cannot run OCR on a recycled bitmap" }
        val output = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            val result = getEngine(context, options).detect(
                input = bitmap,
                output = output,
                maxSideLen = options.detLongSize.takeIf { it > 0 }
                    ?: PaddleOcrV6Engine.DEFAULT_DET_SIDE_LEN,
            )
            val threshold = options.scoreThreshold.takeIf { it >= 0f } ?: 0f
            result.textBlocks.asSequence()
                .map(::toEmbeddedResult)
                .filter { it.text.isNotBlank() && it.confidence >= threshold }
                .toList()
        } finally {
            output.recycle()
        }
    }

    private fun toEmbeddedResult(block: TextBlock): EmbeddedOcrResult {
        val left = block.boxPoint.minOfOrNull { it.x } ?: 0
        val top = block.boxPoint.minOfOrNull { it.y } ?: 0
        val right = block.boxPoint.maxOfOrNull { it.x } ?: left
        val bottom = block.boxPoint.maxOfOrNull { it.y } ?: top
        val confidence = block.charScores
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: block.boxScore
        return EmbeddedOcrResult(
            text = block.text,
            confidence = confidence,
            bounds = Rect(left, top, right, bottom),
        )
    }

    data class EmbeddedOcrResult(
        val text: String,
        val confidence: Float,
        val bounds: Rect,
    )
}
