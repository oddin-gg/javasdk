package com.oddin.oddsfeedsdk.exceptions

import com.oddin.oddsfeedsdk.schema.rest.v1.RAError

abstract class OddsFeedSdkException(message: String?, e: Exception?) : RuntimeException(message, e)

class GenericOdsFeedException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class UnsupportedUrnFormatException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class ApiException(message: String?, error: RAError? = null, e: Exception? = null) :
    OddsFeedSdkException(if (error == null) message else "${error.message} - ${error.action}", e)

class InitException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class UnsupportedMessageInterestCombination(message: String?, e: Exception?) : OddsFeedSdkException(message, e)

class ItemNotFoundException(message: String?, e: Exception?) : OddsFeedSdkException(message, e)