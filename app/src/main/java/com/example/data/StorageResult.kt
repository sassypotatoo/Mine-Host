package com.example.data

sealed class StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>()

    data class Recovered<T>(
        val value: T,
        val warning: String
    ) : StorageResult<T>()

    data object Missing : StorageResult<Nothing>()

    data class Corrupt(
        val warning: String,
        val cause: Throwable? = null
    ) : StorageResult<Nothing>()

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : StorageResult<Nothing>()

    val isSuccess: Boolean get() = this is Success || this is Recovered
    val isFailure: Boolean get() = this is Failure || this is Corrupt
}
