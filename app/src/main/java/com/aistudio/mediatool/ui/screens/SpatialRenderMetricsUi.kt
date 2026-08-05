package com.aistudio.mediatool.ui.screens

import com.aistudio.mediatool.core.spatial.SpatialRenderMetrics

/**
 * Giá trị RMS hiển thị cho người dùng là phần nội dung chính sau cân loudness,
 * không tính tail reverb kéo dài ở cuối tệp.
 */
val SpatialRenderMetrics.rmsDbfs: Float
    get() = outputMainRmsAfterGainDbfs
