package com.intermarche.edge.domain.setting;

/** Unit contracts for HD_43 referential and document coverage. */
class PosListItemTest {
    /** BO-08-01-04: each synchronized business-field change invalidates the row checksum. */
    @org.junit.jupiter.api.Test
    void everyBusinessFieldChangeInvalidatesTheChecksum() {
        var row = new PosListItem();
        int previous = row.getChecksum();
        row.code = "BROKEN";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "code must participate in synchronization");
        previous = row.getChecksum();
        row.label = "Damaged";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "label must participate in synchronization");
        previous = row.getChecksum();
        row.displayOrder = 7;
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "displayOrder must participate in synchronization");
        previous = row.getChecksum();
        row.severity = 2;
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "severity must participate in synchronization");
        previous = row.getChecksum();
        row.numericValue = new java.math.BigDecimal("3.50");
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "numericValue must participate in synchronization");
        previous = row.getChecksum();
        row.options = "OPTION";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "options must participate in synchronization");
        previous = row.getChecksum();
        row.active = false;
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "active must participate in synchronization");
        previous = row.getChecksum();
    }

}
