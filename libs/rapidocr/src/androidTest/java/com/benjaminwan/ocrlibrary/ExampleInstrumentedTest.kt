package com.benjaminwan.ocrlibrary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.benjaminwan.ocrlibrary.test", appContext.packageName)
    }

    @Test
    fun embeddedPaddleOcrV6RecognizesRenderedText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = Bitmap.createBitmap(960, 240, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            Canvas(input).apply {
                drawColor(Color.WHITE)
                drawText(
                    "PADDLE OCR 123",
                    40f,
                    155f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 112f
                        typeface = Typeface.DEFAULT_BOLD
                    },
                )
            }

            val result = PaddleOcrV6Engine(context).detect(input, output)

            assertTrue("Expected PP-OCRv6 to return at least one text block", result.textBlocks.isNotEmpty())
            assertTrue(
                "Expected a non-empty recognition result: ${result.strRes}",
                result.textBlocks.any { it.text.isNotBlank() },
            )
        } finally {
            input.recycle()
            output.recycle()
        }
    }
}
