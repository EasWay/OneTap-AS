package com.example.onetap.network

import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ErrorMapper {
    
    fun mapServerError(exception: HttpException): String {
        return when (exception.code()) {
            400 -> "Invalid request - please check the URL format"
            401 -> "Authentication failed"
            403 -> "Access forbidden - content may be private"
            404 -> "Content not found - URL may be invalid or expired"
            408 -> "Request timeout - server is taking too long to respond"
            429 -> "Too many requests - please wait a moment and try again"
            500 -> "Server error - please try again later"
            502 -> "Bad gateway - server is temporarily unavailable"
            503 -> "Service unavailable - server is temporarily down"
            504 -> "Gateway timeout - server is taking too long to respond"
            else -> "Server error (${exception.code()}) - please try again later"
        }
    }
    
    fun mapNetworkError(exception: Exception): String {
        return when (exception) {
            is SocketTimeoutException -> "Network timeout - check your internet connection"
            is ConnectException -> "Connection failed - check your internet connection"
            is UnknownHostException -> "No internet connection - please check your network"
            else -> "Network error - ${exception.message ?: "please check your connection"}"
        }
    }
}