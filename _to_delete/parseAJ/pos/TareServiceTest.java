package com.intermarche.pos.ui.balance;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TareService}, the software-tare resolver (LC-14-01).
 * <p>
 * The only collaborator is a mocked {@link PosSettingsService}; the article
 * tare is read from a real {@link Product}'s in-memory attribute map by the
 * pure static {@code ProductAttributes}, so no static mock is needed.
 * <p>
 * Branch enumeration (both arms, 100%): {@code resolve} — container armed,
 * container absent with a positive article tare, container absent with no
 * article tare falling to the default, and a null state; {@code resolveForManual}
 * — container armed, null item, null item tare, zero item tare, positive item
 * tare, and the default fallback; {@code arm} (via select/scan) — null state,
 * null code, blank code, a matching code and a non-matching code; {@code consume}
 * — null and non-null state; {@code containers} delegation.
 */
class TareServiceTest {

    /**
     * Builds a service with a mocked settings source whose default tare is the
     * given value.
     *
     * @param defaultTare the administered default tare
     * @return the wired service
     */
    private TareService newService(BigDecimal defaultTare) {
        TareService service = new TareService();
        service.posSettingsService = mock(PosSettingsService.class);
        // The administered lists (BO-04-02-07), consulted for the containers.
        service.posListService = mock(com.intermarche.pos.service.PosListService.class);
        when(service.posSettingsService.defaultTare()).thenReturn(defaultTare);
        return service;
    }

    /**
     * Builds a product carrying the given TARE attribute value (null leaves the
     * article without one).
     *
     * @param tareValue the raw TARE attribute value, or null
     * @return the product
     */
    private Product productWithTare(String tareValue) {
        Product product = new Product();
        if (tareValue != null) {
            product.attributes.put("TARE", tareValue);
        }
        return product;
    }

    /**
     * {@code resolve} returns the armed container tare, overriding the article
     * tare and the default (container arm).
     */
    @Test
    void resolveReturnsArmedContainerTare() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        state.tare.containerTare = new BigDecimal("0.035");
        Product product = productWithTare("0.020");
        assertEquals(0, new BigDecimal("0.035").compareTo(service.resolve(product, state)));
    }

    /**
     * {@code resolve} returns the article tare when no container is armed and
     * the article declares one (container-absent, article-positive arm).
     */
    @Test
    void resolveReturnsArticleTareWhenNoContainer() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        Product product = productWithTare("0.020");
        assertEquals(0, new BigDecimal("0.020").compareTo(service.resolve(product, state)));
    }

    /**
     * {@code resolve} falls back to the administered default when no container
     * is armed and the article declares no tare (article-zero, default arm).
     */
    @Test
    void resolveFallsBackToDefaultTare() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        Product product = productWithTare("0");
        assertEquals(0, new BigDecimal("0.010").compareTo(service.resolve(product, state)));
    }

    /**
     * {@code resolve} tolerates a null state (no container possible) and a null
     * product, returning the default tare.
     */
    @Test
    void resolveWithNullStateAndNullProductReturnsDefault() {
        TareService service = newService(new BigDecimal("0.010"));
        assertEquals(0, new BigDecimal("0.010").compareTo(service.resolve(null, null)));
    }

    /**
     * {@code resolveForManual} returns the armed container tare over the line
     * snapshot and the default (container arm).
     */
    @Test
    void resolveForManualReturnsArmedContainerTare() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        state.tare.containerTare = new BigDecimal("0.045");
        TicketState.TicketItem item = new TicketState.TicketItem();
        item.tareKg = new BigDecimal("0.020");
        assertEquals(0, new BigDecimal("0.045").compareTo(service.resolveForManual(item, state)));
    }

    /**
     * {@code resolveForManual} returns the line's article-tare snapshot when no
     * container is armed and the snapshot is positive (item-positive arm).
     */
    @Test
    void resolveForManualReturnsLineSnapshot() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        TicketState.TicketItem item = new TicketState.TicketItem();
        item.tareKg = new BigDecimal("0.020");
        assertEquals(0, new BigDecimal("0.020").compareTo(service.resolveForManual(item, state)));
    }

    /**
     * {@code resolveForManual} falls back to the default when the line snapshot
     * is null (item-tare-null arm).
     */
    @Test
    void resolveForManualNullSnapshotFallsBackToDefault() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        TicketState.TicketItem item = new TicketState.TicketItem();
        item.tareKg = null;
        assertEquals(0, new BigDecimal("0.010").compareTo(service.resolveForManual(item, state)));
    }

    /**
     * {@code resolveForManual} falls back to the default when the line snapshot
     * is zero (item-tare-zero arm).
     */
    @Test
    void resolveForManualZeroSnapshotFallsBackToDefault() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        TicketState.TicketItem item = new TicketState.TicketItem();
        item.tareKg = BigDecimal.ZERO;
        assertEquals(0, new BigDecimal("0.010").compareTo(service.resolveForManual(item, state)));
    }

    /**
     * {@code resolveForManual} tolerates a null item, returning the default
     * (item-null arm).
     */
    @Test
    void resolveForManualNullItemReturnsDefault() {
        TareService service = newService(new BigDecimal("0.010"));
        PosState state = new PosState();
        assertEquals(0, new BigDecimal("0.010").compareTo(service.resolveForManual(null, state)));
    }

    /**
     * {@code selectContainer} arms the tare and label of a matching administered
     * container and answers true (matching-code arm).
     */
    @Test
    void selectContainerArmsMatchingContainer() {
        TareService service = newService(BigDecimal.ZERO);
        when(service.posListService.tareContainers()).thenReturn(List.of(
                new PosSettingsService.TareContainer("BQS", "Barquette S", new BigDecimal("0.035"))));
        PosState state = new PosState();
        assertTrue(service.selectContainer(state, "BQS"));
        assertEquals(0, new BigDecimal("0.035").compareTo(state.tare.containerTare));
        assertEquals("Barquette S", state.tare.containerLabel);
    }

    /**
     * {@code selectContainer} arms nothing and answers false for a code absent
     * from the administered list (non-matching-code arm).
     */
    @Test
    void selectContainerRejectsUnknownCode() {
        TareService service = newService(BigDecimal.ZERO);
        when(service.posListService.tareContainers()).thenReturn(List.of(
                new PosSettingsService.TareContainer("BQS", "Barquette S", new BigDecimal("0.035"))));
        PosState state = new PosState();
        assertFalse(service.selectContainer(state, "NOPE"));
        assertNull(state.tare.containerTare);
    }

    /**
     * {@code scanContainer} arms the container whose code matches the scanned
     * barcode (the scan path shares the same lookup as selection).
     */
    @Test
    void scanContainerArmsMatchingBarcode() {
        TareService service = newService(BigDecimal.ZERO);
        when(service.posListService.tareContainers()).thenReturn(List.of(
                new PosSettingsService.TareContainer("3800000000010", "Filet", new BigDecimal("0.028"))));
        PosState state = new PosState();
        assertTrue(service.scanContainer(state, "3800000000010"));
        assertEquals(0, new BigDecimal("0.028").compareTo(state.tare.containerTare));
    }

    /**
     * {@code arm} rejects a null state (null-state arm) — asserted through
     * {@code selectContainer}.
     */
    @Test
    void selectContainerRejectsNullState() {
        TareService service = newService(BigDecimal.ZERO);
        assertFalse(service.selectContainer(null, "BQS"));
    }

    /**
     * {@code arm} rejects a null code (null-code arm) and a blank code
     * (blank-code arm) — asserted through {@code selectContainer}.
     */
    @Test
    void selectContainerRejectsNullAndBlankCode() {
        TareService service = newService(BigDecimal.ZERO);
        PosState state = new PosState();
        assertFalse(service.selectContainer(state, null));
        assertFalse(service.selectContainer(state, "   "));
    }

    /**
     * {@code consume} clears the armed container on a non-null state
     * (non-null-state arm).
     */
    @Test
    void consumeClearsTheContainer() {
        TareService service = newService(BigDecimal.ZERO);
        PosState state = new PosState();
        state.tare.containerTare = new BigDecimal("0.035");
        service.consume(state);
        assertNull(state.tare.containerTare);
    }

    /**
     * {@code consume} tolerates a null state (null-state arm) without throwing.
     */
    @Test
    void consumeToleratesNullState() {
        TareService service = newService(BigDecimal.ZERO);
        service.consume(null);
    }

    /**
     * {@code containers} delegates to the administered list of the settings.
     */
    @Test
    void containersDelegatesToSettings() {
        TareService service = newService(BigDecimal.ZERO);
        List<PosSettingsService.TareContainer> list = List.of(
                new PosSettingsService.TareContainer("BQS", "Barquette S", new BigDecimal("0.035")));
        when(service.posListService.tareContainers()).thenReturn(list);
        assertEquals(list, service.containers());
    }
}
