package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Pdv}.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@link Pdv#isValidNumber}
 * covers the null arm, the wrong-length arm, the non-digit arm inside the loop
 * and the all-digits success; {@link Pdv#getChecksum} is exercised on two rows
 * differing in one field each so the hash is proven field-sensitive. Static
 * finders carry no branches and are left to the integration surface.
 */
class PdvTest {

    /**
     * A null candidate is not a valid number (null arm).
     */
    @Test
    void isValidNumberRejectsNull() {
        assertFalse(Pdv.isValidNumber(null));
    }

    /**
     * A candidate of the wrong length is rejected (length arm), both shorter
     * and longer than five.
     */
    @Test
    void isValidNumberRejectsWrongLength() {
        assertFalse(Pdv.isValidNumber("1234"));
        assertFalse(Pdv.isValidNumber("123456"));
    }

    /**
     * A five-character candidate carrying a non-digit is rejected (non-digit
     * arm inside the loop).
     */
    @Test
    void isValidNumberRejectsNonDigit() {
        assertFalse(Pdv.isValidNumber("12A45"));
    }

    /**
     * Exactly five digits is valid (loop completes, success arm), including a
     * leading zero.
     */
    @Test
    void isValidNumberAcceptsFiveDigits() {
        assertTrue(Pdv.isValidNumber("01234"));
    }

    /**
     * {@link Pdv#getChecksum} changes when any business field changes and is
     * stable for identical field sets.
     */
    @Test
    void checksumIsFieldSensitive() {
        Pdv base = pdv("12345", "Lyon", "IF", "AD1", true);
        Pdv same = pdv("12345", "Lyon", "IF", "AD1", true);
        assertEquals(base.getChecksum(), same.getChecksum());
        assertNotEquals(base.getChecksum(), pdv("54321", "Lyon", "IF", "AD1", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Paris", "IF", "AD1", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Lyon", "NF", "AD1", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Lyon", "IF", "AD2", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Lyon", "IF", "AD1", false).getChecksum());
    }

    /**
     * Builds a PDV with the given business fields.
     *
     * @param number the PDV number
     * @param name the display name
     * @param enseigne the enseigne code
     * @param adherent the adhérent code
     * @param active whether the PDV is active
     * @return the built PDV
     */
    private Pdv pdv(String number, String name, String enseigne, String adherent, boolean active) {
        Pdv pdv = new Pdv();
        pdv.pdvNumber = number;
        pdv.name = name;
        pdv.enseigneCode = enseigne;
        pdv.adherentCode = adherent;
        pdv.active = active;
        return pdv;
    }

    /**
     * The two list finders delegate to Panache with their query and hand back
     * what the store answers: {@code listByEnseigne} the PDVs of one enseigne
     * ordered by number, {@code listAllOrdered} every PDV ordered by number.
     */
    @Test
    void listFindersDelegateToPanache() {
        List<Pdv> ofEnseigne = List.of(new Pdv());
        List<Pdv> all = List.of(new Pdv(), new Pdv());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Pdv.list("enseigneCode = ?1 order by pdvNumber", "ITM")).thenReturn(ofEnseigne);
            mocked.when(() -> Pdv.list("order by pdvNumber")).thenReturn(all);
            assertSame(ofEnseigne, Pdv.listByEnseigne("ITM"));
            assertSame(all, Pdv.listAllOrdered());
        }
    }
    /**
     * BO-02-05-05: the points of sale of one adhérent are listed by number, and
     * the adhérent of a point of sale is read — none for a null number, an
     * unknown point of sale or one attached to none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theAdherentFindersReadTheGrouping() {
        Pdv operated = new Pdv();
        operated.adherentCode = "AD1";
        Pdv loose = new Pdv();
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> Pdv.list("adherentCode = ?1 order by pdvNumber", "AD1"))
                    .thenReturn(java.util.List.of(operated));
            assertEquals(java.util.List.of(operated), Pdv.listByAdherent("AD1"));
            io.quarkus.hibernate.orm.panache.PanacheQuery<Pdv> one =
                    org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            org.mockito.Mockito.when(one.firstResult()).thenReturn(operated);
            io.quarkus.hibernate.orm.panache.PanacheQuery<Pdv> two =
                    org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            org.mockito.Mockito.when(two.firstResult()).thenReturn(loose);
            io.quarkus.hibernate.orm.panache.PanacheQuery<Pdv> none =
                    org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            panache.when(() -> Pdv.find("pdvNumber", "10001")).thenReturn(one);
            panache.when(() -> Pdv.find("pdvNumber", "10002")).thenReturn(two);
            panache.when(() -> Pdv.find("pdvNumber", "10003")).thenReturn(none);
            assertEquals("AD1", Pdv.adherentOf("10001"));
            org.junit.jupiter.api.Assertions.assertNull(Pdv.adherentOf("10002"));
            org.junit.jupiter.api.Assertions.assertNull(Pdv.adherentOf("10003"));
            org.junit.jupiter.api.Assertions.assertNull(Pdv.adherentOf(null));
        }
    }
}
