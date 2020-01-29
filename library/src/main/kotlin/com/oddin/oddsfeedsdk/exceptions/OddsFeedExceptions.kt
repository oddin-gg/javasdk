package com.oddin.oddsfeedsdk.exceptions

abstract class OddsFeedSdkException(message: String?, e: Exception?) : RuntimeException(message, e)

class GenericOdsFeedException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class UnsupportedUrnFormatException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class ApiException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class InitException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class UnsupportedMessageInterestCombination(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class ItemNotFoundException (message: String?, e: Exception?) : OddsFeedSdkException(message, e)