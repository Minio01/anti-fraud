package com.antifraud.scoring.score.domain;

import java.util.Currency;

public record Money(long amountMinor, Currency currency) {
}
