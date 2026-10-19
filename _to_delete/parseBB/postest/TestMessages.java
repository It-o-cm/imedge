package com.intermarche.pos.ui;

import com.intermarche.pos.service.PosSettingsService;

import java.util.List;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Builds the {@link Messages} that a unit test hands to a service it drives.
 *
 * <p>WHY A FACTORY AND NOT FOUR LINES PER TEST. Every service that speaks to the
 * operator now reads its sentences from a bundle, so every test of such a
 * service has to wire a resolver. Four lines repeated in sixteen test classes is
 * four lines to fix in sixteen places the day the resolver gains a collaborator.
 *
 * <p>THE BUNDLES ARE THE REAL ONES. The resolver returned reads
 * {@code messages/msg_fr.properties} from the classpath, so an assertion keeps
 * the literal French sentence the register shows — a test that stubbed the
 * resolver would pass while the bundle was empty, which is the one failure mode
 * worth catching here.
 *
 * <p>It lives in {@code com.intermarche.pos.ui} because {@link Messages}'s
 * collaborator fields are package-private: wiring them is this package's
 * business, and the factory is what the other packages are given instead.
 */
public final class TestMessages {

    /**
     * Not instantiable: a holder of one static factory.
     */
    private TestMessages() {
    }

    /**
     * A resolver answering the French bundle, with no operator choice, no
     * administered operator language and no second language.
     *
     * @return a resolver ready to be assigned to a service's {@code i18n} field
     */
    public static Messages french() {
        Messages messages = new Messages();
        messages.state = new PosState();
        messages.posSettingsService = mock(PosSettingsService.class);
        // LENIENT, and that is the whole point of the factory. Most tests of a
        // service never resolve a label: they drive a path that posts no
        // message. Under Mockito's strict stubs those three answers would then
        // be reported as unnecessary and fail a test that has nothing to do with
        // the language — the factory must be free to over-provide.
        lenient().when(messages.posSettingsService.systemLanguage()).thenReturn("fr");
        lenient().when(messages.posSettingsService.availableLanguages())
                .thenReturn(List.of("fr"));
        lenient().when(messages.posSettingsService.customerSecondLanguage()).thenReturn("");
        return messages;
    }

    /**
     * A resolver answering the French bundle and sharing the register state the
     * test already built, for a service whose message depends on that state.
     *
     * @param state the register state the test drives
     * @return a resolver ready to be assigned to a service's {@code i18n} field
     */
    public static Messages french(PosState state) {
        Messages messages = french();
        messages.state = state;
        return messages;
    }
    /**
     * A register state carrying a French resolver, for a test driving a
     * sub-state that reaches its labels through its parent.
     *
     * @return the register state, its resolver wired
     */
    public static PosState stateWithMessages() {
        PosState state = new PosState();
        state.i18n = french();
        return state;
    }
}
