package com.oddin.oddsfeedsdk.api

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.InitException
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail
import com.oddin.oddsfeedsdk.schema.rest.v1.RAResponseCode
import mu.KotlinLogging
import java.util.*

interface BookmakerDetail {
    /**
     * Expiration date of access token
     */
    val expireAt: Date

    /**
     * Bookmaker id
     */
    val bookmakerId: Int

    /**
     * Virtual host for RabbitMQ connection
     */
    val virtualHost: String
}

class BookMakerDetailImpl(private val bookmakerDetail: RABookmakerDetail): BookmakerDetail {
    override val expireAt: Date
        get() = bookmakerDetail.expireAt.toGregorianCalendar().time
    override val bookmakerId: Int
        get() = bookmakerDetail.bookmakerId.toInt()
    override val virtualHost: String
        get() = bookmakerDetail.virtualHost
}

interface WhoAmIManager {
    val bookmakerDetail: BookmakerDetail?
    fun bookmakerDescription(): String
    suspend fun fetchBookmakerDetails()
}

private val logger = KotlinLogging.logger {}

class WhoAmIManagerImpl @Inject constructor(
    private val apiClient: ApiClient,
    private val config: OddsFeedConfiguration
) : WhoAmIManager {
    private var _bookmakerDetail: BookmakerDetail? = null

    override val bookmakerDetail: BookmakerDetail?
        get() = _bookmakerDetail

    override fun bookmakerDescription(): String {
        val bookmakerDetail = bookmakerDetail ?: throw InitException("missing bookmaker detail", null)
        val sdkNodeId = config.sdkNodeId ?: -1

        return "of-sdk-${bookmakerDetail.bookmakerId}-${sdkNodeId}"
    }

    override suspend fun fetchBookmakerDetails() {
        val bookmakerDetail = apiClient.fetchWhoAmI()

        logger.info { "Client id: ${bookmakerDetail.bookmakerId}" }
        validateBookmakerDetails(bookmakerDetail)
        val expirationTime = bookmakerDetail.expireAt.toGregorianCalendar().time

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, 7)
        if (cal.time.after(expirationTime)) {
            logger.warn { "Access token will expire soon (${bookmakerDetail.expireAt})" }
        }

        this._bookmakerDetail = BookMakerDetailImpl(bookmakerDetail)
    }

    private fun validateBookmakerDetails(RABookmakerDetail: RABookmakerDetail) {
        val errorMsg = when (RABookmakerDetail.responseCode) {
            RAResponseCode.OK -> null
            RAResponseCode.NOT_FOUND -> "Problem with validating access token. [${RABookmakerDetail.responseCode}]"
            RAResponseCode.FORBIDDEN -> "Access was denied. Access token is probably expired or invalid. [msg: ${RABookmakerDetail.responseCode}]"
            else -> "Failed to validate book maker details [${RABookmakerDetail.responseCode}]"
        }

        if (errorMsg != null) {
            throw IllegalStateException(errorMsg)
        }
    }
}