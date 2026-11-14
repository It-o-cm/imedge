package com.intermarche.edge.service.export;

/** Unit contracts for HD_43 referential and document coverage. */
class OutboundFlowTest {
    /** BO-11-01-03: the revenue flow exposes its requirement, administered activation, label and CSV shape to the back office. */
    @org.junit.jupiter.api.Test
    void revenueMetadataIdentifiesItsAdministrationAndFormat() {
        org.junit.jupiter.api.Assertions.assertEquals("BO-11-01-03", OutboundFlow.REVENUE.getRequirement());
        org.junit.jupiter.api.Assertions.assertEquals("export.revenue-enabled", OutboundFlow.REVENUE.getActivationKey());
        org.junit.jupiter.api.Assertions.assertEquals("Chiffre d'affaires par date", OutboundFlow.REVENUE.getLabel());
        org.junit.jupiter.api.Assertions.assertEquals(OutboundFlow.Shape.CSV, OutboundFlow.REVENUE.getShape());
    }

}
