package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A MESSAGE THE STORE SENDS TO ITS REGISTERS (BO-03-08-01, -02, -04 and -08).
 *
 * <p>The register end of this channel was built long ago: a lane polls the node
 * every twenty seconds, shows the banner it gets on the cashier's screen, and
 * answers a survey back up. What never existed was the OTHER END — nothing in
 * the node ever produced a message, so the lane polled an endpoint that was not
 * served. This entity and its screen are that end.
 *
 * <p>WHAT THE QUESTIONNAIRE ASKS, and what each column answers: a period of
 * diffusion ({@link #startsAt}, {@link #endsAt}), a display duration
 * ({@link #durationSeconds}), the population concerned ({@link #audience} and
 * {@link #audienceValue}: every cashier, one island, one register, one
 * employee), the moment it is shown ({@link #trigger}: at once, at the opening
 * of a register, at its closing), and a confirmation sent back with the
 * cashier's name and the time ({@link #confirmation}).
 *
 * <p>A SURVEY IS A MESSAGE WITH A QUESTION. The register already knows how to
 * show one and how to answer it, so a question and its options are two columns
 * here rather than a second entity: a notice is a message whose question is
 * blank.
 */
@Entity
@Table(name = "store_messages",
        indexes = @Index(name = "idx_store_message_active", columnList = "active, starts_at"))
public class StoreMessage extends PanacheEntity {

    /** The text shown to the cashier; blank clears the banner. */
    @Column(name = "text", nullable = false, length = 500)
    public String text = "";

    /** The question asked, or null for a plain notice (EXP-89). */
    @Column(name = "question", length = 300)
    public String question;

    /** The answer options of the question, separated by semicolons. */
    @Column(name = "options", length = 500)
    public String options;

    /**
     * HOW the cashier answers the question (LC-03-07-06 to -09), or null for
     * the ordinary option buttons: a NUMERIC answer is keyed on the pad, a
     * POSTAL_CODE one is keyed and FORMAT-CHECKED, and an INFORMATIVE survey
     * is a label to validate with nothing to type.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "response_type", length = 20)
    public ResponseType responseType;

    /**
     * True when the cashier may NOT pass the survey by (LC-03-07-10): a
     * mandatory survey stays until it is answered, an optional one carries a
     * skip key.
     */
    @Column(name = "required", nullable = false)
    public boolean required = false;

    /**
     * The postal-code format a POSTAL_CODE answer must match
     * (LC-03-07-07): {@code C} is a digit and everything else is literal —
     * {@code CCCCC} for France, {@code CCCC} for Belgium, {@code CCCC-CCC}
     * for Portugal. Null when the answer is not a postal code.
     */
    @Column(name = "postal_format", length = 20)
    public String postalFormat;

    /**
     * The valid postal codes a POSTAL_CODE answer must belong to
     * (LC-03-07-08), separated by semicolons, or null when the format alone
     * decides.
     */
    @Column(name = "postal_codes", length = 500)
    public String postalCodes;

    /** When the message starts being served, or null for at once. */
    @Column(name = "starts_at")
    public LocalDateTime startsAt;

    /** When it stops being served, or null for no end. */
    @Column(name = "ends_at")
    public LocalDateTime endsAt;

    /** How long the banner stays on screen, zero for until it is replaced. */
    @Column(name = "duration_seconds", nullable = false)
    public int durationSeconds;

    /** Who the message is for. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    public Audience audience = Audience.ALL;

    /** The island, terminal or employee the audience names; blank for everyone. */
    @Column(name = "audience_value", length = 50)
    public String audienceValue;

    /** When the register shows it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_moment", nullable = false, length = 20)
    public Trigger trigger = Trigger.IMMEDIATE;

    /** What the cashier must do about it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation", nullable = false, length = 20)
    public Confirmation confirmation = Confirmation.NONE;

    /** Whether the message is served at all. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** When the message was written, for the administration list. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Whether this message applies to a register AT A GIVEN MOMENT.
     *
     * <p>Three questions and not one: is it switched on, are we inside its
     * period, and is this register in its audience. A period with no start
     * begins at once and one with no end never closes, because a store writing
     * "until further notice" should not have to invent a date.
     *
     * @param terminalId the register asking
     * @param islandCode the island that register belongs to, or null
     * @param now the moment of the question
     * @return true when the register must be served this message
     */
    public boolean appliesTo(String terminalId, String islandCode, LocalDateTime now) {
        if (!active) {
            return false;
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        if (endsAt != null && now.isAfter(endsAt)) {
            return false;
        }
        return switch (audience) {
            case ALL -> true;
            case TERMINAL -> audienceValue != null && audienceValue.equals(terminalId);
            case ISLAND -> audienceValue != null && audienceValue.equals(islandCode);
            case EMPLOYEE -> true;
        };
    }

    /**
     * The messages that may be served, newest first, so the most recent notice
     * wins when several apply to one register.
     *
     * @return the active messages, newest first
     */
    public static List<StoreMessage> listServable() {
        return list("active = true order by createdAt desc");
    }

    /**
     * Every message, newest first, for the administration list.
     *
     * @return the whole table, newest first
     */
    public static List<StoreMessage> listAllOrdered() {
        return list("order by createdAt desc");
    }

    /**
     * WHO a message is for.
     */
    public enum Audience {

        /** Every cashier of the store. */
        ALL("Toutes les caisses"),

        /** The registers of one island. */
        ISLAND("Un îlot"),

        /** One register. */
        TERMINAL("Une caisse"),

        /**
         * One employee, wherever they take a post.
         *
         * <p>Served to EVERY register, because the node does not know who is
         * signed in where: the register shows it to the named operator and to
         * nobody else. The filtering is the register's, and it is the only
         * audience of the four that works that way.
         */
        EMPLOYEE("Un opérateur");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds an audience with its administration label.
         *
         * @param label the label shown on the screen
         */
        Audience(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * WHEN the register shows a message.
     */
    public enum Trigger {

        /** As soon as the register polls, which is within twenty seconds. */
        IMMEDIATE("Immédiat"),

        /** At the next opening of a register (BO-03-08-01). */
        SESSION_OPEN("À l'ouverture de caisse"),

        /** At the next closing of a register (BO-03-08-02). */
        SESSION_CLOSE("À la fermeture de caisse"),

        /**
         * When the transaction on the register STARTS (LC-03-07-01): the
         * register asks at the first article rung up, not on a timer — the
         * questionnaire's "début de transaction" is the moment the basket
         * begins, and a twenty-second poll would miss it.
         */
        TRANSACTION_START("Au début de la transaction"),

        /**
         * When the transaction on the register ENDS (LC-03-07-02): the
         * register asks as the paid ticket closes, so the question reads
         * while the customer leaves.
         */
        TRANSACTION_END("En fin de transaction");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds a trigger with its administration label.
         *
         * @param label the label shown on the screen
         */
        Trigger(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * HOW a survey's answer is typed (LC-03-07-06 to -09).
     */
    public enum ResponseType {

        /** The ordinary: one administered option among the buttons. */
        OPTIONS("Choix parmi les options"),

        /** A number keyed on the register's pad (LC-03-07-06). */
        NUMERIC("Saisie d'un nombre"),

        /** A postal code, format-checked against the administered mask (LC-03-07-07). */
        POSTAL_CODE("Saisie d'un code postal"),

        /**
         * A label to validate with nothing to type (LC-03-07-09) — declared
         * as such, not an options list of one.
         */
        INFORMATIVE("Message informatif à valider");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds a response type with its administration label.
         *
         * @param label the label shown on the screen
         */
        ResponseType(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * WHAT the cashier must do about a message.
     */
    public enum Confirmation {

        /** Nothing: the banner shows and goes. */
        NONE("Aucune"),

        /** The cashier acknowledges, and the acknowledgement comes back up. */
        ACKNOWLEDGE("Accusé de lecture"),

        /** The cashier answers the question, and the answer comes back up. */
        ANSWER("Réponse au sondage");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds a confirmation mode with its administration label.
         *
         * @param label the label shown on the screen
         */
        Confirmation(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }
}
