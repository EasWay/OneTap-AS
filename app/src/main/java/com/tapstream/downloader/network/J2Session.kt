package com.tapstream.downloader.network

/**
 * J2Session - Captured session data from J2Download
 * 
 * @param jwtAccessToken The OAuth2/JWT access token obtained from /api/auth/issue
 * @param cookieString All session cookies (including jx_session) required for authenticated requests
 * @param userAgent The matching User-Agent that solved the Turnstile challenge
 */
data class J2Session(
    val jwtAccessToken: String,
    val cookieString: String,
    val userAgent: String
)
