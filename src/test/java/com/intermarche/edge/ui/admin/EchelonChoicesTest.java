package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.TpvType;
import com.intermarche.edge.domain.sync.RegisterPresence;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link EchelonChoices} (BO-01-01-03, BO-01-03-04), targeting
 * 100% branch coverage: the node-level echelons of the organisation tree, then
 * the types of point of contact and the points of contact, labelled or not.
 */
class EchelonChoicesTest {

    /**
     * The echelons are read from the tree, the whole organisation first, the
     * registers last; a register without label, or with a blank one, shows
     * its identifier alone; the registers seen on the line but not declared
     * follow, sorted and once; an echelon the edited account holds that no
     * list offers closes the list.
     */
    @Test
    void theEchelonsAreReadFromTheTree() {
        Country france = new Country();
        france.code = "FR";
        france.name = "France";
        Enseigne itm = new Enseigne();
        itm.code = "ITM";
        itm.name = "Intermarché";
        Pdv pdv = new Pdv();
        pdv.pdvNumber = "07039";
        pdv.name = "Vannes";
        CheckoutPoint labelled = new CheckoutPoint();
        labelled.terminalId = "POS01";
        labelled.label = "Caisse 1";
        CheckoutPoint bare = new CheckoutPoint();
        bare.terminalId = "POS02";
        CheckoutPoint blank = new CheckoutPoint();
        blank.terminalId = "POS03";
        blank.label = " ";
        try (MockedStatic<Country> countries = mockStatic(Country.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<CheckoutPoint> points = mockStatic(CheckoutPoint.class);
             MockedStatic<RegisterPresence> presences = mockStatic(RegisterPresence.class)) {
            countries.when(Country::listAllOrdered).thenReturn(List.of(france));
            enseignes.when(Enseigne::listAllOrdered).thenReturn(List.of(itm));
            pdvs.when(Pdv::listAllOrdered).thenReturn(List.of(pdv));
            points.when(CheckoutPoint::listAllOrdered).thenReturn(List.of(labelled, bare, blank));
            presences.when(RegisterPresence::allByAge).thenReturn(List.of(seen("POS09"), seen("POS01"), seen("POS04")));
            List<EchelonChoices.Choice> node = EchelonChoices.nodeLevel();
            assertEquals(List.of("*", "COUNTRY:FR", "ENSEIGNE:ITM", "PDV:07039"),
                    node.stream().map(choice -> choice.value).toList());
            assertEquals("Enseigne ITM — Intermarché", node.get(2).label);
            List<EchelonChoices.Choice> account = EchelonChoices.accountLevel(null);
            assertEquals(4 + TpvType.values().length + 5, account.size());
            assertEquals("TPV_TYPE:CASH_REGISTER", account.get(4).value);
            assertEquals("Type de TPV — Caisse", account.get(4).label);
            int last = account.size() - 1;
            assertEquals("TPV POS01 — Caisse 1", account.get(last - 4).label);
            assertEquals("TPV POS02", account.get(last - 3).label);
            assertEquals("TPV POS03", account.get(last - 2).label);
            assertEquals("TPV POS04 — vu sur la ligne", account.get(last - 1).label);
            assertEquals("TPV POS09 — vu sur la ligne", account.get(last).label);
            List<EchelonChoices.Choice> held = EchelonChoices.accountLevel(List.of("PDV:07039", "TPV:POS77"));
            assertEquals(account.size() + 1, held.size());
            assertEquals("TPV:POS77", held.get(held.size() - 1).value);
            assertEquals("TPV POS77", held.get(held.size() - 1).label);
        }
    }

    /**
     * Builds a register seen on the line.
     *
     * @param terminalId the register's identifier
     * @return the presence
     */
    private static RegisterPresence seen(String terminalId) {
        RegisterPresence presence = new RegisterPresence();
        presence.terminalId = terminalId;
        return presence;
    }
}
