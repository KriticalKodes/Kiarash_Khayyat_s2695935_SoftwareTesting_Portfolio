package de.campusshop.dispatch.service;

import de.campusshop.dispatch.model.Tariff;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Assertions;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based pricing checks with jqwik. Instead of relying on three fixed examples, these
 * properties generate hundreds of tariffs and weights and verify the formula, the HALF_UP rounding
 * mode and the two-decimal scale invariant. This is the "property-based pricing tests" improvement
 * recorded in LO4/LO5 of the portfolio.
 */
class ChargeCalculatorPropertyTest {

    private final ChargeCalculator calculator = new ChargeCalculator();

    @Property(tries = 300)
    void formulaHoldsForGeneratedTariffsAndWeights(
            @ForAll @DoubleRange(min = 0, max = 1000) double baseFee,
            @ForAll @DoubleRange(min = 0, max = 100) double pricePerKg,
            @ForAll @DoubleRange(min = 0, max = 1) double surcharge,
            @ForAll @DoubleRange(min = 0.01, max = 100) double weight) {

        BigDecimal base = money(baseFee);
        BigDecimal perKg = money(pricePerKg);
        BigDecimal rate = money(surcharge);
        BigDecimal kg = money(weight);

        Tariff tariff = new Tariff("TRF-GEN", "Generated", base, perKg, rate);
        BigDecimal actual = calculator.charge(tariff, kg);

        BigDecimal expected = base.add(perKg.multiply(kg))
                .multiply(BigDecimal.ONE.add(rate))
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(actual).isEqualByComparingTo(expected);
        assertThat(actual.scale()).isEqualTo(2);
    }

    @Property(tries = 200)
    void heavierParcelNeverCostsLessWhenPerKgPriceIsPositive(
            @ForAll @DoubleRange(min = 0, max = 1000) double baseFee,
            @ForAll @DoubleRange(min = 0.01, max = 100) double pricePerKg,
            @ForAll @DoubleRange(min = 0, max = 1) double surcharge,
            @ForAll @DoubleRange(min = 0.01, max = 50) double weight) {

        Tariff tariff = new Tariff("TRF-GEN", "Generated", money(baseFee), money(pricePerKg), money(surcharge));
        BigDecimal lighter = calculator.charge(tariff, money(weight));
        BigDecimal heavier = calculator.charge(tariff, money(weight + 1));
        Assertions.assertTrue(heavier.compareTo(lighter) >= 0,
                "charge must be monotonically non-decreasing in weight");
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
