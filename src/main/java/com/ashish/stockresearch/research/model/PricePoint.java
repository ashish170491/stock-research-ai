package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One trading day's close, in rupees per share. */
public record PricePoint(LocalDate date, BigDecimal close) {
}
