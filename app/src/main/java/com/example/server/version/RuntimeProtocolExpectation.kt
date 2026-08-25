package com.example.server.version

data class RuntimeProtocolExpectation(
    val expectedProtocols: List<Int>,
    val selectedBedrockVersion: String,
    val source: String,
)
