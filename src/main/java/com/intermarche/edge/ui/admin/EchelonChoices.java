package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.EchelonScope;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.TpvType;
import com.intermarche.edge.domain.sync.RegisterPresence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The echelons an operator may pick on the account and assignment screens
 * (BO-01-01-03, BO-01-03-04), read from the organisation tree this node
 * holds, so a scope is chosen rather than typed.
 */
public final class EchelonChoices {

    /** One pickable echelon. */
    public static final class Choice {

        /** The stored scope. */
        public final String value;

        /** The operator-facing label. */
        public final String label;

        /**
         * Builds a choice.
         *
         * @param value the stored scope
         * @param label the operator-facing label
         */
        public Choice(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /**
     * Not instantiable: static helpers only.
     */
    private EchelonChoices() {
    }

    /**
     * Lists the node-level echelons: the whole organisation, every country,
     * every enseigne and every point of sale.
     *
     * @return the choices, the whole organisation first
     */
    public static List<Choice> nodeLevel() {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(EchelonScope.ALL, EchelonScope.label(EchelonScope.ALL)));
        for (Country country : Country.listAllOrdered()) {
            choices.add(new Choice("COUNTRY:" + country.code, "Pays " + country.code + " — " + country.name));
        }
        for (Enseigne enseigne : Enseigne.listAllOrdered()) {
            choices.add(new Choice("ENSEIGNE:" + enseigne.code, "Enseigne " + enseigne.code + " — " + enseigne.name));
        }
        for (Pdv pdv : Pdv.listAllOrdered()) {
            choices.add(new Choice("PDV:" + pdv.pdvNumber, "PDV " + pdv.pdvNumber + " — " + pdv.name));
        }
        return choices;
    }

    /**
     * Lists every echelon an account may be attached to: the node-level ones,
     * then every type of point of contact and every point of contact — the
     * points of caisse declared on this node, then the registers seen on the
     * line without being declared, then the echelons the edited account
     * already holds and that neither list offers, so that saving the account
     * never drops one.
     *
     * @param held the scopes of the edited account, possibly null or empty
     * @return the choices
     */
    public static List<Choice> accountLevel(List<String> held) {
        List<Choice> choices = nodeLevel();
        for (TpvType type : TpvType.values()) {
            choices.add(new Choice("TPV_TYPE:" + type.name(), "Type de TPV — " + type.getLabel()));
        }
        Set<String> offered = new HashSet<>();
        for (Choice choice : choices) {
            offered.add(choice.value);
        }
        for (CheckoutPoint point : CheckoutPoint.listAllOrdered()) {
            add(choices, offered, "TPV:" + point.terminalId, "TPV " + point.terminalId
                    + (point.label == null || point.label.isBlank() ? "" : " — " + point.label));
        }
        List<String> seen = new ArrayList<>();
        for (RegisterPresence presence : RegisterPresence.allByAge()) {
            seen.add(presence.terminalId);
        }
        Collections.sort(seen);
        for (String terminalId : seen) {
            add(choices, offered, "TPV:" + terminalId, "TPV " + terminalId + " — vu sur la ligne");
        }
        for (String scope : held == null ? List.<String>of() : held) {
            add(choices, offered, scope, EchelonScope.label(scope));
        }
        return choices;
    }

    /**
     * Appends a choice unless its scope is already offered.
     *
     * @param choices the choices being built
     * @param offered the scopes already offered, updated
     * @param value the scope
     * @param label the operator-facing label
     */
    private static void add(List<Choice> choices, Set<String> offered, String value, String label) {
        if (offered.add(value)) {
            choices.add(new Choice(value, label));
        }
    }
}
