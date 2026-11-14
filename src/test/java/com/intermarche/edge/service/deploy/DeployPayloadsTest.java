package com.intermarche.edge.service.deploy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DeployPayloads}. */
class DeployPayloadsTest {

    /** A register is keyed inside its point of sale; the central node has none. */
    @Test
    void nodeKeyNamesKindPointOfSaleAndIdentifier() {
        assertEquals("REGISTER:07039:C04", DeployPayloads.nodeKey(DeployPayloads.REGISTER, "07039", "C04"));
        assertEquals("CENTRAL::CENTRAL", DeployPayloads.nodeKey(DeployPayloads.CENTRAL, null, "CENTRAL"));
    }

    /** The lists of a report and of an answer start empty, and a report reads healthy by default. */
    @Test
    void payloadsStartEmptyAndHealthy() {
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        assertTrue(report.appOk);
        assertTrue(report.peripherals.isEmpty() && report.inventory.isEmpty() && report.integrations.isEmpty()
                && report.usages.isEmpty() && report.actionResults.isEmpty() && report.registers.isEmpty());
        DeployPayloads.ReportAnswer answer = new DeployPayloads.ReportAnswer();
        assertTrue(answer.targets.isEmpty() && answer.actions.isEmpty());
        assertEquals(null, new DeployPayloads.Peripheral().role);
        assertEquals(null, new DeployPayloads.InventoryItem().name);
        assertEquals(null, new DeployPayloads.Integration().domain);
        assertEquals(0L, new DeployPayloads.Usage().count);
        assertEquals(null, new DeployPayloads.ActionResult().uid);
        assertEquals(null, new DeployPayloads.Target().version);
        assertEquals(null, new DeployPayloads.Action().type);
    }
    /**
     * BO-02-05-05: a report carries no revenue until a store states its days,
     * and a day holds what it is given.
     */
    @Test
    void aReportCarriesTheRevenueOfItsDays() {
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        org.junit.jupiter.api.Assertions.assertTrue(report.revenues.isEmpty());
        DeployPayloads.DayRevenue day = new DeployPayloads.DayRevenue();
        day.day = "2026-10-25";
        day.revenue = java.math.BigDecimal.TEN;
        day.tickets = 2L;
        report.revenues.add(day);
        org.junit.jupiter.api.Assertions.assertEquals(2L, report.revenues.get(0).tickets);
    }
}
