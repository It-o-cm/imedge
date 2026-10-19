package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link BankDeposit}.
 *
 * <p>The bag that leaves for the bank. Its formatting is asserted on both arms
 * of the amount guard and its finder through {@code PanacheEntityBase}; what the
 * amount IS — the sum of the pouches, never a typed figure — is proved on the
 * safe service that writes it.
 */
class BankDepositTest {

    /**
     * Builds a deposit.
     *
     * @param amount the amount, as a plain decimal string, null for none
     * @return the deposit
     */
    private BankDeposit deposit(String amount) {
        BankDeposit deposit = new BankDeposit();
        deposit.bagNumber = "SAC-42";
        deposit.label = "Remise du lundi";
        deposit.depositDay = LocalDate.of(2026, 9, 16);
        deposit.amount = amount == null ? null : new BigDecimal(amount);
        return deposit;
    }

    /**
     * A deposit starts with no pouches attached and an amount of zero.
     */
    @Test
    void adepositStartsEmpty() {
        BankDeposit deposit = new BankDeposit();
        assertEquals(BigDecimal.ZERO, deposit.amount);
        assertTrue(deposit.pouches.isEmpty());
    }

    /**
     * The amount is formatted with two decimals, and a deposit carrying none
     * reads as zero rather than exploding — both arms of the guard.
     */
    @Test
    void theamountIsFormattedOrReadsAsZero() {
        assertEquals("2500.00", deposit("2500").getAmountFormatted());
        assertEquals("0.00", deposit(null).getAmountFormatted());
    }

    /**
     * The history finder asks for every deposit, newest day first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thehistoryFinderAsksForEveryDepositNewestFirst() {
        BankDeposit deposit = deposit("2500.00");
        PanacheQuery<BankDeposit> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(deposit));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> BankDeposit.find("order by depositDay desc, id desc"))
                    .thenReturn(query);
            assertEquals(List.of(deposit), BankDeposit.listNewestFirst());
        }
    }

    /**
     * The checksum hashes the identifying and financial fields.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        BankDeposit deposit = deposit("2500.00");
        int expected = Objects.hash("SAC-42", "Remise du lundi", LocalDate.of(2026, 9, 16),
                new BigDecimal("2500.00"));
        assertEquals(expected, deposit.getChecksum());
    }
}
