package com.tapstream.downloader.domain.usecase

import com.tapstream.downloader.utils.UrlValidator
import javax.inject.Inject

/**
 * Use case for validating video URLs
 */
class ValidateUrlUseCase @Inject constructor() {
    operator fun invoke(url: String?): UrlValidationResult {
        if (url.isNullOrBlank()) {
            return UrlValidationResult.Empty
        }
        
        val normalizedUrl = UrlValidator.normalizeUrl(url)
        return if (UrlValidator.isValidVideoUrl(normalizedUrl)) {
            UrlValidationResult.Valid(normalizedUrl)
        } else {
            UrlValidationResult.Invalid
        }
    }
}

sealed class UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult()
    object Invalid : UrlValidationResult()
    object Empty : UrlValidationResult()
}
