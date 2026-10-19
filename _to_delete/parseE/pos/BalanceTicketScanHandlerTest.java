package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.balance.BalanceTicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BalanceTicketScanHandler}.
 * <p>
 * The handler is a {@code @Priority(1)} link recognizing counter-ticket barcodes
 * laid out as {@code PP RRRRRRRRRR K}: an in-store prefix it owns, the 10-digit
 * counter reference kept verbatim (leading zeros included), and a verified EAN13
 * check digit. Everything it does not own — a null code, a non-2x code, a 2x code
 * whose prefix belongs to another handler, a bad check digit — falls through
 * untouched so the rest of the chain can try. What it does own it consumes
 * unconditionally, refusal included, and it delegates every decision to
 * {@link BalanceTicketService}: this handler decides WHAT the code is, never what
 * happens to it. The service is a Mockito mock; the prefix list is set directly on
 * the package-private config field.
 */
class BalanceTicketScanHandlerTest {

    /** A valid counter-ticket barcode: prefix 27, reference 0012345678. */
    private static final String COUNTER_CODE = "2700123456781";

    /** The reference the handler must extract from {@link #COUNTER_CODE}. */
    private static final String REFERENCE = "0012345678";

    /** {@link #COUNTER_CODE} with a deliberately wrong check digit. */
    private static final String BAD_CHECKSUM_CODE = "2700123456789";

    /** A 2x code whose prefix (21) belongs to the weighted-sticker handler. */
    private static final String OTHER_PREFIX_CODE = "2101234001509";

    /** A code outside the 2x in-store family altogether. */
    private static final String RETAIL_CODE = "3560070000000";

    /**
     * Builds a handler owning prefix 27 and wired with the supplied service.
     *
     * @param service the pick-up service mock
     * @return a ready-to-test handler
     */
    private BalanceTicketScanHandler newHandler(BalanceTicketService service) {
        BalanceTicketScanHandler handler = new BalanceTicketScanHandler();
        handler.balanceTicketPrefixes = List.of("27");
        handler.balanceTicketService = service;
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is a mock too.
     *
     * @return the wired state mock
     */
    private PosState newState() {
        PosState state = mock(PosState.class);
        state.ticket = mock(TicketState.class);
        return state;
    }

    /**
     * An already-handled context short-circuits: no pick-up is attempted and the
     * flag stays set for the rest of the chain.
     */
    @Test
    void alreadyHandledShortCircuits() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ScanContext ctx = new ScanContext(COUNTER_CODE, newState());
        ctx.handled = true;
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertTrue(ctx.handled);
        verifyNoInteractions(service);
    }

    /**
     * A null code is not a counter ticket: the context stays unhandled and no
     * pick-up is attempted.
     */
    @Test
    void nullCodeFallsThrough() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ScanContext ctx = new ScanContext(null, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(service);
    }

    /**
     * A regular retail EAN outside the 2x family falls through to the catalog
     * handler untouched.
     */
    @Test
    void nonInStoreCodeFallsThrough() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ScanContext ctx = new ScanContext(RETAIL_CODE, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(service);
    }

    /**
     * A 2x code whose prefix belongs to the weighted-sticker handler falls
     * through: the two handlers share priority 1 and must stay disjoint.
     */
    @Test
    void foreignPrefixFallsThrough() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ScanContext ctx = new ScanContext(OTHER_PREFIX_CODE, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(service);
    }

    /**
     * An owned prefix with an invalid check digit falls through rather than
     * erroring: a 2-prefixed retail EAN must still reach the catalog handler.
     */
    @Test
    void badChecksumFallsThrough() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ScanContext ctx = new ScanContext(BAD_CHECKSUM_CODE, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(service);
    }

    /**
     * A well-formed counter barcode hands the reference — leading zeros kept —
     * to the service and consumes the code.
     */
    @Test
    void wellFormedCodeIsPickedUp() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        PosState state = newState();
        ScanContext ctx = new ScanContext(COUNTER_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(service).integrate(eq(state), eq(REFERENCE));
    }

    /**
     * Opens a static mock answering NO administered range, which is what every
     * store looked like before the destination column existed.
     *
     * @return the static mock
     */
    private MockedStatic<ArticleBarcodeRange> noAdministeredRange() {
        MockedStatic<ArticleBarcodeRange> ranges = mockStatic(ArticleBarcodeRange.class);
        ranges.when(() -> ArticleBarcodeRange.findMatching(any())).thenReturn(null);
        return ranges;
    }

    /**
     * Builds a range administered as a counter-ticket plan, reading the
     * reference where the store says it is (BO-03-06-56).
     *
     * @param prefix the literal head
     * @param length the total code length
     * @param position the 0-based start of the reference
     * @param referenceLength the number of characters of the reference
     * @return the administered range
     */
    private ArticleBarcodeRange bagRange(String prefix, int length, int position,
                                         int referenceLength) {
        ArticleBarcodeRange range = new ArticleBarcodeRange();
        range.code = "SACHET_" + prefix;
        range.label = "Sachet comptoir";
        range.prefix = prefix;
        range.codeLength = length;
        range.codeKind = com.intermarche.pos.domain.barcode.CouponField.Kind.NUMERIC;
        range.articlePosition = position;
        range.articleLength = referenceLength;
        range.valueSource = ArticleBarcodeRange.ValueSource.PRICE;
        range.valuePosition = 0;
        range.valueLength = 1;
        range.checkDigit = false;
        range.purpose = ArticleBarcodeRange.Purpose.BALANCE_TICKET;
        range.matchPattern = "^" + prefix + "\\d{" + (length - prefix.length()) + "}$";
        return range;
    }

    /**
     * Opens a static mock answering the given range for every code.
     *
     * @param range the administered range
     * @return the static mock
     */
    private MockedStatic<ArticleBarcodeRange> administeredRange(ArticleBarcodeRange range) {
        MockedStatic<ArticleBarcodeRange> ranges = mockStatic(ArticleBarcodeRange.class);
        ranges.when(() -> ArticleBarcodeRange.findMatching(any())).thenReturn(range);
        return ranges;
    }

    /**
     * An ADMINISTERED counter-ticket range reads the reference where the store
     * says it is, in a code of its own length — which is what proves the layout
     * is administered and not compiled in (BO-03-06-56).
     */
    @Test
    void anAdministeredRangeReadsItsOwnLayout() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        PosState state = newState();
        ScanContext ctx = new ScanContext("9900000987654321", state);
        try (MockedStatic<ArticleBarcodeRange> ranges =
                administeredRange(bagRange("99", 16, 4, 12))) {
            newHandler(service).handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(service).integrate(eq(state), eq("000987654321"));
    }

    /**
     * The administered reference keeps its leading zeros: the shop matches it
     * character for character against what the scale pushed.
     */
    @Test
    void theAdministeredReferenceKeepsItsLeadingZeros() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        PosState state = newState();
        ScanContext ctx = new ScanContext(COUNTER_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> ranges =
                administeredRange(bagRange("27", 13, 2, 10))) {
            newHandler(service).handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(service).integrate(eq(state), eq(REFERENCE));
    }

    /**
     * A range administered for something else does not make a counter ticket of
     * the code: the historical 2x layout decides, and a retail code still falls
     * through (the other leg of the purpose guard).
     */
    @Test
    void aRangeAdministeredForAnotherPurposeIsIgnoredHere() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ArticleBarcodeRange other = bagRange("27", 13, 2, 10);
        other.purpose = ArticleBarcodeRange.Purpose.ARTICLE;
        ScanContext retail = new ScanContext(RETAIL_CODE, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = administeredRange(other)) {
            newHandler(service).handle(retail);
        }
        assertFalse(retail.handled);
        verifyNoInteractions(service);
    }

    /**
     * An administered range whose check digit is required refuses a 13-character
     * code failing it, and the historical layout is then the only thing left to
     * answer — which it does not, the digit being wrong there too.
     */
    @Test
    void theAdministeredCheckDigitIsHonoured() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ArticleBarcodeRange range = bagRange("27", 13, 2, 10);
        range.checkDigit = true;
        ScanContext ctx = new ScanContext(BAD_CHECKSUM_CODE, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            newHandler(service).handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(service);
    }

    /**
     * An administered range whose reference segment falls outside the code
     * reads nothing, and the code is left to the historical layout.
     */
    @Test
    void anUnreadableAdministeredReferenceFallsBack() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        PosState state = newState();
        ScanContext ctx = new ScanContext(COUNTER_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> ranges =
                administeredRange(bagRange("27", 13, 40, 10))) {
            newHandler(service).handle(ctx);
        }
        // The 2x fallback recognizes it and picks it up, exactly as before.
        assertTrue(ctx.handled);
        verify(service).integrate(eq(state), eq(REFERENCE));
    }

    /**
     * A refused pick-up still consumes the code: letting it walk on would end as
     * "article inconnu" printed over the refusal the cashier must read.
     */
    @Test
    void refusedPickUpStillConsumesTheCode() {
        BalanceTicketService service = mock(BalanceTicketService.class);
        ScanContext ctx = new ScanContext(COUNTER_CODE, newState());
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler(service).handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(service).integrate(any(), any());
    }
}
