package com.ohv.shared.api

/**
 * API 错误类型
 * 移植自 iOS APIError enum
 */
sealed class ApiError(message: String) : Exception(message) {
    object NotLoggedIn : ApiError("请先登录爱发电")
    object InvalidUrl : ApiError("无效的请求地址")
    object NoAudioUrl : ApiError("该内容暂无音频或无播放权限")
    object NetworkError : ApiError("网络连接失败，请稍后重试")
    data class HttpError(val code: Int) : ApiError("网络错误（$code）")
    data class ApiResponseError(val msg: String) : ApiError(msg)
}
