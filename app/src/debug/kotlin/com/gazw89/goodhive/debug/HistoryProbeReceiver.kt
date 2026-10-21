package com.gazw89.goodhive.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gazw89.goodhive.auth.AuthRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Debug-only, read-only probe of Hive's undocumented `/history/...` endpoints, to learn what
 * history a non-Hive+ account can read. Logs status + a short summary under [TAG] and writes each
 * raw body to `files/history-probe/`. Never logs the token or the `nodes/all` body.
 */
class HistoryProbeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProbeEntryPoint {
        fun authRepository(): AuthRepository
        fun okHttpClient(): OkHttpClient
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ProbeEntryPoint::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runProbe(context, intent, entryPoint.authRepository(), entryPoint.okHttpClient())
            } catch (t: Throwable) {
                Log.e(TAG, "probe failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runProbe(context: Context, intent: Intent, auth: AuthRepository, client: OkHttpClient) {
        val outDir = File(context.filesDir, "history-probe").apply { deleteRecursively(); mkdirs() }
        val token = auth.currentIdToken()

        // One-off live write test: --es post "nodes/trvcontrol/<id>|{json}". Debug builds only.
        intent.getStringExtra("post")?.let { spec ->
            val (path, body) = spec.split('|', limit = 2)
            val request = Request.Builder()
                .url("https://beekeeper.hivehome.com/1.0/$path")
                .header("Authorization", token)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val code = client.newCall(request).execute().use { it.code }
            Log.i(TAG, "POST $path $body -> HTTP $code")
            Log.i(TAG, "done; post only")
            return
        }
        val json = Json { ignoreUnknownKeys = true }

        val nodes = get(client, token, "nodes/all?products=true&devices=true&actions=false")
        if (nodes.code !in 200..299) {
            Log.e(TAG, "nodes/all -> HTTP ${nodes.code}")
            return
        }
        val root = json.parseToJsonElement(nodes.body).jsonObject
        val products = root["products"]?.jsonArray.orEmpty().map { it.jsonObject }
        val devices = root["devices"]?.jsonArray.orEmpty().map { it.jsonObject }
        products.filter { it.str("type") in setOf("heating", "trvcontrol") }.forEach {
            Log.i(TAG, "product type=${it.str("type")} id=${it.str("id")} name=${it.obj("state")?.str("name")}")
        }
        devices.filter { it.str("type") in setOf("trv", "thermostatui", "boilermodule") }.forEach {
            Log.i(TAG, "device type=${it.str("type")} id=${it.str("id")} name=${it.obj("state")?.str("name")}")
        }

        val heating = products.firstOrNull { it.str("type") == "heating" }
        val trvs = products.filter { it.str("type") == "trvcontrol" }
        val trv = trvs.firstOrNull()
        val trvDevice = devices.firstOrNull { it.str("type") == "trv" }

        val now = System.currentTimeMillis()
        val hour = TimeUnit.HOURS.toMillis(1)
        val day = TimeUnit.DAYS.toMillis(1)
        fun q(start: Long, end: Long, unit: String, rate: Int, op: String = "AVG") =
            "start=$start&end=$end&timeUnit=$unit&rate=$rate&operation=$op"

        // Optional ad-hoc list: --es paths "label=path;label=path" with {heat} {trv} {trvdev} {now} {d:N} (= now - N days).
        val custom = intent.getStringExtra("paths")?.split(';')?.filter { it.contains('=') }?.map { entry ->
            val (label, template) = entry.split('=', limit = 2)
            val path = Regex("""\{d:(\d+)\}""").replace(template) { (now - it.groupValues[1].toLong() * day).toString() }
                .replace("{heat}", heating?.str("id").orEmpty())
                .replace("{trv}", trv?.str("id").orEmpty())
                .replace("{trvdev}", trvDevice?.str("id").orEmpty())
                .replace("{now}", now.toString())
            label to path
        }

        val probes = custom ?: buildList {
            heating?.str("id")?.let { id ->
                add("heat-days-7d" to "history/heating/$id?${q(now - 7 * day, now, "DAYS", 1)}")
                add("heat-hours-24h" to "history/heating/$id?${q(now - day, now, "HOURS", 1)}")
                add("heat-min5-24h" to "history/heating/$id?${q(now - day, now, "MINUTES", 5)}")
                add("heat-min1-2h" to "history/heating/$id?${q(now - 2 * hour, now, "MINUTES", 1)}")
                add("heat-hours-7d" to "history/heating/$id?${q(now - 7 * day, now, "HOURS", 1)}")
                add("heat-days-120d" to "history/heating/$id?${q(now - 120 * day, now, "DAYS", 1)}")
                add("heat-days-400d-ago" to "history/heating/$id?${q(now - 400 * day, now - 380 * day, "DAYS", 1)}")
            }
            trv?.str("id")?.let { id ->
                add("trv-trvcontrol-hours-24h" to "history/trvcontrol/$id?${q(now - day, now, "HOURS", 1)}")
                add("trv-trvcontrol-min5-24h" to "history/trvcontrol/$id?${q(now - day, now, "MINUTES", 5)}")
                add("trv-trvcontrol-days-30d" to "history/trvcontrol/$id?${q(now - 30 * day, now, "DAYS", 1)}")
                add("trv-asheating-hours-24h" to "history/heating/$id?${q(now - day, now, "HOURS", 1)}")
            }
            trvDevice?.str("id")?.let { id ->
                add("trvdevice-trv-hours-24h" to "history/trv/$id?${q(now - day, now, "HOURS", 1)}")
            }
        }

        probes.forEachIndexed { index, (label, path) ->
            delay(1_500) // keep well clear of Beekeeper's CDN rate limiting
            val result = runCatching { get(client, token, path) }
            result.onSuccess { res ->
                File(outDir, "%02d-%s.json".format(index, label)).writeText(res.body)
                Log.i(TAG, "$label -> HTTP ${res.code} bytes=${res.body.length} ${summarize(json, res.body)}")
            }.onFailure { Log.w(TAG, "$label -> ${it.javaClass.simpleName}: ${it.message}") }
        }
        Log.i(TAG, "done; bodies in ${outDir.absolutePath}")
    }

    private fun summarize(json: Json, body: String): String {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return "raw=${body.take(300)}"
        val data = (element as? JsonObject)?.get("data") as? JsonArray
            ?: return "json=${body.take(300)}"
        val edges = if (data.size <= 4) data.toString() else "${data.take(2)} … ${data.takeLast(2)}"
        return "points=${data.size} $edges"
    }

    private data class Res(val code: Int, val body: String)

    private fun get(client: OkHttpClient, token: String, path: String): Res {
        val request = Request.Builder()
            .url("https://beekeeper.hivehome.com/1.0/$path")
            .header("Authorization", token)
            .get()
            .build()
        return client.newCall(request).execute().use { Res(it.code, it.body?.string().orEmpty()) }
    }

    private fun JsonObject.str(key: String): String? = runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private companion object {
        const val TAG = "HiveHistoryProbe"
    }
}
