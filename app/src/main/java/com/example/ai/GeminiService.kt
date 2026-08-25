package com.example.ai

/**
 * Legacy compatibility file. Real AI transport now lives in [AiAssistantService]
 * and accepts only an encrypted user key or an authenticated backend proxy.
 */
@Deprecated("Use AiAssistantService")
typealias GeminiService = AiAssistantService
