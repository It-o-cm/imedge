package com.intermarche.pos.domain.store;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * A postal code and the place it designates ({@code BO-02-04-15},
 * {@code BO-02-04-16}, {@code BO-02-04-21}).
 *
 * <p>Kept for ONE reason: filling an address at the register without typing it.
 * A cashier creating a customer keys four digits and the town comes with them,
 * which is both faster and — far more to the point — spelt the same way every
 * time, so the shop's customer file does not end up with Saint-Étienne, St
 * Etienne and ST ETIENNE side by side.
 *
 * <p>The pair is the key, not the code alone. A French code covers several
 * communes and a Portuguese one covers a street, so a table keyed on the code
 * would keep exactly one of them and quietly lose the rest. Two rows carrying
 * the same code and two different places are two legitimate rows.
 *
 * <p>The FORMAT belongs to the country and is never checked here
 * ({@code BO-02-04-21}): France writes five digits, Portugal writes
 * {@code 1234-567} with a place of at most twenty-five upper-case characters,
 * and a table that refused what it had not been taught would refuse the next
 * country the group opens in. What is imported is stored; the country is
 * carried beside it so a screen can filter on it.
 */
@Entity
@Table(name = "postal_codes",
        indexes = {
                @Index(name = "idx_postal_code_code", columnList = "code"),
                @Index(name = "idx_postal_code_place", columnList = "place")
        }
)
public class PostalCode extends BaseEntity {

    /** The postal code as the country writes it. */
    @Column(name = "code", nullable = false, length = 16)
    @NotBlank
    public String code;

    /** The place it designates: commune, locality, or street in Portugal. */
    @Column(name = "place", nullable = false, length = 60)
    @NotBlank
    public String place;

    /** The ISO country the code belongs to, or null when the feed said none. */
    @Column(name = "country", length = 3)
    public String country;

    /**
     * Finds one row by the pair that identifies it.
     *
     * @param code the postal code
     * @param place the place it designates
     * @return the row, or null when that pair is unknown
     */
    public static PostalCode findPair(String code, String place) {
        return find("code = ?1 and place = ?2", code, place).firstResult();
    }

    /**
     * Lists the places a code designates, in alphabetical order.
     *
     * @param code the postal code asked for
     * @return its places, possibly empty
     */
    public static List<PostalCode> findByCode(String code) {
        return list("code = ?1 order by place", code);
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, place, country);
    }
}
