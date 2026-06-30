package com.example.core.ml

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

sealed class SeparationState {
    data class Progress(val value: Float) : SeparationState()
    data class Success(
        val vocalsFile: File, 
        val musicFile: File, // Use for beat in 2-stem mode. Wait we can use this for other stems?
        val drumsFile: File? = null,
        val bassFile: File? = null,
        val otherFile: File? = null
    ) : SeparationState()
}

class AudioSeparator(private val context: Context, private val modelFile: File) {

    companion object {
        private const val TAG = "AudioSeparator"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        // Chunk size: Demucs ONNX model expects EXACTLY 343980 frames (approx 7.8 seconds).
        private const val CHUNK_FRAMES = 343980
        private const val BYTES_PER_FRAME = CHANNELS * 2 // s16le = 2 bytes / sample
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                val logFile = File(downloadsDir, "audio_separator_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logMessage = "[$timestamp] $message\n"
                val writer = FileWriter(logFile, true)
                writer.append(logMessage)
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log to file: ${e.message}", e)
        }
    }

    private fun logError(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                val logFile = File(downloadsDir, "audio_separator_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logMessage = "[$timestamp] ERROR: $message | Exception: ${error?.message}\n${error?.stackTraceToString() ?: ""}\n"
                val writer = FileWriter(logFile, true)
                writer.append(logMessage)
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log to file: ${e.message}", e)
        }
    }

    suspend fun separate(inputUri: Uri): Flow<SeparationState> = flow {
    	emit(SeparationState.Progress(0.01f)) // Start

        // 1. Prepare temp files
        val cacheDir = context.cacheDir
        val tempRawMix = File(cacheDir, "mix.raw")
        val tempRawVocals = File(cacheDir, "vocals.raw")
        val tempRawMusic = File(cacheDir, "music.raw")
        val tempRawDrums = File(cacheDir, "drums.raw")
        val tempRawBass = File(cacheDir, "bass.raw")
        val tempRawOther = File(cacheDir, "other.raw")
        
        val is4StemMode = com.example.core.SettingsManager.getStemModeIndex(context) == 1
        
        log("Bắt đầu xử lý tách audio. Model: ${modelFile.absolutePath}, InputUri: $inputUri")
        try {
            // Delete previous log file to start fresh if needed, or keep appending. 
            // We append. Let's just log start.
            
            tempRawMix.delete()
            tempRawVocals.delete()
            tempRawMusic.delete()
            tempRawDrums.delete()
            tempRawBass.delete()
            tempRawOther.delete()

            // 2. Decode input to raw s16 PCM (s16le)
            emit(SeparationState.Progress(0.05f))
            val inputPath = com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, inputUri)
            val decodeCmd = "-y -i \"$inputPath\" -f s16le -ac $CHANNELS -ar $SAMPLE_RATE \"${tempRawMix.absolutePath}\""
            log("Chạy FFmpeg decode: $decodeCmd")
            val decodeSession = FFmpegKit.execute(decodeCmd)
            
            if (!ReturnCode.isSuccess(decodeSession.returnCode)) {
                val logs = decodeSession.allLogsAsString
                logError("Lỗi giải mã. Logs: $logs")
                throw Exception("Lỗi khi giải mã audio (FFmpeg). Chi tiết: $logs")
            }
            log("FFmpeg decode thành công. Size: ${tempRawMix.length()} bytes")

            emit(SeparationState.Progress(0.1f)) // Decode complete

            // 3. Process with ONNX
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) // ORT_ENABLE_ALL
                
                // 1. Áp dụng cấu hình Đa luồng (Threads)
                val numThreads = com.example.core.SettingsManager.getNumThreads(context)
                log("Cấu hình ONNX: Đặt số luồng Intra-op = $numThreads")
                setIntraOpNumThreads(numThreads) 

                // 2. Áp dụng cấu hình Bộ tăng tốc phần cứng (NNAPI)
                val hwIndex = com.example.core.SettingsManager.getHardwareAccelIndex(context)
                try {
                    when (hwIndex) {
                        1 -> {
                            log("Cấu hình ONNX: Đang cố bật NNAPI Hardware Acceleration (cố gắng ép FP16)...")
                            try {
                                val method = this::class.java.getMethod("addNnapi", Int::class.javaPrimitiveType)
                                method.invoke(this, 1) // 1 = USE_FP16
                                log("Bật NNAPI với cờ USE_FP16 thành công.")
                            } catch (e: Exception) {
                                log("Không ép được cờ FP16 (bỏ qua), sử dụng addNnapi() chuẩn.")
                                addNnapi() // Standard fallback
                            }
                        }
                        2 -> {
                            log("Cấu hình ONNX: Bật XNNPACK Execution Provider (nếu có hỗ trợ)...")
                            // Note: We use addNnapi in standard onnxruntime-android. 
                            // XNNPACK is sometimes supported. The Java API sometimes lacks addXnnpack() natively in this version
                            // but adding it via config map works if provided, or we can just try to configure flags.
                            // We will just catch the exception if the API doesn't exist.
                            try {
                                val method = this::class.java.getMethod("addXnnpack", MutableMap::class.java)
                                val xnnpackOptions = java.util.HashMap<String, String>()
                                method.invoke(this, xnnpackOptions)
                            } catch (e: Exception) {
                                log("addXnnpack không khả dụng trên phiên bản ONNX này qua reflection. Chạy CPU thường thay thế.")
                                // Fallback
                            }
                        }
                        else -> {
                            log("Cấu hình ONNX: Chạy bằng CPU mặc định.")
                        }
                    }
                } catch (e: Exception) {
                    logError("Lỗi khi cấu hình Hardware Acceleration (thiết bị có thể không hỗ trợ HW này). Fallback về CPU: ${e.message}")
                }
            }
            
            val session = env.createSession(modelFile.absolutePath, sessionOptions)

            try {
                log("Khởi tạo model ONNX thành công.")
                val totalBytes = tempRawMix.length()
                val totalFrames = totalBytes / BYTES_PER_FRAME
                log("Tổng số frames audio gốc: $totalFrames (Total bytes: $totalBytes)")
                
                // --- TÍNH TOÁN GLOBAL MEAN VÀ STD (One-pass Welford Algorithm) ---
                log("Bắt đầu tính toán Global Mean & Std cho toàn bộ file audio...")
                var welfordMean = 0.0
                var welfordM2 = 0.0
                var globalFramesCount = 0L

                val isStats = DataInputStream(java.io.BufferedInputStream(FileInputStream(tempRawMix), 524288))
                val statsBuffer = ByteArray(8192 * CHANNELS * 2) 

                while (true) {
                    val readBytes = isStats.read(statsBuffer)
                    if (readBytes <= 0) break
                    val frames = readBytes / (CHANNELS * 2)
                    val shortBuffer = ByteBuffer.wrap(statsBuffer, 0, readBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (f in 0 until frames) {
                        val left = shortBuffer.get(f * CHANNELS + 0) / 32768.0f
                        val right = shortBuffer.get(f * CHANNELS + 1) / 32768.0f
                        val mono = (left + right) / 2.0f
                        
                        globalFramesCount++
                        val delta = mono - welfordMean
                        welfordMean += delta / globalFramesCount
                        val delta2 = mono - welfordMean
                        welfordM2 += delta * delta2
                    }
                }
                isStats.close()

                val globalVariance = if (globalFramesCount > 0) welfordM2 / globalFramesCount else 0.0
                val std = Math.max(1e-4, Math.sqrt(globalVariance)).toFloat()
                val mean = welfordMean.toFloat()

                log("Tính toán xong: Global Mean = $mean, Global Std = $std")
                // -------------------------------------------------------------
                
                var processedFrames = 0L

                val bSize = 524288 // 512KB buffer I/O
                val inputStream = DataInputStream(java.io.BufferedInputStream(FileInputStream(tempRawMix), bSize))
                val vocalsOut = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawVocals), bSize))
                val musicOut = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawMusic), bSize))
                val drumsOut = if (is4StemMode) DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawDrums), bSize)) else null
                val bassOut = if (is4StemMode) DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawBass), bSize)) else null
                val otherOut = if (is4StemMode) DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawOther), bSize)) else null

                // Overlap 25% to smooth cuts
                val overlapSize = (CHUNK_FRAMES * 0.25f).toInt()
                val stepSize = CHUNK_FRAMES - overlapSize

                val chunkBufferBytes = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
                val chunkBufferFloat = FloatArray(CHUNK_FRAMES * CHANNELS)
                
                // Buffer lưu các đoạn âm thanh nối (Overlap)
                val outVocalsOverlap = FloatArray(overlapSize * CHANNELS)
                val outBeatOverlap = FloatArray(overlapSize * CHANNELS) // Using for music if 2 stems
                val outDrumsOverlap = if (is4StemMode) FloatArray(overlapSize * CHANNELS) else null
                val outBassOverlap = if (is4StemMode) FloatArray(overlapSize * CHANNELS) else null
                val outOtherOverlap = if (is4StemMode) FloatArray(overlapSize * CHANNELS) else null

                val vocalsMerged = ByteBuffer.allocate(CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
                val vocalsMergedShort = vocalsMerged.asShortBuffer()
                val musicMerged = ByteBuffer.allocate(CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
                val musicMergedShort = musicMerged.asShortBuffer()
                val drumsMerged = if (is4StemMode) ByteBuffer.allocate(CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN) else null
                val drumsMergedShort = drumsMerged?.asShortBuffer()
                val bassMerged = if (is4StemMode) ByteBuffer.allocate(CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN) else null
                val bassMergedShort = bassMerged?.asShortBuffer()
                val otherMerged = if (is4StemMode) ByteBuffer.allocate(CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN) else null
                val otherMergedShort = otherMerged?.asShortBuffer()

                val inputName = session.inputNames.iterator().next()
                val inInfo = session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
                val expectedShape = inInfo?.shape
                
                log("Input Expected Shape: ${expectedShape?.joinToString(", ") ?: "Unknown"}")
                
                var inShape = longArrayOf(1, CHANNELS.toLong(), CHUNK_FRAMES.toLong())
                var inCAxis = 1
                var inFAxis = 2
                
                if (expectedShape != null && expectedShape.size == 3 && expectedShape[1] > 100 && expectedShape[2] == 2L) {
                    inShape = longArrayOf(1, CHUNK_FRAMES.toLong(), CHANNELS.toLong())
                    inFAxis = 1
                    inCAxis = 2
                    log("CẢNH BÁO: Model Input Shape [1, frames, channels]. Đã tự xoay trục.")
                }

                val inStrides = LongArray(3)
                inStrides[2] = 1L
                inStrides[1] = inShape[2]
                inStrides[0] = inShape[1] * inShape[2]

                var isFirstChunk = true
                var chunkIndex = 0

                val pipelineMode = com.example.core.SettingsManager.getPipelineModeIndex(context)

                // Cấp phát Buffer bộ nhớ trực tiếp (Direct Memory) một lần duy nhất.
                // Nếu để trong vòng lặp while như trước đây, việc cấp phát Native ByteBuffer 
                // liên tục sẽ không được GC cọ dọn kịp thời, gây OOM/thủng RAM nghiêm trọng.
                val sharedInputBufferBytes = ByteBuffer.allocateDirect(CHANNELS * CHUNK_FRAMES * 4).order(ByteOrder.nativeOrder())
                val sharedInputBufferDirect = sharedInputBufferBytes.asFloatBuffer()

                kotlinx.coroutines.coroutineScope {
                    var prefetchDeferred: kotlinx.coroutines.Deferred<Pair<ByteArray, Int>>? = null

                    fun startPrefetch(isFirst: Boolean): kotlinx.coroutines.Deferred<Pair<ByteArray, Int>> {
                        return this@coroutineScope.async(kotlinx.coroutines.Dispatchers.IO) {
                            val framesToRead = if (isFirst) CHUNK_FRAMES else stepSize
                            val bytesToRead = framesToRead * BYTES_PER_FRAME
                            val buffer = ByteArray(bytesToRead)
                            var readSize = 0
                            while (readSize < bytesToRead) {
                                val read = inputStream.read(buffer, readSize, bytesToRead - readSize)
                                if (read == -1) break
                                readSize += read
                            }
                            Pair(buffer, readSize)
                        }
                    }

                    if (pipelineMode == 1) {
                        log("Chế độ Thực nghiệm: Bật Pipeline Producer-Consumer (Đọc trước dữ liệu IO)")
                        prefetchDeferred = startPrefetch(true)
                    }

                    while (kotlin.coroutines.coroutineContext.isActive) {
                        // Kích hoạt dọn dẹp bộ nhớ an toàn (Aggressive GC) mỗi vòng lặp
                        System.gc()
                        Runtime.getRuntime().gc()

                        val framesToRead = if (isFirstChunk) CHUNK_FRAMES else stepSize
                        val bytesToRead = framesToRead * BYTES_PER_FRAME
                        
                        var bytesRead = 0
                        
                        if (pipelineMode == 1 && prefetchDeferred != null) {
                            val prefetched = prefetchDeferred!!.await()
                            System.arraycopy(prefetched.first, 0, chunkBufferBytes, 0, prefetched.second)
                            bytesRead = prefetched.second
                            
                            // Bắt đầu prefetch cho chunk tiếp theo nếu chunk này chưa phải cuối
                            if (bytesRead == bytesToRead) {
                                prefetchDeferred = startPrefetch(false)
                            } else {
                                prefetchDeferred = null
                            }
                        } else {
                            // Chế độ Tuần tự chuẩn
                            while (bytesRead < bytesToRead) {
                                val read = inputStream.read(chunkBufferBytes, bytesRead, bytesToRead - bytesRead)
                                if (read == -1) break
                                bytesRead += read
                            }
                        }
                        
                        val actualFramesRead = bytesRead / BYTES_PER_FRAME
                    log("Chunk $chunkIndex: Đọc được $actualFramesRead frames ($bytesRead bytes)")

                    if (actualFramesRead == 0 && !isFirstChunk) {
                        log("EOF: Xả nốt đoạn overlap cuối cùng (size $overlapSize frames)")
                        // EOF: Xả nốt đoạn overlap cuối cùng.
                        vocalsMergedShort.clear()
                        musicMergedShort.clear()
                        drumsMergedShort?.clear()
                        bassMergedShort?.clear()
                        otherMergedShort?.clear()
                        
                        for(i in 0 until overlapSize * CHANNELS) {
                            val frameIdxInOverlap = i / CHANNELS
                            val phase = ((overlapSize - frameIdxInOverlap).toFloat() / overlapSize) * (Math.PI / 2.0)
                            val rightWeight = Math.sin(phase).toFloat()
                            val invWeight = if (rightWeight > 0.001f) 1.0f / rightWeight else 1.0f
                            
                            val v_val = outVocalsOverlap[i] * invWeight
                            val m_val = outBeatOverlap[i] * invWeight
                            
                            val vShort = (v_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                            val mShort = (m_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                            
                            vocalsMergedShort.put(vShort)
                            musicMergedShort.put(mShort)
                            
                            if (is4StemMode) {
                                val d_val = outDrumsOverlap!![i] * invWeight
                                val b_val = outBassOverlap!![i] * invWeight
                                val o_val = outOtherOverlap!![i] * invWeight
                                drumsMergedShort!!.put((d_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
                                bassMergedShort!!.put((b_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
                                otherMergedShort!!.put((o_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
                            }
                        }
                        vocalsOut.write(vocalsMerged.array(), 0, overlapSize * BYTES_PER_FRAME)
                        musicOut.write(musicMerged.array(), 0, overlapSize * BYTES_PER_FRAME)
                        if (is4StemMode) {
                            drumsOut!!.write(drumsMerged!!.array(), 0, overlapSize * BYTES_PER_FRAME)
                            bassOut!!.write(bassMerged!!.array(), 0, overlapSize * BYTES_PER_FRAME)
                            otherOut!!.write(otherMerged!!.array(), 0, overlapSize * BYTES_PER_FRAME)
                        }
                        break
                    }
                    if (actualFramesRead == 0 && isFirstChunk) break // File rỗng

                    if (!isFirstChunk) {
                        // Dịch dữ liệu cũ sang trái
                        System.arraycopy(chunkBufferFloat, stepSize * CHANNELS, chunkBufferFloat, 0, overlapSize * CHANNELS)
                    }

                    val shortBuffer = ByteBuffer.wrap(chunkBufferBytes, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()

                    val offset = if (isFirstChunk) 0 else overlapSize * CHANNELS
                    for (i in 0 until actualFramesRead * CHANNELS) {
                        chunkBufferFloat[offset + i] = shortBuffer.get(i) / 32768.0f
                    }

                    val validFramesInChunk = if (isFirstChunk) actualFramesRead else (overlapSize + actualFramesRead)

                    val isFullRead = (isFirstChunk && actualFramesRead == CHUNK_FRAMES) || (!isFirstChunk && actualFramesRead == stepSize)
                    val framesToWrite = if (isFullRead) stepSize else validFramesInChunk
                    
                    val inputName = session.inputNames.iterator().next()
                    val expectedShape = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
                    
                    var inShape = longArrayOf(1, CHANNELS.toLong(), CHUNK_FRAMES.toLong())
                    var inCAxis = 1
                    var inFAxis = 2
                    
                    if (expectedShape != null && expectedShape.size == 3 && expectedShape[1] > 100 && expectedShape[2] == 2L) {
                        inShape = longArrayOf(1, CHUNK_FRAMES.toLong(), CHANNELS.toLong())
                        inFAxis = 1
                        inCAxis = 2
                    }

                    val inStrides = LongArray(3)
                    inStrides[2] = 1L
                    inStrides[1] = inShape[2]
                    inStrides[0] = inShape[1] * inShape[2]

                    for (ch in 0 until CHANNELS) {
                        for (f in 0 until validFramesInChunk) {
                            val idx = ch * inStrides[inCAxis] + f * inStrides[inFAxis]
                            val rawVal = chunkBufferFloat[f * CHANNELS + ch]
                            val normVal = (rawVal - mean) / std // APPLY NORM
                            sharedInputBufferDirect.put(idx.toInt(), normVal)
                        }
                    }
                    sharedInputBufferDirect.rewind()

                    val inputTensor = OnnxTensor.createTensor(env, sharedInputBufferDirect, inShape)
                    var result: ai.onnxruntime.OrtSession.Result? = null
                    
                    try {
                        val inputMap = mapOf(inputName to inputTensor)

                        // Inference
                        log("Chunk $chunkIndex: Bắt đầu Inference ONNX...")
                        result = session.run(inputMap)
                        log("Chunk $chunkIndex: ONNX Inference hoàn tất.")
                        
                        val outOnnxTensor = result.get(0) as OnnxTensor
                        val outShape = (outOnnxTensor.info as ai.onnxruntime.TensorInfo).shape
                        
                        var sAxis = 1
                        var cAxis = 2
                        var fAxis = 3
                        
                        if (outShape.size == 4 && outShape[2] > 100 && outShape[3] == 2L) {
                            cAxis = 3
                            fAxis = 2
                        }

                        val outStrides = LongArray(outShape.size)
                        var currentStr = 1L
                        for(i in outShape.indices.reversed()) {
                            outStrides[i] = currentStr
                            currentStr *= outShape[i]
                        }

                        val outBuffer = outOnnxTensor.floatBuffer
                        val sourceCount = outShape[sAxis].toInt()
                        val vocalIdx = if (sourceCount >= 4) 3 else sourceCount - 1
                        
                        if (chunkIndex == 0) {
                            try {
                                val inputSample = FloatArray(5)
                                for(i in 0..4) inputSample[i] = chunkBufferFloat[i * CHANNELS]
                                
                                val vocSample = FloatArray(5)
                                val drumSample = FloatArray(5)
                                for (i in 0..4) {
                                    val vOffset = vocalIdx * outStrides[sAxis] + 0 * outStrides[cAxis] + i * outStrides[fAxis]
                                    vocSample[i] = outBuffer.get(vOffset.toInt()) * std + mean
                                    
                                    val dOffset = 0 * outStrides[sAxis] + 0 * outStrides[cAxis] + i * outStrides[fAxis]
                                    drumSample[i] = outBuffer.get(dOffset.toInt()) * std + mean
                                }

                                val inStr = inputSample.joinToString(", ")
                                val vocStr = vocSample.joinToString(", ")
                                val drumStr = drumSample.joinToString(", ")
                                
                                log("==== KIỂM TRA DỮ LIỆU CHUNK ====")
                                log("1. INPUT GỐC: $inStr")
                                log("2. VOCAL OUT: $vocStr")
                                log("3. DRUMS OUT: $drumStr")
                                log("================================")
                            } catch (e: Exception) {
                                logError("Lỗi khi soi dữ liệu", e)
                            }
                        }

                        vocalsMergedShort.clear()
                        musicMergedShort.clear()
                        drumsMergedShort?.clear()
                        bassMergedShort?.clear()
                        otherMergedShort?.clear()

                        for (f in 0 until framesToWrite) {
                            for (ch in 0 until CHANNELS) {
                                val vOffset = vocalIdx * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]
                                var v_val = outBuffer.get(vOffset.toInt())
                                var m_val = 0f
                                
                                var d_val = 0f
                                var b_val = 0f
                                var o_val = 0f
                                
                                if (is4StemMode && sourceCount >= 4) {
                                    d_val = outBuffer.get((0 * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]).toInt())
                                    b_val = outBuffer.get((1 * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]).toInt())
                                    o_val = outBuffer.get((2 * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]).toInt())
                                    m_val = d_val + b_val + o_val
                                } else {
                                    for (s in 0 until sourceCount) {
                                        if (s != vocalIdx) {
                                            val sOffset = s * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]
                                            m_val += outBuffer.get(sOffset.toInt())
                                        }
                                    }
                                }
                                
                                // UN-NORMALIZE
                                v_val = v_val * std + mean
                                m_val = m_val * std + mean
                                if (is4StemMode) {
                                    d_val = d_val * std + mean
                                    b_val = b_val * std + mean
                                    o_val = o_val * std + mean
                                }
                                
                                // Crossfade Overlap-add (Equal-Power Sine Window)
                                if (f < overlapSize && !isFirstChunk) {
                                    val phase = (f.toFloat() / overlapSize) * (Math.PI / 2.0)
                                    val weight = Math.sin(phase).toFloat()
                                    v_val = v_val * weight + outVocalsOverlap[f * CHANNELS + ch]
                                    m_val = m_val * weight + outBeatOverlap[f * CHANNELS + ch]
                                    
                                    if (is4StemMode) {
                                        d_val = d_val * weight + outDrumsOverlap!![f * CHANNELS + ch]
                                        b_val = b_val * weight + outBassOverlap!![f * CHANNELS + ch]
                                        o_val = o_val * weight + outOtherOverlap!![f * CHANNELS + ch]
                                    }
                                }

                                // Chuyển về Int16 Interleaved
                                val vShort = (v_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                                val mShort = (m_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                                
                                vocalsMergedShort.put(vShort)
                                musicMergedShort.put(mShort)
                                
                                if (is4StemMode) {
                                    drumsMergedShort!!.put((d_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
                                    bassMergedShort!!.put((b_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
                                    otherMergedShort!!.put((o_val * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
                                }
                            }
                        }

                        vocalsOut.write(vocalsMerged.array(), 0, framesToWrite * BYTES_PER_FRAME)
                        musicOut.write(musicMerged.array(), 0, framesToWrite * BYTES_PER_FRAME)
                        if (is4StemMode) {
                            drumsOut!!.write(drumsMerged!!.array(), 0, framesToWrite * BYTES_PER_FRAME)
                            bassOut!!.write(bassMerged!!.array(), 0, framesToWrite * BYTES_PER_FRAME)
                            otherOut!!.write(otherMerged!!.array(), 0, framesToWrite * BYTES_PER_FRAME)
                        }
                        log("Chunk $chunkIndex: Ghi $framesToWrite frames ra file.")
                        
                        // Lưu Overlap cho chunk kế tiếp
                        if (isFullRead) {
                            for (f in framesToWrite until CHUNK_FRAMES) {
                                for (ch in 0 until CHANNELS) {
                                    val vOffset = vocalIdx * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]
                                    var v_val = outBuffer.get(vOffset.toInt())
                                    var m_val = 0f
                                    
                                    var d_val = 0f
                                    var b_val = 0f
                                    var o_val = 0f
                                    if (is4StemMode && sourceCount >= 4) {
                                        d_val = outBuffer.get((0 * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]).toInt())
                                        b_val = outBuffer.get((1 * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]).toInt())
                                        o_val = outBuffer.get((2 * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]).toInt())
                                        m_val = d_val + b_val + o_val
                                    } else {
                                        for (s in 0 until sourceCount) {
                                            if (s != vocalIdx) {
                                                val sOffset = s * outStrides[sAxis] + ch * outStrides[cAxis] + f * outStrides[fAxis]
                                                m_val += outBuffer.get(sOffset.toInt())
                                            }
                                        }
                                    }
                                    
                                    // UN-NORMALIZE
                                    v_val = v_val * std + mean
                                    m_val = m_val * std + mean
                                    if (is4StemMode) {
                                        d_val = d_val * std + mean
                                        b_val = b_val * std + mean
                                        o_val = o_val * std + mean
                                    }

                                    val phase = ((CHUNK_FRAMES - f).toFloat() / overlapSize) * (Math.PI / 2.0)
                                    val rightWeight = Math.sin(phase).toFloat()
                                    v_val *= rightWeight
                                    m_val *= rightWeight
                                    
                                    val overIdx = f - framesToWrite
                                    outVocalsOverlap[overIdx * CHANNELS + ch] = v_val
                                    outBeatOverlap[overIdx * CHANNELS + ch] = m_val
                                    
                                    if (is4StemMode) {
                                        d_val *= rightWeight
                                        b_val *= rightWeight
                                        o_val *= rightWeight
                                        outDrumsOverlap!![overIdx * CHANNELS + ch] = d_val
                                        outBassOverlap!![overIdx * CHANNELS + ch] = b_val
                                        outOtherOverlap!![overIdx * CHANNELS + ch] = o_val
                                    }
                                }
                            }
                        }
                    } finally {
                        result?.close()
                        inputTensor.close()
                    }

                    processedFrames += actualFramesRead
                    val progressRatio = if (totalFrames > 0) processedFrames.toFloat() / totalFrames.toFloat() else 1.0f
                    val progress = 0.12f + 0.78f * progressRatio
                    emit(SeparationState.Progress(progress.coerceAtMost(0.88f)))
                    
                    isFirstChunk = false
                    chunkIndex++
                    
                    if (!isFullRead) {
                        log("Hoàn tất duyệt file ở frame cuối cùng.")
                        break
                    }
                }
                } // Đóng coroutineScope

                inputStream.close()
                vocalsOut.close()
                musicOut.close()
                if (is4StemMode) {
                    drumsOut?.close()
                    bassOut?.close()
                    otherOut?.close()
                }

            } catch (e: Throwable) {
                logError("Lỗi nghiêm trọng (OOM hoặc Crash) khi tách: ${e.message}", e)
                throw Exception("Lỗi khi xử lý mô hình AI: ${e.message}", e)
            } finally {
                session.close()
            }

            emit(SeparationState.Progress(0.9f)) // Encoding 

            // 4. Encode raw PCM back to selected format
            val ext = com.example.core.SettingsManager.getAudioFormatExt(context)
            val codecArg = com.example.core.SettingsManager.getAudioCodecArg(context)
            val isLossless = com.example.core.SettingsManager.isAudioLossless(context)
            
            // Cannot use '-c:a copy' from raw PCM for lossy formats. Fallback to 320k if lossless selected for mp3/m4a.
            val bitrateArg = if (isLossless) {
                if (ext == "wav" || ext == "flac") "" else "-b:a 320k"
            } else {
                com.example.core.SettingsManager.getAudioBitrateArg(context)
            }

            val outVocals = File(context.filesDir, "vocals_${System.currentTimeMillis()}.$ext")
            val outMusic = File(context.filesDir, "music_${System.currentTimeMillis()}.$ext")
            var outDrums: File? = null
            var outBass: File? = null
            var outOther: File? = null

            log("Bắt đầu Encode raw PCM sang $ext.")
            val encVocalCmd = "-y -f s16le -ac $CHANNELS -ar $SAMPLE_RATE -i \"${tempRawVocals.absolutePath}\" $codecArg $bitrateArg \"${outVocals.absolutePath}\""
            val encMusicCmd = "-y -f s16le -ac $CHANNELS -ar $SAMPLE_RATE -i \"${tempRawMusic.absolutePath}\" $codecArg $bitrateArg \"${outMusic.absolutePath}\""

            val res1 = FFmpegKit.execute(encVocalCmd)
            val res2 = FFmpegKit.execute(encMusicCmd)

            if (!ReturnCode.isSuccess(res1.returnCode) || !ReturnCode.isSuccess(res2.returnCode)) {
                logError("Lỗi export FFmpeg - Vocals: ${res1.allLogsAsString}")
                logError("Lỗi export FFmpeg - Music: ${res2.allLogsAsString}")
                throw Exception("Lỗi khi xuất file mp3")
            }
            
            if (is4StemMode) {
                outDrums = File(context.filesDir, "drums_${System.currentTimeMillis()}.$ext")
                outBass = File(context.filesDir, "bass_${System.currentTimeMillis()}.$ext")
                outOther = File(context.filesDir, "other_${System.currentTimeMillis()}.$ext")
                
                val encDrumsCmd = "-y -f s16le -ac $CHANNELS -ar $SAMPLE_RATE -i \"${tempRawDrums.absolutePath}\" $codecArg $bitrateArg \"${outDrums.absolutePath}\""
                val encBassCmd = "-y -f s16le -ac $CHANNELS -ar $SAMPLE_RATE -i \"${tempRawBass.absolutePath}\" $codecArg $bitrateArg \"${outBass.absolutePath}\""
                val encOtherCmd = "-y -f s16le -ac $CHANNELS -ar $SAMPLE_RATE -i \"${tempRawOther.absolutePath}\" $codecArg $bitrateArg \"${outOther.absolutePath}\""
                
                val resD = FFmpegKit.execute(encDrumsCmd)
                val resB = FFmpegKit.execute(encBassCmd)
                val resO = FFmpegKit.execute(encOtherCmd)
                
                if (!ReturnCode.isSuccess(resD.returnCode) || !ReturnCode.isSuccess(resB.returnCode) || !ReturnCode.isSuccess(resO.returnCode)) {
                    throw Exception("Lỗi khi xuất file $ext (4 stems)")
                }
            }
            
            log("Hoàn tất tách audio. Vocals: ${outVocals.absolutePath}, Music: ${outMusic.absolutePath}")

            emit(SeparationState.Progress(1.0f))
            emit(SeparationState.Success(outVocals, outMusic, outDrums, outBass, outOther))

        } finally {
            // 5. Cleanup
            tempRawMix.delete()
            tempRawVocals.delete()
            tempRawMusic.delete()
            tempRawDrums.delete()
            tempRawBass.delete()
            tempRawOther.delete()
        }
    }.flowOn(Dispatchers.IO)
}
