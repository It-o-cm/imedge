package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The VALIDATION of an accounting day ({@code BO-05-02-09}, {@code BO-05-02-10}):
 * the controller confirms the net revenue the registers reported for the day,
 * once every cashier's movement of that day has been validated.
 *
 * <p>It is the hinge of the sequence the requirements impose: the movements are
 * validated one by one, then the day's net revenue, and only then are the cash
 * takings sealed into the pouches brought to the safe. The figures are frozen
 * here as they stood when the controller signed them, so a later push of a
 * register cannot silently change what was validated.
 */
@Entity
@Table(name = "safe_day_validations",
        indexes = {
                @Index(name = "idx_safe_day_validation_day", columnList = "accounting_day", unique = true)
        })
public class SafeDayValidation extends PanacheEntity {

    /** The accounting day validated. */
    @Column(name = "accounting_day", nullable = false)
    public LocalDate accountingDay;

    /** The net revenue confirmed: the closed tickets less the refunds of the day. */
    @Column(name = "net_revenue", nullable = false, precision = 19, scale = 2)
    public BigDecimal netRevenue = BigDecimal.ZERO;

    /** The takings of the day tender by tender, as {@code TENDER=AMOUNT;...}. */
    @Column(name = "tender_totals", length = 2000)
    public String tenderTotals;

    /** The name or badge of the controller who validated the day. */
    @Column(name = "validated_by", nullable = false, length = 60)
    public String validatedBy;

    /** When the day was validated. */
    @Column(name = "validated_at", nullable = false)
    public LocalDateTime validatedAt;

    /**
     * The validation of one accounting day.
     *
     * @param day the accounting day, null answering none
     * @return the validation, or null when the day is not validated
     */
    public static SafeDayValidation forDay(LocalDate day) {
        if (day == null) {
            return null;
        }
        return SafeDayValidation.<SafeDayValidation>find("accountingDay", day).firstResult();
    }
}
