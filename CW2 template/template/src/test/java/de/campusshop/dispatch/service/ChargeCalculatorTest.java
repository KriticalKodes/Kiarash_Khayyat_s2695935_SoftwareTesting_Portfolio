package de.campusshop.dispatch.service;

import de.campusshop.dispatch.model.Tariff;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeCalculatorTest {

    private final ChargeCalculator calculator = new ChargeCalculator();

    @Test
    void pricesTheSpecExampleWithHalfUpRounding() {
        Tariff standard = tariff("TRF-1", "4.50", "1.20", "0.10");
        assertThat(calculator.charge(standard, new BigDecimal("2.5")))
                .isEqualByComparingTo("8.25"); // (4.50 + 1.20*2.5) * 1.10

        Tariff express = tariff("TRF-2", "6.00", "2.00", "0.15");
        assertThat(calculator.charge(express, new BigDecimal("0.8")))
                .isEqualByComparingTo("8.74"); // (6.00 + 2.00*0.8) * 1.15 = 8.740
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        // (1.00 + 1.00*1.005) * 1.00 = 2.005 -> 2.01 (HALF_UP, not HALF_EVEN)
        Tariff tariff = tariff("TRF-X", "1.00", "1.00", "0.00");
        BigDecimal result = calculator.charge(tariff, new BigDecimal("1.005"));
        assertThat(result).isEqualByComparingTo("2.01");
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void keepsTwoDecimalsWithZeroSurchargeAndNoRoundingNeeded() {
        Tariff tariff = tariff("TRF-Y", "3.00", "0.50", "0.00");
        BigDecimal result = calculator.charge(tariff, new BigDecimal("4"));
        assertThat(result).isEqualByComparingTo("5.00");
        assertThat(result.scale()).isEqualTo(2);
    }

    private static Tariff tariff(String code, String baseFee, String pricePerKg, String surcharge) {
        return new Tariff(code, "Service", new BigDecimal(baseFee), new BigDecimal(pricePerKg), new BigDecimal(surcharge));
    }
}
