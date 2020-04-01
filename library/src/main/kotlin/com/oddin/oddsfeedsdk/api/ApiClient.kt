package com.oddin.oddsfeedsdk.api

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.core.ResponseDeserializable
import com.github.kittinunf.fuel.coroutines.awaitObjectResult
import com.github.kittinunf.fuel.coroutines.awaitStringResult
import com.github.kittinunf.result.Result
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.DispatchManager
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.ApiException
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.bind.JAXBContext
import javax.xml.bind.Unmarshaller

interface ResponseWithCode {
    val responseCode: RAResponseCode
}

data class ApiResponse<T>(val response: T, val uri: URI, val locale: Locale?)

interface ApiClient {
    suspend fun fetchWhoAmI(): RABookmakerDetail
    suspend fun fetchProducers(): RAProducers
    suspend fun fetchSports(locale: Locale): List<RASport>
    suspend fun fetchMatchStatusDescriptions(locale: Locale): List<RAMatchStatusDescription>
    suspend fun fetchFixtureChanges(locale: Locale): List<RAFixtureChange>
    suspend fun fetchFixture(id: URN, locale: Locale): RAFixture
    suspend fun fetchSchedule(startIndex: Int, limit: Int, locale: Locale): List<RASportEvent>
    suspend fun fetchTournaments(sportId: URN, locale: Locale): List<RATournament>
    suspend fun fetchTournament(id: URN, locale: Locale): RATournamentExtended
    suspend fun postEventOddsRecovery(producerName: String, eventId: URN, requestId: Long, nodeId: Int?): Boolean
    suspend fun postEventStatefulRecovery(producerName: String, eventId: URN, requestId: Long, nodeId: Int?): Boolean
    suspend fun postRecovery(producerName: String, requestId: Long, nodeId: Int?, after: Long?): Boolean
    suspend fun fetchCompetitorProfile(id: URN, locale: Locale): RATeamExtended
    suspend fun fetchMatchSummary(id: URN, locale: Locale): RAMatchSummaryEndpoint
    suspend fun fetchLiveMatches(locale: Locale): List<RASportEvent>
    suspend fun fetchMatches(date: Date, locale: Locale): List<RASportEvent>
    suspend fun fetchMarketDescriptions(locale: Locale): List<RAMarketDescription>
    suspend fun postReplayClear(nodeId: Int?): Boolean
    suspend fun postReplayStop(nodeId: Int?): Boolean
    suspend fun fetchReplaySetContent(nodeId: Int?): List<RAReplayEvent>
    suspend fun putReplayEvent(eventId: URN, nodeId: Int?): Boolean
    suspend fun deleteReplayEvent(eventId: URN, nodeId: Int?): Boolean
    suspend fun postReplayStart(
        nodeId: Int?,
        speed: Int? = null,
        maxDelay: Int? = null,
        useReplayTimestamp: Boolean? = null,
        product: String? = null,
        runParallel: Boolean? = null
    ): Boolean

    fun subscribeForData(oddsFeedExtListener: OddsFeedExtListener?)
    fun <T> subscribeForClass(clazz: Class<T>): Observable<T>
    fun close()
}

private val logger = KotlinLogging.logger {}

class ApiClientImpl @Inject constructor(
    oddsFeedConfiguration: OddsFeedConfiguration,
    private val dispatchManager: DispatchManager
) : ApiClient {
    private val publisher = PublishSubject.create<Any>()

    companion object {
        private const val API_VERSION = "v1"

        private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd")
    }

    init {
        // Set auth header
        FuelManager.instance.baseHeaders =
            mapOf(
                Pair("x-access-token", oddsFeedConfiguration.accessToken),
                Pair("accept", "application/xml")
            )
        FuelManager.instance.basePath = "https://${oddsFeedConfiguration.selectedEnvironment.apiHost}/${API_VERSION}"
        simpleDateFormat.timeZone = TimeZone.getTimeZone("GMT")
    }

    override suspend fun fetchWhoAmI(): RABookmakerDetail {
        return fetchData("/users/whoami")
    }

    override suspend fun fetchSports(locale: Locale): List<RASport> {
        val data: RASportsEndpoint = fetchData("/sports/${locale.language}/sports", locale)
        return data.sport
    }

    override suspend fun fetchMatchStatusDescriptions(locale: Locale): List<RAMatchStatusDescription> {
        val data: RAMatchStatusDescriptions = fetchData("/descriptions/${locale.language}/match_status", locale)
        return data.matchStatus
    }

    override suspend fun fetchFixtureChanges(locale: Locale): List<RAFixtureChange> {
        val data: RAFixtureChangesEndpoint = fetchData("/sports/${locale.language}/fixtures/changes", locale)
        return data.fixtureChange
    }

    override suspend fun fetchFixture(id: URN, locale: Locale): RAFixture {
        val data: RAFixturesEndpoint = fetchData("/sports/${locale.language}/sport_events/$id/fixture", locale)
        return data.fixture
    }

    override suspend fun fetchSchedule(startIndex: Int, limit: Int, locale: Locale): List<RASportEvent> {
        val data: RAScheduleEndpoint =
            fetchData(
                "/sports/${locale.language}/schedules/pre/schedule?start=${startIndex}&limit=${limit}",
                locale
            )
        return data.sportEvent
    }

    override suspend fun fetchTournaments(sportId: URN, locale: Locale): List<RATournament> {
        val data: RASportTournaments = fetchData("/sports/${locale.language}/sports/$sportId/tournaments", locale)
        return data.tournaments?.tournament ?: emptyList()
    }

    override suspend fun fetchTournament(id: URN, locale: Locale): RATournamentExtended {
        val data: RATournamentInfo = fetchData("/sports/${locale.language}/tournaments/$id/info", locale)
        return data.tournament
    }

    override suspend fun postEventOddsRecovery(
        producerName: String,
        eventId: URN,
        requestId: Long,
        nodeId: Int?
    ): Boolean {
        var path = "/$producerName/odds/events/$eventId/initiate_request?request_id=$requestId"
        if (nodeId != null) {
            path = "$path&node_id=$nodeId"
        }

        return post(path)
    }

    override suspend fun postEventStatefulRecovery(
        producerName: String,
        eventId: URN,
        requestId: Long,
        nodeId: Int?
    ): Boolean {
        var path = "/$producerName/stateful_messages/events/$eventId/initiate_request?request_id=$requestId"
        if (nodeId != null) {
            path = "$path&node_id=$nodeId"
        }

        return post(path)
    }

    override suspend fun postRecovery(producerName: String, requestId: Long, nodeId: Int?, after: Long?): Boolean {
        var path = "/$producerName/recovery/initiate_request?request_id=$requestId"
        if (nodeId != null) {
            path = "$path&node_id=$nodeId"
        }

        if (after != null) {
            path = "$path&after=$after"
        }

        return post(path)
    }

    override suspend fun fetchCompetitorProfile(id: URN, locale: Locale): RATeamExtended {
        val data: RACompetitorProfileEndpoint =
            fetchData("/sports/${locale.language}/competitors/${id}/profile", locale)
        return data.competitor
    }

    override suspend fun fetchMatchSummary(id: URN, locale: Locale): RAMatchSummaryEndpoint {
        return fetchData("sports/${locale.language}/sport_events/$id/summary", locale)
    }

    override suspend fun fetchLiveMatches(locale: Locale): List<RASportEvent> {
        val data: RAScheduleEndpoint = fetchData("/sports/${locale.language}/schedules/live/schedule", locale)
        return data.sportEvent
    }

    override suspend fun fetchMatches(date: Date, locale: Locale): List<RASportEvent> {
        val data: RAScheduleEndpoint =
            fetchData("/sports/${locale.language}/schedules/${simpleDateFormat.format(date)}/schedule", locale)
        return data.sportEvent
    }

    override suspend fun fetchMarketDescriptions(locale: Locale): List<RAMarketDescription> {
        val data: RAMarketDescriptions = fetchData("/descriptions/${locale.language}/markets", locale)
        return data.market
    }

    override suspend fun postReplayClear(nodeId: Int?): Boolean {
        var path = "/replay/clear"
        if (nodeId != null) {
            path = "$path?node_id=$nodeId"
        }
        return post(path)
    }

    override suspend fun postReplayStop(nodeId: Int?): Boolean {
        var path = "/replay/stop"
        if (nodeId != null) {
            path = "$path?node_id=$nodeId"
        }
        return post(path)
    }

    override suspend fun fetchReplaySetContent(nodeId: Int?): List<RAReplayEvent> {
        var path = "/replay"
        if (nodeId != null) {
            path = "$path?node_id=$nodeId"
        }

        val data: RAReplaySetContent = fetchData(path)
        return data.event
    }

    override suspend fun putReplayEvent(eventId: URN, nodeId: Int?): Boolean {
        var path = "/replay/events/$eventId"
        if (nodeId != null) {
            path = "$path?node_id=$nodeId"
        }

        return put(path)
    }

    override suspend fun deleteReplayEvent(eventId: URN, nodeId: Int?): Boolean {
        var path = "/replay/events/$eventId"
        if (nodeId != null) {
            path = "$path?node_id=$nodeId"
        }

        return delete(path)
    }

    override suspend fun postReplayStart(
        nodeId: Int?,
        speed: Int?,
        maxDelay: Int?,
        useReplayTimestamp: Boolean?,
        product: String?,
        runParallel: Boolean?
    ): Boolean {
        val queryParams = mutableMapOf<String, Any>()
        if (nodeId != null) {
            queryParams["node_id"] = nodeId
        }

        if (speed != null) {
            queryParams["speed"] = speed
        }

        if (maxDelay != null) {
            queryParams["max_delay"] = maxDelay
        }

        if (useReplayTimestamp != null) {
            queryParams["use_replay_timestamp"] = useReplayTimestamp
        }

        if (runParallel != null) {
            queryParams["run_parallel"] = runParallel
        }

        if (product != null) {
            queryParams["product"] = product
        }

        val params = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")
        var path = "/replay/play"
        if (params.isNotEmpty()) {
            path = "$path?$params"
        }

        return post(path)
    }

    override suspend fun fetchProducers(): RAProducers {
        return fetchData("/descriptions/producers")
    }

    override fun subscribeForData(oddsFeedExtListener: OddsFeedExtListener?) {
        val listener = oddsFeedExtListener ?: return

        dispatchManager
            .listen(ApiResponse::class.java)
            .subscribe({
                if (it.response != null) {
                    listener.onRawApiDataReceived(it.uri, it.response)
                }
            }, {
                logger.error { "Failed to dispatch raw api data - $it" }
            }
            )
    }

    override fun <T> subscribeForClass(clazz: Class<T>): Observable<T> {
        return publisher.ofType(clazz)
    }

    override fun close() {
        publisher.onComplete()
        dispatchManager.close()
    }

    private suspend fun post(path: String): Boolean {
        val result = Fuel.post(path).awaitStringResult()
        if (result is Result.Failure) {
            val error = unmarshalPossibleError(result.error)
            throw ApiException("Failed to post data", error, result.error)
        }

        return true
    }

    private suspend fun put(path: String): Boolean {
        val result = Fuel.put(path).awaitStringResult()
        if (result is Result.Failure) {
            val error = unmarshalPossibleError(result.error)
            throw ApiException("Failed to put data", error, result.error)
        }

        return true
    }

    private suspend fun delete(path: String): Boolean {
        val result = Fuel.delete(path).awaitStringResult()
        if (result is Result.Failure) {
            val error = unmarshalPossibleError(result.error)
            throw ApiException("Failed to delete data", error, result.error)
        }

        return true
    }

    private suspend fun <T : Any> fetchData(path: String, locale: Locale? = null): T {
        val result: Result<T, FuelError> = Fuel.get(path).awaitObjectResult(Deserializer())
        if (result is Result.Failure) {
            val error = unmarshalPossibleError(result.error)
            throw ApiException("Failed to get data", error, result.error)
        }

        val response = result.get()
        if (response is ResponseWithCode && response.responseCode != RAResponseCode.OK) {
            throw ApiException(
                "Not acceptable response code from API: ${response.responseCode}"
            )
        }

        val apiResponse = ApiResponse(response, URI.create("${FuelManager.instance.basePath}$path"), locale)
        // Dispatch for possible processing
        dispatchManager.publish(apiResponse)
        // Synchronous processing
        publisher.onNext(apiResponse)
        return response
    }

    private fun unmarshalPossibleError(error: FuelError): RAError? {
        return try {
            Deserializer.UNMARSHALLER.unmarshal(ByteArrayInputStream(error.errorData)) as? RAError
        } catch (e: Exception) {
            null
        }
    }
}

class Deserializer<out T : Any> : ResponseDeserializable<T> {
    companion object {
        var UNMARSHALLER: Unmarshaller

        init {
            val jc: JAXBContext = JAXBContext.newInstance(
                "com.oddin.oddsfeedsdk.schema.rest.v1"
            )
            UNMARSHALLER = jc.createUnmarshaller()
        }
    }

    override fun deserialize(inputStream: InputStream): T? {
        @Suppress("UNCHECKED_CAST")
        return UNMARSHALLER.unmarshal(inputStream) as T?
    }
}