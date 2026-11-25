package com.antifraud.mock.scoring

import com.antifraud.proto.v1.ScoreResponse
import com.antifraud.proto.v1.Transaction

fun interface ScoringGateway {
    suspend fun score(transaction: Transaction): ScoreResponse
}
