package com.intermarche.pos.domain.store;

import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Address}, targeting 100% branch coverage.
 * <p>
 * The only branching method is {@code equals}; every other member
 * (constructors, {@code hashCode}, {@code getChecksum}) is branchless
 * and exercised for line coverage.
 */
class AddressTest {

    /**
     * Builds a fully populated reference address used as the equality baseline.
     *
     * @return a canonical {@link Address} instance
     */
    private Address reference() {
        return new Address("10 Av", "Bat A", "75008", "Paris", "France", 48.87, 2.30);
    }

    /**
     * The default constructor leaves every field null.
     */
    @Test
    void defaultConstructorLeavesFieldsNull() {
        Address a = new Address();
        Assertions.assertNull(a.streetLine1);
        Assertions.assertNull(a.streetLine2);
        Assertions.assertNull(a.postalCode);
        Assertions.assertNull(a.city);
        Assertions.assertNull(a.country);
        Assertions.assertNull(a.latitude);
        Assertions.assertNull(a.longitude);
    }

    /**
     * The full constructor assigns every field verbatim.
     */
    @Test
    void fullConstructorAssignsAllFields() {
        Address a = new Address("10 Av", "Bat A", "75008", "Paris", "France", 48.87, 2.30);
        Assertions.assertEquals("10 Av", a.streetLine1);
        Assertions.assertEquals("Bat A", a.streetLine2);
        Assertions.assertEquals("75008", a.postalCode);
        Assertions.assertEquals("Paris", a.city);
        Assertions.assertEquals("France", a.country);
        Assertions.assertEquals(48.87, a.latitude);
        Assertions.assertEquals(2.30, a.longitude);
    }

    /**
     * equals returns true for the same reference (this == o arm true).
     */
    @Test
    void equalsSameReferenceIsTrue() {
        Address a = reference();
        Assertions.assertTrue(a.equals(a));
    }

    /**
     * equals returns false against null (o == null arm true).
     */
    @Test
    void equalsNullIsFalse() {
        Address a = reference();
        Assertions.assertFalse(a.equals(null));
    }

    /**
     * equals returns false against a different class (getClass mismatch arm true).
     */
    @Test
    void equalsDifferentClassIsFalse() {
        Address a = reference();
        Assertions.assertFalse(a.equals("not an address"));
    }

    /**
     * equals returns true for two distinct instances with identical content
     * (this != o, o != null, same class, all seven field comparisons true).
     */
    @Test
    void equalsIdenticalContentIsTrue() {
        Address a = reference();
        Address b = reference();
        Assertions.assertNotSame(a, b);
        Assertions.assertTrue(a.equals(b));
    }

    /**
     * A difference in streetLine1 short-circuits the chain to false (1st arm false).
     */
    @Test
    void equalsDifferentStreetLine1IsFalse() {
        Address a = reference();
        Address b = new Address("X", "Bat A", "75008", "Paris", "France", 48.87, 2.30);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * A difference in streetLine2 short-circuits the chain to false (2nd arm false).
     */
    @Test
    void equalsDifferentStreetLine2IsFalse() {
        Address a = reference();
        Address b = new Address("10 Av", "X", "75008", "Paris", "France", 48.87, 2.30);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * A difference in postalCode short-circuits the chain to false (3rd arm false).
     */
    @Test
    void equalsDifferentPostalCodeIsFalse() {
        Address a = reference();
        Address b = new Address("10 Av", "Bat A", "X", "Paris", "France", 48.87, 2.30);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * A difference in city short-circuits the chain to false (4th arm false).
     */
    @Test
    void equalsDifferentCityIsFalse() {
        Address a = reference();
        Address b = new Address("10 Av", "Bat A", "75008", "X", "France", 48.87, 2.30);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * A difference in country short-circuits the chain to false (5th arm false).
     */
    @Test
    void equalsDifferentCountryIsFalse() {
        Address a = reference();
        Address b = new Address("10 Av", "Bat A", "75008", "Paris", "X", 48.87, 2.30);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * A difference in latitude short-circuits the chain to false (6th arm false).
     */
    @Test
    void equalsDifferentLatitudeIsFalse() {
        Address a = reference();
        Address b = new Address("10 Av", "Bat A", "75008", "Paris", "France", 0.0, 2.30);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * A difference in longitude makes the final comparison false (7th arm false).
     */
    @Test
    void equalsDifferentLongitudeIsFalse() {
        Address a = reference();
        Address b = new Address("10 Av", "Bat A", "75008", "Paris", "France", 48.87, 0.0);
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * hashCode is consistent with the JDK Objects.hash of the seven fields.
     */
    @Test
    void hashCodeMatchesObjectsHash() {
        Address a = reference();
        int expected = Objects.hash("10 Av", "Bat A", "75008", "Paris", "France", 48.87, 2.30);
        Assertions.assertEquals(expected, a.hashCode());
    }

    /**
     * Equal instances produce equal hash codes.
     */
    @Test
    void hashCodeEqualForEqualInstances() {
        Assertions.assertEquals(reference().hashCode(), reference().hashCode());
    }

    /**
     * getChecksum returns the Objects.hash of the seven fields.
     */
    @Test
    void getChecksumMatchesObjectsHash() {
        Address a = reference();
        int expected = Objects.hash("10 Av", "Bat A", "75008", "Paris", "France", 48.87, 2.30);
        Assertions.assertEquals(expected, a.getChecksum());
    }
}
