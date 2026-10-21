package com.gazw89.goodhive.data.network

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Hive's unofficial "beekeeper" REST API (base https://beekeeper.hivehome.com/1.0/).
 * Endpoints and auth header format verified against Pyhive's reference implementation
 * and a real account (see plan doc's "Verified technical foundation" section).
 */
interface HiveApiService {

    @GET("nodes/all")
    suspend fun getAllNodes(
        @Header("Authorization") token: String,
        @Query("products") products: Boolean = true,
        @Query("devices") devices: Boolean = true,
        @Query("actions") actions: Boolean = false,
    ): HiveNodesResponse

    /**
     * Sets state on a node (temperature, mode, boost, or schedule). [type]/[id] are the
     * product's own `type`/`id` fields from [getAllNodes] (e.g. "trvcontrol"/"heating").
     * A 2xx here means Hive's cloud *accepted* the request — NOT that the physical device
     * applied it. Callers must check a follow-up poll; this confirms Hive's reported configuration,
     * not a physical-device acknowledgement, which this endpoint does not expose.
     */
    @POST("nodes/{type}/{id}")
    suspend fun setState(
        @Header("Authorization") token: String,
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    )

    /**
     * Temperature history (live-confirmed). `start`/`end` are epoch millis; `timeUnit` is
     * MINUTES|HOURS|DAYS, `rate` the bucket size, `operation` AVG|MAX. Each point's `date` is an
     * epoch-millis **string**. [id] is the `heating` PRODUCT id for a thermostat, but for a TRV it
     * is a physical `trv` DEVICE id from the trvcontrol product's `props.trvs` (a trvcontrol
     * product id returns 404). Works without a Hive+ subscription. A 403 whose message mentions
     * SigV4 means an unknown route, not an auth failure.
     */
    @GET("history/heating/{id}")
    suspend fun getTemperatureHistory(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Query("start") start: Long,
        @Query("end") end: Long,
        @Query("timeUnit") timeUnit: String,
        @Query("rate") rate: Int,
        @Query("operation") operation: String,
    ): HiveHistoryResponse
}
