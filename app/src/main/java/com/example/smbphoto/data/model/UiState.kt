package com.example.smbphoto.data.model

import android.net.Uri

/**
 * UI 状态封装
 */
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>()
    object Empty : UiState<Nothing>()
}

/**
 * 保存图片到本地相册的结果
 */
sealed class SaveResult {
    data class Success(val uri: Uri, val displayName: String) : SaveResult()
    data class Failure(val message: String) : SaveResult()
}
