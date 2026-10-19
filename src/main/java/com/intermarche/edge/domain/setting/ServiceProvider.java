package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.List;

/**
 * A declared SERVICE PROVIDER: the third party the register or the node talks to
 * for something it does not do itself (BO-03-11-01 to 06).
 *
 * <p>Why a table and not a configuration property: every protocol of the plan —
 * the dematerialised-product issuers, Catalina, the fiscal web service, the
 * electronic labels, the price kiosk — needs the same four things said about it,
 * an address, a credential, a protocol and when it is called. Declared here, a
 * new protocol is a row plus its client; hard-wired in deployment properties,
 * each one would invent its own three property names, and nobody could list what
 * the store is connected to.
 *
 * <p>What this table is NOT: the protocol itself. A row says which provider
 * answers for a service and how to reach it; the conversation with it is code,
 * and a provider whose protocol this release does not implement is a row the node
 * never calls — declaring it is not the same as supporting it.
 *
 * <p>The CREDENTIAL is write-only by contract: {@link #authKey} is stored because
 * the call needs it, and no screen, export or log ever shows it — the back office
 * displays whether one is set, never its value. A credential that can be read back
 * from a listing is a credential that travels in screenshots.
 */
@Entity
@Table(name = "service_provider")
public class ServiceProvider extends PanacheEntity {

    /**
     * The kind of service a provider answers for.
     *
     * <p>The kind, not the protocol: two issuers of gift cards are the same kind
     * of provider spoken to in two protocols, and the register chooses a provider
     * by what it needs done.
     */
    public enum ProviderType {

        /** Gift cards, coupon boxes and other dematerialised products. */
        DEMATERIALISE("Produits dématérialisés"),

        /** A loyalty server. */
        FIDELITE("Fidélité"),

        /** A payment or meal-voucher acquirer. */
        MONETIQUE("Monétique"),

        /** Electronic shelf labels. */
        ETIQUETTE("Étiquettes électroniques"),

        /** A price kiosk the customer scans at. */
        BORNE_PRIX("Borne de prix"),

        /** A marketing or decisional collector. */
        DECISIONNEL("Décisionnel"),

        /** A tax administration endpoint. */
        FISCAL("Fiscal"),

        /** Video surveillance, single-queue and other floor systems. */
        EXPLOITATION("Exploitation magasin");

        /** The operator-facing label. */
        private final String label;

        /**
         * Builds a provider type.
         *
         * @param label the operator-facing label
         */
        ProviderType(String label) {
            this.label = label;
        }

        /**
         * Returns the operator-facing label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * The protocol a provider is spoken to in — the named ones of the plan, plus
     * a generic HTTP exchange for a provider that has no protocol of its own.
     */
    public enum ProviderProtocol {

        /** Wynid, for dematerialised products. */
        WYNID("Wynid"),

        /** Prosodie, for dematerialised products. */
        PROSODIE("Prosodie"),

        /** ATOS, for dematerialised products. */
        ATOS("ATOS"),

        /** NEXO Retailer, the protocol the dematerialised products use today. */
        NEXO_RETAILER("NEXO Retailer"),

        /** Catalina, over its own POS web-service interface. */
        CATALINA("Catalina"),

        /** SAFT, the fiscal web service. */
        SAFT("SAFT"),

        /** An electronic-shelf-label supplier's own protocol. */
        EEG("Étiquettes (EEG)"),

        /** A plain credentialed HTTP/JSON exchange. */
        HTTP_JSON("HTTP/JSON");

        /** The operator-facing label. */
        private final String label;

        /**
         * Builds a protocol.
         *
         * @param label the operator-facing label
         */
        ProviderProtocol(String label) {
            this.label = label;
        }

        /**
         * Returns the operator-facing label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * When the register calls the provider during a sale (BO-03-11-05).
     *
     * <p>The requirement spells out three moments and warns that the choice
     * changes the checkout journey: calling at the scan blocks the line until the
     * provider answers, calling at the end of payment cannot refuse the sale any
     * more. A provider the sale never calls — a nightly collector — carries
     * {@link #AUCUNE}.
     */
    public enum CallPhase {

        /** At the scan of the product. */
        AU_SCAN("Au scan produit"),

        /** At the total, before settlement. */
        AU_TOTAL("Au total"),

        /** At the end of payment. */
        FIN_PAIEMENT("À la fin du paiement"),

        /** Never during a sale. */
        AUCUNE("Hors vente");

        /** The operator-facing label. */
        private final String label;

        /**
         * Builds a call phase.
         *
         * @param label the operator-facing label
         */
        CallPhase(String label) {
            this.label = label;
        }

        /**
         * Returns the operator-facing label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /** The functional code of the provider, the key the back office names it by. */
    @Column(name = "code", unique = true, nullable = false, length = 30)
    public String code;

    /** The operator-facing label. */
    @Column(name = "label", length = 120)
    public String label;

    /**
     * The kind of service this provider answers for (BO-03-11-03).
     *
     * <p>Named {@code providerType} and not {@code type}: {@code type} is a
     * reserved word of the query language, and a field named after one is a query
     * that fails the first time somebody filters on it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 20)
    public ProviderType providerType = ProviderType.DEMATERIALISE;

    /** The protocol it is spoken to in (BO-03-11-04/06). */
    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 20)
    public ProviderProtocol protocol = ProviderProtocol.HTTP_JSON;

    /** Its address: a URL, or a host and a port (BO-03-11-04). */
    @Column(name = "endpoint", length = 300)
    public String endpoint;

    /**
     * The credential the call carries (BO-03-11-04).
     *
     * <p>Never displayed, never exported, never logged: see the class contract.
     * The screens read {@link #hasCredential()} instead.
     */
    @Column(name = "auth_key", length = 300)
    public String authKey;

    /** When a sale calls this provider (BO-03-11-05). */
    @Enumerated(EnumType.STRING)
    @Column(name = "call_phase", nullable = false, length = 20)
    public CallPhase callPhase = CallPhase.AUCUNE;

    /**
     * The article selection this provider serves (BO-03-11-06), by its
     * administered name, or null when it serves no particular list.
     *
     * <p>By NAME and not by a foreign key: a selection is administered content
     * that an operator renames and deletes, and a provider must survive that
     * rather than block it.
     */
    @Column(name = "article_selection", length = 120)
    public String articleSelection;

    /**
     * The document template printed for this provider's products (BO-03-11-06), by
     * its code, or null when the standard ticket is used.
     */
    @Column(name = "document_template_code", length = 30)
    public String documentTemplateCode;

    /** Whether the provider is in service. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Tells whether a credential is stored for this provider.
     *
     * <p>The only thing any screen is allowed to know about {@link #authKey}. A
     * blank key counts as none: a credential made of spaces authenticates nothing
     * and would otherwise read as configured.
     *
     * @return true when a non-blank credential is stored
     */
    public boolean hasCredential() {
        return authKey != null && !authKey.isBlank();
    }

    /**
     * Tells whether this provider is called during a sale.
     *
     * @return true when its call phase is one of the three selling moments
     */
    public boolean isCalledDuringSale() {
        return callPhase != null && callPhase != CallPhase.AUCUNE;
    }

    /**
     * Tells whether this provider can actually be called: in service, with an
     * address and a credential.
     *
     * <p>Three legs and not one, because they fail for different reasons and the
     * screen says which: a provider out of service was switched off on purpose, a
     * provider without an address was never finished, and a provider without a
     * credential will be refused by the other side.
     *
     * @return true when the provider is in service, addressed and credentialed
     */
    public boolean isReachable() {
        return active && endpoint != null && !endpoint.isBlank() && hasCredential();
    }

    /**
     * Finds a provider by its functional code.
     *
     * @param code the code, possibly null
     * @return the provider, or null when the code is blank or unknown
     */
    public static ServiceProvider findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Lists the providers that answer for one kind of service and are in service.
     *
     * @param providerType the kind of service
     * @return the active providers of that kind, in code order
     */
    public static List<ServiceProvider> listActiveOfType(ProviderType providerType) {
        return list("providerType = ?1 and active = true order by code", providerType);
    }

    /**
     * Lists every declared provider, in code order.
     *
     * @return the providers, empty when none is declared
     */
    public static List<ServiceProvider> listAllOrdered() {
        return list("order by code");
    }
}
