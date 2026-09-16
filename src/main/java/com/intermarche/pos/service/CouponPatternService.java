package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the administered description of a barcode range into the regular
 * expressions the till already runs on (BO-03-06).
 *
 * <p>The scan chain is unchanged: {@link CouponType#matches(String)} and
 * {@link CouponType#extractAmount(String)} still decide, and they still read a
 * pattern. What moves is WHO writes that pattern. The back office states a
 * literal prefix, a total length, a character kind and a list of
 * {@link CouponField} positions; this service assembles them into
 * {@link CouponType#matchPattern} and {@link CouponType#amountPattern} at save
 * time. Nobody types a regular expression, and no reading site changes.
 *
 * <p>A range that carries no code length is left exactly as it was loaded: the
 * hand-written patterns of the reference feed keep working beside the generated
 * ones.
 */
@ApplicationScoped
public class CouponPatternService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CouponPatternService.class);

    /** The regular expression characters a literal prefix must be protected from. */
    private static final String META = "\\^$.|?*+()[]{}";

    /**
     * Rewrites the patterns of an administered range from its fields.
     *
     * <p>A range that is not administered is left untouched, patterns included:
     * that is how a hand-written row survives a save on another row.
     *
     * @param type the range to regenerate, or null
     */
    public void regenerate(CouponType type) {
        LOGGER.info("Entering method regenerate with type: " + (type == null ? null : type.code));
        if (type == null || !type.isAdministered()) {
            LOGGER.info("Exiting method regenerate");
            return;
        }
        type.matchPattern = buildMatchPattern(type);
        type.amountPattern = buildAmountPattern(type);
        LOGGER.info("Exiting method regenerate");
    }

    /**
     * Regenerates the recognition pattern of an ARTICLE range from its
     * administered description (BO-03-06-02/03/04/10).
     *
     * @param range the range to regenerate, or null
     */
    public void regenerateRange(com.intermarche.pos.domain.barcode.ArticleBarcodeRange range) {
        LOGGER.info("Entering method regenerateRange with range: " + (range == null ? null : range.code));
        if (range == null) {
            LOGGER.info("Exiting method regenerateRange");
            return;
        }
        range.matchPattern = buildRangePattern(range);
        LOGGER.info("Exiting method regenerateRange");
    }

    /**
     * Builds the recognition pattern of an ARTICLE range: the literal prefix,
     * then one token per position, the article and value segments taking their
     * own kind from the range's base kind.
     *
     * @param range the range, or null
     * @return the generated pattern, or null when the range carries no length
     */
    public String buildRangePattern(com.intermarche.pos.domain.barcode.ArticleBarcodeRange range) {
        LOGGER.info("Entering method buildRangePattern with range: " + (range == null ? null : range.code));
        if (range == null || range.codeLength <= 0) {
            LOGGER.info("Exiting method buildRangePattern");
            return null;
        }
        String prefix = range.prefix == null ? "" : range.prefix;
        CouponField.Kind base = range.codeKind == null ? CouponField.Kind.NUMERIC : range.codeKind;
        CouponField.Kind[] slots = new CouponField.Kind[range.codeLength];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = base;
        }
        StringBuilder pattern = new StringBuilder("^").append(escape(prefix));
        appendRuns(pattern, slots, Math.min(prefix.length(), slots.length), slots.length, -1, -1);
        pattern.append('$');
        LOGGER.info("Exiting method buildRangePattern");
        return pattern.toString();
    }

    /**
     * Lists what forbids an ARTICLE range from being generated: the length
     * bounds the questionnaire states (1 to 38 characters), the two segments
     * lying inside the code and outside the prefix, and their not overlapping
     * (BO-03-06-02/03/04/10).
     *
     * @param range the range to check, or null
     * @return the problems, in French, empty when the range is sound
     */
    public List<String> validateRange(com.intermarche.pos.domain.barcode.ArticleBarcodeRange range) {
        LOGGER.info("Entering method validateRange with range: " + (range == null ? null : range.code));
        List<String> problems = new ArrayList<>();
        if (range == null) {
            LOGGER.info("Exiting method validateRange");
            return problems;
        }
        int min = com.intermarche.pos.domain.barcode.ArticleBarcodeRange.MIN_CODE_LENGTH;
        int max = com.intermarche.pos.domain.barcode.ArticleBarcodeRange.MAX_CODE_LENGTH;
        if (range.codeLength < min || range.codeLength > max) {
            problems.add("La longueur du code doit être comprise entre " + min + " et " + max
                    + " caractères.");
        }
        String prefix = range.prefix == null ? "" : range.prefix;
        if (prefix.isBlank()) {
            problems.add("Le préfixe de la plage est obligatoire.");
        }
        if (prefix.length() > range.codeLength) {
            problems.add("Le préfixe est plus long que le code lui-même.");
        }
        checkSegment(problems, "article", range.articlePosition, range.articleLength,
                prefix.length(), range.codeLength);
        checkSegment(problems, "valeur", range.valuePosition, range.valueLength,
                prefix.length(), range.codeLength);
        if (range.segmentsOverlap()) {
            problems.add("Les zones article et valeur se chevauchent.");
        }
        if (range.valueDecimals < 0 || range.valueDecimals > range.valueLength) {
            problems.add("Le nombre de décimales de la valeur est hors de la zone lue.");
        }
        LOGGER.info("Exiting method validateRange");
        return problems;
    }

    /**
     * Adds the problems of one administered segment of an article range.
     *
     * @param problems the problems being collected
     * @param name the segment's name, as the message spells it
     * @param position the 0-based offset of the segment
     * @param length the number of characters of the segment
     * @param prefixLength the number of characters the prefix occupies
     * @param codeLength the total length of a code of the range
     */
    private void checkSegment(List<String> problems, String name, int position, int length,
                              int prefixLength, int codeLength) {
        if (length <= 0) {
            problems.add("La zone " + name + " doit occuper au moins un caractère.");
            return;
        }
        if (position < prefixLength) {
            problems.add("La zone " + name + " commence dans le préfixe.");
        }
        if (position + length > codeLength) {
            problems.add("La zone " + name + " déborde de la longueur du code.");
        }
    }

    /**
     * Builds the recognition pattern of an administered range.
     *
     * @param type the range, or null
     * @return the generated pattern, or the range's own pattern when it is not administered
     */
    public String buildMatchPattern(CouponType type) {
        LOGGER.info("Entering method buildMatchPattern with type: " + (type == null ? null : type.code));
        if (type == null) {
            LOGGER.info("Exiting method buildMatchPattern");
            return null;
        }
        if (!type.isAdministered()) {
            LOGGER.info("Exiting method buildMatchPattern");
            return type.matchPattern;
        }
        String prefix = type.effectivePrefix();
        StringBuilder pattern = new StringBuilder("^").append(escape(prefix));
        appendRuns(pattern, slots(type), prefix.length(), type.codeLength, -1, -1);
        pattern.append('$');
        LOGGER.info("Exiting method buildMatchPattern");
        return pattern.toString();
    }

    /**
     * Builds the amount extraction pattern of an administered range.
     *
     * <p>The first capturing group holds the price field, which is what the
     * legacy reader expects; an administered range reads the amount
     * positionally anyway, so the pattern is there for the rows that do not
     * carry a price field and for anything reading the column directly.
     *
     * @param type the range, or null
     * @return the generated pattern, null when the range encodes no readable amount,
     *         or the range's own pattern when it is not administered
     */
    public String buildAmountPattern(CouponType type) {
        LOGGER.info("Entering method buildAmountPattern with type: " + (type == null ? null : type.code));
        if (type == null) {
            LOGGER.info("Exiting method buildAmountPattern");
            return null;
        }
        if (!type.isAdministered()) {
            LOGGER.info("Exiting method buildAmountPattern");
            return type.amountPattern;
        }
        CouponField price = type.fieldOf(CouponField.Role.PRICE);
        String prefix = type.effectivePrefix();
        if (type.amountSource != CouponType.AmountSource.ENCODED || price == null
                || price.offsetPosition < prefix.length() || price.endPosition() > type.codeLength) {
            LOGGER.info("Exiting method buildAmountPattern");
            return null;
        }
        StringBuilder pattern = new StringBuilder("^").append(escape(prefix));
        appendRuns(pattern, slots(type), prefix.length(), type.codeLength,
                price.offsetPosition, price.endPosition());
        pattern.append('$');
        LOGGER.info("Exiting method buildAmountPattern");
        return pattern.toString();
    }

    /**
     * Lists what forbids an administered range from being generated.
     *
     * @param type the range to check, or null
     * @return the problems, in French, empty when the range is sound
     */
    public List<String> validate(CouponType type) {
        LOGGER.info("Entering method validate with type: " + (type == null ? null : type.code));
        if (type == null || !type.isAdministered()) {
            LOGGER.info("Exiting method validate");
            return new ArrayList<>();
        }
        List<String> problems = validate(type.effectivePrefix(), type.codeLength,
                type.amountSource, type.fields);
        LOGGER.info("Exiting method validate");
        return problems;
    }

    /**
     * Lists what forbids a POSTED description from being generated, before any
     * stored row is touched.
     *
     * @param rawPrefix the literal head, possibly null
     * @param codeLength the total code length, or null when the range is left unadministered
     * @param source where the amount comes from, possibly null
     * @param fields the administered positions, possibly null
     * @return the problems, in French, empty when the description is sound
     */
    public List<String> validate(String rawPrefix, Integer codeLength,
                                 CouponType.AmountSource source, List<CouponField> fields) {
        LOGGER.info("Entering method validate with rawPrefix: " + rawPrefix + ", codeLength: "
                + codeLength + ", source: " + source + ", fields: " + fields);
        List<String> problems = new ArrayList<>();
        if (codeLength == null || codeLength <= 0) {
            LOGGER.info("Exiting method validate");
            return problems;
        }
        String prefix = rawPrefix == null ? "" : rawPrefix;
        int total = codeLength;
        if (prefix.length() > total) {
            problems.add("Le préfixe « " + prefix + " » est plus long que le code (" + total + " caractères).");
        }
        Set<CouponField.Role> seen = EnumSet.noneOf(CouponField.Role.class);
        List<CouponField> rows = fields == null ? List.of() : fields;
        boolean hasPrice = false;
        for (CouponField field : rows) {
            if (field == null) {
                continue;
            }
            String name = field.role == null ? "?" : field.role.name();
            hasPrice = hasPrice || field.role == CouponField.Role.PRICE;
            if (field.role == null) {
                problems.add("Un champ n'a pas de rôle.");
            } else if (!seen.add(field.role)) {
                problems.add("Le rôle " + name + " est administré deux fois.");
            }
            if (field.fieldLength <= 0) {
                problems.add("Le champ " + name + " a une longueur nulle ou négative.");
            }
            if (field.offsetPosition < 0) {
                problems.add("Le champ " + name + " commence avant le début du code.");
            } else if (field.offsetPosition < prefix.length()) {
                problems.add("Le champ " + name + " empiète sur le préfixe.");
            }
            if (field.fieldLength > 0 && field.endPosition() > total) {
                problems.add("Le champ " + name + " dépasse la longueur du code.");
            }
            if (field.dateFormat != null && field.fieldLength != field.dateFormat.getWidth()) {
                problems.add("Le champ " + name + " est en " + field.dateFormat.name()
                        + ", qui occupe " + field.dateFormat.getWidth() + " caractères.");
            }
        }
        addOverlaps(problems, rows);
        if (source == CouponType.AmountSource.ENCODED && !hasPrice) {
            problems.add("Le montant est déclaré encodé mais aucun champ PRICE n'est administré.");
        }
        LOGGER.info("Exiting method validate");
        return problems;
    }

    /**
     * Reports every pair of fields claiming a common position.
     *
     * @param problems the list to add to
     * @param fields the administered fields
     */
    private void addOverlaps(List<String> problems, List<CouponField> fields) {
        for (int i = 0; i < fields.size(); i++) {
            CouponField left = fields.get(i);
            if (left == null || left.role == null) {
                continue;
            }
            for (int j = i + 1; j < fields.size(); j++) {
                CouponField right = fields.get(j);
                if (right == null || right.role == null || right.role == left.role) {
                    continue;
                }
                if (left.overlaps(right)) {
                    problems.add("Les champs " + left.role.name() + " et " + right.role.name()
                            + " se chevauchent.");
                }
            }
        }
    }

    /**
     * Maps each position of the code to the kind of character it accepts.
     *
     * @param type the administered range
     * @return one kind per position
     */
    private CouponField.Kind[] slots(CouponType type) {
        int total = type.codeLength;
        CouponField.Kind base = type.codeKind == null ? CouponField.Kind.NUMERIC : type.codeKind;
        CouponField.Kind[] slots = new CouponField.Kind[total];
        for (int i = 0; i < total; i++) {
            slots[i] = base;
        }
        List<CouponField> fields = type.fields == null ? List.of() : type.fields;
        for (CouponField field : fields) {
            if (field == null || field.kind == null || field.fieldLength <= 0) {
                continue;
            }
            int from = Math.max(field.offsetPosition, 0);
            int to = Math.min(field.endPosition(), total);
            for (int i = from; i < to; i++) {
                slots[i] = field.kind;
            }
        }
        return slots;
    }

    /**
     * Writes the positions of the code as run-length encoded tokens, opening a
     * capturing group over the requested span.
     *
     * @param pattern the pattern being built
     * @param slots the kind of each position
     * @param from the first position to write
     * @param to the position just past the last one to write
     * @param groupFrom the first position of the capturing group, or -1 for none
     * @param groupTo the position just past the group, or -1 for none
     */
    private void appendRuns(StringBuilder pattern, CouponField.Kind[] slots,
                            int from, int to, int groupFrom, int groupTo) {
        if (groupFrom < from || groupFrom >= to) {
            appendRun(pattern, slots, from, to);
            return;
        }
        int groupEnd = Math.min(groupTo, to);
        appendRun(pattern, slots, from, groupFrom);
        pattern.append('(');
        appendRun(pattern, slots, groupFrom, groupEnd);
        pattern.append(')');
        appendRun(pattern, slots, groupEnd, to);
    }

    /**
     * Writes one span of the code as a single token, quantified when longer
     * than one character.
     *
     * @param pattern the pattern being built
     * @param slots the kind of each position
     * @param from the first position of the span
     * @param to the position just past the span
     */
    private void appendRun(StringBuilder pattern, CouponField.Kind[] slots, int from, int to) {
        int count = to - from;
        if (count <= 0) {
            return;
        }
        int cursor = from;
        while (cursor < to) {
            int stop = cursor + 1;
            while (stop < to && slots[stop] == slots[cursor]) {
                stop++;
            }
            pattern.append(slots[cursor].getToken());
            if (stop - cursor > 1) {
                pattern.append('{').append(stop - cursor).append('}');
            }
            cursor = stop;
        }
    }

    /**
     * Protects a literal prefix from being read as a regular expression.
     *
     * @param literal the prefix, never null
     * @return the escaped prefix
     */
    private String escape(String literal) {
        StringBuilder escaped = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (META.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
