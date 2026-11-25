package com.antifraud.mock.dataset

import com.antifraud.features.UserFeatureHistory
import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.scenario.ScenarioCatalog
import com.antifraud.mock.scenario.UserDirectory
import com.antifraud.mock.stream.StreamConfig
import com.antifraud.mock.stream.StreamTransactions
import com.antifraud.proto.v1.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.Instant

class DatasetService(
    private val users: UserDirectory,
    private val normal: NormalTransactionGenerator,
    private val catalog: ScenarioCatalog,
) {
    fun export(size: Int, fraudRate: Double, seed: Long): Flow<String> = flow {
        val totalDailyRate = users.users.sumOf { it.paymentsPerDay.toLong() }
        val rps = ((totalDailyRate + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).coerceIn(1, 20_000).toInt()
        val config = StreamConfig(rps, fraudRate, FraudPattern.entries)
        val transactions = StreamTransactions(users.users, normal, catalog, seed)
        val histories = mutableMapOf<String, UserFeatureHistory>()
        val batch = StringBuilder()
        emit(HEADER + "\n")

        repeat(size) { index ->
            val now = START.plusNanos(index * 1_000_000_000L / rps)
            val event = transactions.next(config, now)
            val tx = event.transaction
            val history = histories.getOrPut(tx.userId) { UserFeatureHistory() }
            val features = history.observe(payment(tx, now.toEpochMilli()))
            batch.append(csvRow(features, event.expectedFraud, event.pattern?.name.orEmpty()))
            if ((index + 1) % BATCH_SIZE == 0) {
                emit(batch.toString())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) emit(batch.toString())
    }.flowOn(Dispatchers.Default).buffer(0)

    private fun payment(tx: Transaction, emittedAtMs: Long): UserFeatureHistory.Payment {
        val coordinates = if (tx.hasLatitude() && tx.hasLongitude()) {
            UserFeatureHistory.Coordinates(tx.latitude, tx.longitude)
        } else null
        return UserFeatureHistory.Payment(
            emittedAtMs, tx.amountMinor, tx.currency, tx.merchantCategory, tx.merchantId,
            tx.channel.name.removePrefix("CHANNEL_"), tx.country, tx.deviceId, tx.ip, coordinates,
        )
    }

    private fun csvRow(f: UserFeatureHistory.Features, expectedFraud: Boolean, pattern: String): String =
        listOf(
            f.amount(), f.logAmount(), f.currency(), f.hourOfDay(), f.merchantCategory(), f.channel(), f.country(),
            f.txCount1m(), f.txCount5m(), f.txCount60m(), f.amountSum5m(), f.smallTxCount10m(),
            f.smallTxDistinctMerchants10m(), f.travelSpeedKmh(), f.amountToAvgRatio(), f.userTxCount(),
            f.newDevice(), f.newIp(), f.newCountry(), expectedFraud, pattern,
        ).joinToString(",", postfix = "\n", transform = ::csvCell)

    private fun csvCell(value: Any): String {
        val text = value.toString()
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else text
    }

    private companion object {
        val START: Instant = Instant.parse("2026-01-01T00:00:00Z")
        const val SECONDS_PER_DAY = 86_400L
        const val BATCH_SIZE = 128
        const val HEADER = "amount,log_amount,currency,hour_of_day,merchant_category,channel,country," +
            "tx_count_1m,tx_count_5m,tx_count_60m,amount_sum_5m,small_tx_count_10m,small_tx_distinct_merchants_10m," +
            "travel_speed_kmh,amount_to_avg_ratio,user_tx_count,is_new_device,is_new_ip,is_new_country,expected_fraud,pattern"
    }
}
