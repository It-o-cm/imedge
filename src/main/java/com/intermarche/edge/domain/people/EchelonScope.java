package com.intermarche.edge.domain.people;

import com.intermarche.edge.domain.store.NodeEchelon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The echelon scopes an account or a profile assignment is attached to
 * (BO-01-01-03, BO-01-02-03, BO-01-03-04).
 *
 * <p>A scope is written {@code KIND:CODE} — {@code COUNTRY:FR},
 * {@code ENSEIGNE:ITM}, {@code PDV:12345}, {@code TPV_TYPE:SELF_CHECKOUT},
 * {@code TPV:POS01} — or {@code *} for the whole organisation. A bare code,
 * the form the profile assignments were stored in before, still reads as a
 * point of sale.
 *
 * <p>The first four kinds place the holder at NODE level: which points of
 * sale they belong to. The two TPV kinds restrict, inside those points of
 * sale, which registers they may sign in to; they never exclude a node.
 */
public final class EchelonScope {

    /** The whole organisation. */
    public static final String ALL = "*";

    /** The kinds of echelon a scope can name. */
    public enum Kind {

        /** The whole organisation. */
        ALL,

        /** A country, by its code. */
        COUNTRY,

        /** An enseigne, by its code. */
        ENSEIGNE,

        /** A point of sale, by its number. */
        PDV,

        /** A type of point of contact (register, self-checkout...), by its name. */
        TPV_TYPE,

        /** One point of contact, by its terminal identifier. */
        TPV
    }

    /**
     * Not instantiable: static helpers only.
     */
    private EchelonScope() {
    }

    /**
     * Normalises a typed scope into its canonical form.
     *
     * @param raw the scope as typed or stored, possibly null
     * @return the canonical scope ({@code *} or {@code KIND:CODE}), or null when
     *         the text names no known kind or no code
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (ALL.equals(trimmed)) {
            return ALL;
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return Kind.PDV.name() + ":" + trimmed;
        }
        Kind kind = parseKind(trimmed.substring(0, colon));
        String code = trimmed.substring(colon + 1).trim();
        if (kind == null || kind == Kind.ALL || code.isEmpty()) {
            return null;
        }
        return kind.name() + ":" + (kind == Kind.TPV_TYPE ? code.toUpperCase(Locale.ROOT) : code);
    }

    /**
     * Reads the kind a canonical or legacy scope names.
     *
     * @param scope the scope, possibly null
     * @return its kind, {@link Kind#ALL} for null, blank or {@code *}, null when invalid
     */
    public static Kind kindOf(String scope) {
        if (scope == null || scope.isBlank() || ALL.equals(scope.trim())) {
            return Kind.ALL;
        }
        String canonical = normalize(scope);
        return canonical == null ? null : parseKind(canonical.substring(0, canonical.indexOf(':')));
    }

    /**
     * Reads the code a scope names.
     *
     * @param scope the scope, possibly null
     * @return the code, or an empty string for the whole organisation or an invalid scope
     */
    public static String codeOf(String scope) {
        String canonical = normalize(scope);
        if (canonical == null || ALL.equals(canonical)) {
            return "";
        }
        return canonical.substring(canonical.indexOf(':') + 1);
    }

    /**
     * Splits a stored or typed list of scopes, keeping only the valid ones,
     * canonical and without duplicates.
     *
     * @param joined the scopes separated by semicolons, commas or line breaks, possibly null
     * @return the canonical scopes in their order, empty when none
     */
    public static List<String> parseList(String joined) {
        Set<String> scopes = new LinkedHashSet<>();
        if (joined != null) {
            for (String part : joined.split("[;,\\r\\n]+")) {
                String canonical = normalize(part);
                if (canonical != null) {
                    scopes.add(canonical);
                }
            }
        }
        return new ArrayList<>(scopes);
    }

    /**
     * Tells which parts of a typed list are not valid scopes.
     *
     * @param joined the typed scopes, possibly null
     * @return the parts refused, empty when every part is valid
     */
    public static List<String> invalidParts(String joined) {
        List<String> refused = new ArrayList<>();
        if (joined != null) {
            for (String part : joined.split("[;,\\r\\n]+")) {
                if (!part.isBlank() && normalize(part) == null) {
                    refused.add(part.trim());
                }
            }
        }
        return refused;
    }

    /**
     * Joins scopes into their stored form.
     *
     * @param scopes the canonical scopes, possibly null
     * @return the scopes separated by semicolons, or null when there is none
     */
    public static String join(List<String> scopes) {
        return scopes == null || scopes.isEmpty() ? null : String.join(";", scopes);
    }

    /**
     * Tells whether a scope places its holder at node level (organisation,
     * country, enseigne or point of sale) rather than restricting registers.
     *
     * @param scope the scope
     * @return true for {@code *}, COUNTRY, ENSEIGNE and PDV scopes
     */
    public static boolean isNodeLevel(String scope) {
        Kind kind = kindOf(scope);
        return kind != null && kind != Kind.TPV_TYPE && kind != Kind.TPV;
    }

    /**
     * Tells whether one node-level scope covers a node.
     *
     * @param scope the scope
     * @param node the node's place in the organisation, possibly null
     * @return true when the scope is the whole organisation or names the node's
     *         country, enseigne or point of sale; a register-level scope covers every node
     */
    public static boolean coversNode(String scope, NodeEchelon node) {
        Kind kind = kindOf(scope);
        if (kind == null) {
            return false;
        }
        String code = codeOf(scope);
        return switch (kind) {
            case ALL, TPV_TYPE, TPV -> true;
            case COUNTRY -> node != null && code.equals(node.country);
            case ENSEIGNE -> node != null && code.equals(node.enseigne);
            case PDV -> node != null && code.equals(node.pdv);
        };
    }

    /**
     * Tells whether a list of scopes admits its holder on a node: no node-level
     * scope means everywhere, otherwise one of them must cover the node.
     *
     * @param scopes the holder's canonical scopes, possibly null
     * @param node the node's place in the organisation, possibly null
     * @return true when the holder belongs to the node
     */
    public static boolean admitsNode(List<String> scopes, NodeEchelon node) {
        boolean anyNodeLevel = false;
        for (String scope : scopes == null ? List.<String>of() : scopes) {
            if (!isNodeLevel(scope)) {
                continue;
            }
            anyNodeLevel = true;
            if (coversNode(scope, node)) {
                return true;
            }
        }
        return !anyNodeLevel;
    }

    /**
     * Tells whether a list of scopes admits its holder on one register of a
     * node: the node must admit them, and when they carry register-level
     * scopes one of them must name the register or its type.
     *
     * @param scopes the holder's canonical scopes, possibly null
     * @param node the node's place in the organisation, possibly null
     * @param terminalId the register's identifier, possibly null
     * @param tpvType the register's type name, possibly null when the register is not declared
     * @return true when the holder may sign in to that register
     */
    public static boolean admitsRegister(List<String> scopes, NodeEchelon node,
                                         String terminalId, String tpvType) {
        if (!admitsNode(scopes, node)) {
            return false;
        }
        boolean anyRegisterLevel = false;
        for (String scope : scopes == null ? List.<String>of() : scopes) {
            Kind kind = kindOf(scope);
            if (kind != Kind.TPV && kind != Kind.TPV_TYPE) {
                continue;
            }
            anyRegisterLevel = true;
            String code = codeOf(scope);
            if (kind == Kind.TPV ? code.equals(terminalId) : code.equals(tpvType)) {
                return true;
            }
        }
        return !anyRegisterLevel;
    }

    /**
     * Renders a scope for the operator.
     *
     * @param scope the scope, possibly null
     * @return its French label, the raw text for an invalid scope
     */
    public static String label(String scope) {
        Kind kind = kindOf(scope);
        if (kind == null) {
            return scope;
        }
        String code = codeOf(scope);
        return switch (kind) {
            case ALL -> "Tous les échelons";
            case COUNTRY -> "Pays " + code;
            case ENSEIGNE -> "Enseigne " + code;
            case PDV -> "PDV " + code;
            case TPV_TYPE -> "Type de TPV " + code;
            case TPV -> "TPV " + code;
        };
    }

    /**
     * Resolves a kind from its name.
     *
     * @param name the name, any case
     * @return the kind, or null when unknown
     */
    private static Kind parseKind(String name) {
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        for (Kind kind : Kind.values()) {
            if (kind.name().equals(wanted)) {
                return kind;
            }
        }
        return null;
    }
}
