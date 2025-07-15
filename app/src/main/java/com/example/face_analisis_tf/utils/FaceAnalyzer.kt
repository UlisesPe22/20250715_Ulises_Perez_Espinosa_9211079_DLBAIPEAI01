package com.example.face_analisis_tf.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object FaceAnalyzer {

    private const val inputImageSize = 224 // For gender and age model input size
    private lateinit var genderInterpreter: Interpreter
    private lateinit var ageInterpreter: Interpreter
    private lateinit var emotionInterpreter: Interpreter

    /**
     * Initialize gender, age, and emotion interpreters.
     */
    fun initializeInterpreter(
        context: Context,
        genderModel: String = "deep_face_gender.tflite",
        ageModel: String = "age_deep.tflite",
        emotionModel: String = "emotion_deep.tflite"
    ) {
        genderInterpreter = Interpreter(loadModelFile(context, genderModel))
        ageInterpreter = Interpreter(loadModelFile(context, ageModel))
        emotionInterpreter = Interpreter(loadModelFile(context, emotionModel))
    }

    /** Load a TFLite file from assets into ByteBuffer */
    private fun loadModelFile(context: Context, filename: String): ByteBuffer {
        val fd = context.assets.openFd(filename)
        return FileInputStream(fd.fileDescriptor).use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** Processes image and returns list of predictions per face */
    suspend fun processImage(bitmap: Bitmap): List<String> {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )
        val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        return if (faces.isEmpty()) listOf("No face detected.")
        else faces.mapIndexed { i, face ->
            val result = predictAttributes(bitmap, face)
            "Face ${i+1}: $result"
        }
    }

    /** Annotates each detected face with rectangle and numeric label */
    suspend fun processImageWithAnnotations(bitmap: Bitmap): Bitmap {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )
        val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        if (faces.isEmpty()) return bitmap

        val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)

        val stroke = (minOf(bitmap.width, bitmap.height) * 0.005f).coerceAtLeast(2f)
        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = stroke
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.BLUE
            textSize = stroke * 12f
            isAntiAlias = true
        }

        faces.forEachIndexed { index, face ->
            val box = face.boundingBox
            val margin = (box.width() * 0.1f).toInt()
            val r = Rect(
                (box.left - margin).coerceAtLeast(0),
                (box.top - margin).coerceAtLeast(0),
                (box.right + margin).coerceAtMost(bitmap.width),
                (box.bottom + margin).coerceAtMost(bitmap.height)
            )
            canvas.drawRect(r, paint)
            canvas.drawText(
                "#${index+1}",
                r.left.toFloat(),
                (r.top - stroke * 2),
                textPaint
            )
        }
        return annotated
    }

    /** Predicts gender, age, and emotion (3-class), returns formatted string */
    private fun predictAttributes(bitmap: Bitmap, face: Face): String {
        // Crop with padding
        val pad = (face.boundingBox.width() * 0.2f).toInt()
        val rect = Rect(
            (face.boundingBox.left - pad).coerceAtLeast(0),
            (face.boundingBox.top - pad).coerceAtLeast(0),
            (face.boundingBox.right + pad).coerceAtMost(bitmap.width),
            (face.boundingBox.bottom + pad).coerceAtMost(bitmap.height)
        )
        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())

        // Prepare RGB buffer for gender & age (using original inputImageSize)
        // Normalized to [0,1] by dividing pixel values by 255f
        val rgbBuf = convertBitmapToByteBuffer(cropped.scale(inputImageSize, inputImageSize))

        // Gender
        val gOut = Array(1) { FloatArray(2) }
        genderInterpreter.run(rgbBuf, gOut)
        val gender = if (gOut[0][0] > gOut[0][1]) "Woman" else "Man"

        // Age ([1,101])
        val aOut = Array(1) { FloatArray(101) }
        ageInterpreter.run(rgbBuf, aOut)
        val ageNumeric = aOut[0].indices.maxByOrNull { aOut[0][it] } ?: 0

        // Age Mapping
        val ageCategory = when (ageNumeric) {
            in 0..17 -> "Underage"
            in 18..35 -> "Young Adult"
            in 36..64 -> "Adult"
            in 65..100 -> "Elder"
            else -> "Unknown"
        }

        // Prepare grayscale buffer for emotion (48x48)
        // Normalized to [0,1] grayscale float values
        val emoBuf = convertToGrayscale48x48(cropped)
        val eOut = Array(1) { FloatArray(7) }
        emotionInterpreter.run(emoBuf, eOut)
        val labels7 = listOf("Angry","Disgust","Fear","Happy","Sad","Surprise","Neutral")
        val idx7 = eOut[0].indices.maxByOrNull { eOut[0][it] } ?: 6
        val emotion7 = labels7[idx7]

        // Map 7 emotions to 3 classes
        val emotion3 = when (emotion7) {
            "Happy", "Surprise" -> "Positive"
            "Neutral" -> "Neutral"
            else -> "Negative"
        }

        Log.d("FaceAnalyzer", "Gender=$gender Age=$ageNumeric ($ageCategory) Emotion=$emotion3")
        return "$gender// Age group $ageCategory ($ageNumeric) , Mood $emotion3"
    }

    /** Converts Bitmap to normalized ByteBuffer (RGB) with values in [0,1] */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * inputImageSize * inputImageSize * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputImageSize * inputImageSize)
        bitmap.getPixels(pixels, 0, inputImageSize, 0, 0, inputImageSize, inputImageSize)
        pixels.forEach { p ->
            // Normalize each channel by dividing by 255f to get [0,1]
            buf.putFloat((p shr 16 and 0xFF) / 255f)
            buf.putFloat((p shr 8 and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    /** Converts Bitmap to normalized ByteBuffer for emotion (48x48 grayscale), values in [0,1] */
    private fun convertToGrayscale48x48(bitmap: Bitmap): ByteBuffer {
        val resized = bitmap.scale(48, 48)
        val buf = ByteBuffer.allocateDirect(4 * 48 * 48).order(ByteOrder.nativeOrder())
        val pixels = IntArray(48 * 48)
        resized.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        pixels.forEach { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            buf.putFloat(gray)
        }
        buf.rewind()
        return buf
    }
}
