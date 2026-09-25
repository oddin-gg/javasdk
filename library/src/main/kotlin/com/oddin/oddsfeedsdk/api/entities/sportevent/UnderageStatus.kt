package com.oddin.oddsfeedsdk.api.entities.sportevent

/**
 * Whether a competitor or player is flagged as underage. The feed encodes it
 * as -1 (unknown), 0 (no) and 1 (yes); anything else reads as [UNKNOWN].
 */
enum class UnderageStatus(val value: Int) {
    UNKNOWN(-1),
    NO(0),
    YES(1);

    companion object {
        @JvmStatic
        fun fromValue(value: Int?): UnderageStatus = when (value) {
            0 -> NO
            1 -> YES
            else -> UNKNOWN
        }
    }
}
