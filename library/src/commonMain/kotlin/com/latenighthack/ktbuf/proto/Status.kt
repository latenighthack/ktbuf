package com.latenighthack.ktbuf.proto

enum class Codes(val value: Int) {
    OK(0),
    CANCELLED(1),
    UNKNOWN(2),
    INVALID_ARGUMENT(3),
    DEADLINE_EXCEEDED(4),
    NOT_FOUND(5),
    ALREADY_EXISTS(6),
    PERMISSION_DENIED(7),
    RESOURCE_EXHAUSTED(8),
    FAILED_PRECONDITION(9),
    ABORTED(10),
    OUT_OF_RANGE(11),
    UNIMPLEMENTED(12),
    INTERNAL(13),
    UNAVAILABLE(14),
    DATA_LOSS(15),
    UNAUTHENTICATED(16),

    CLIENT_TIMEOUT(100);

    fun retriable() = when (this) {
        UNKNOWN -> true
        CLIENT_TIMEOUT -> true
        DEADLINE_EXCEEDED -> true
        RESOURCE_EXHAUSTED -> true
        ABORTED -> true
        INTERNAL -> true
        UNAVAILABLE -> true
        else -> false
    }
}

data class Status(val code: Codes, val message: String) {
    companion object
}

// https://github.com/googleapis/googleapis/blob/master/google/rpc/code.proto
fun Status.Companion.fromHTTPCode(httpCode: Int, message: String?): Status {
    val code = when (httpCode) {
        200 -> Codes.OK
        400 -> {
            val lowerMessage = message?.lowercase()
            if (lowerMessage?.contains("failedprecondition") == true) {
                Codes.FAILED_PRECONDITION
            } else if (lowerMessage?.contains("outofrange") == true) {
                Codes.OUT_OF_RANGE
            } else {
                Codes.INVALID_ARGUMENT
            }
        }
        401 -> Codes.UNAUTHENTICATED
        403 -> Codes.PERMISSION_DENIED
        404 -> Codes.NOT_FOUND
        408 -> Codes.CLIENT_TIMEOUT
        409 -> {
            val lowerMessage = message?.lowercase()
            if (lowerMessage?.contains("alreadyexists") == true) {
                Codes.ALREADY_EXISTS
            } else {
                Codes.ABORTED
            }
        }
        429 -> Codes.RESOURCE_EXHAUSTED
        499 -> Codes.CANCELLED
        500 -> {
            val lowerMessage = message?.lowercase()
            if (lowerMessage?.contains("internal") == true) {
                Codes.INTERNAL
            } else if (lowerMessage?.contains("dataloss") == true) {
                Codes.DATA_LOSS
            } else {
                Codes.UNKNOWN
            }
        }
        501 -> Codes.UNIMPLEMENTED
        503 -> Codes.UNAVAILABLE
        504 -> Codes.DEADLINE_EXCEEDED

        else -> Codes.UNKNOWN
    }

    return Status(code, "$httpCode - ${message.orEmpty()}")
}

fun Status.Companion.fromWSCode(wsCode: Int, message: String?): Status {
    val code = when (wsCode) {
        1000 -> Codes.OK
        1001 -> Codes.UNAVAILABLE
        in 4000 until 5000 -> {
            val codeVal = wsCode - 4000
            Codes.values().firstOrNull { it.value == codeVal } ?: Codes.UNKNOWN
        }
        else -> Codes.UNKNOWN
    }

    return Status(code, message ?: "")
}
