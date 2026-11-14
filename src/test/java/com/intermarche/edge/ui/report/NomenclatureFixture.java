package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Nomenclature;
import com.intermarche.edge.domain.catalog.NomenclatureLevel;
import com.intermarche.edge.domain.catalog.ProductFamily;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stubs the store's nomenclature for the report tests: an active scheme of
 * three levels — activity (2), rayon (4), family (8) — and the labels of the
 * nodes given, read through the Panache statics the lookup calls.
 */
final class NomenclatureFixture {

    /**
     * Not instantiable.
     */
    private NomenclatureFixture() {
    }

    /**
     * Stubs the scheme and the node labels.
     *
     * @param panache the static mock of the Panache base
     * @param labels the labels by node code
     */
    @SuppressWarnings("unchecked")
    static void scheme(MockedStatic<PanacheEntityBase> panache, Map<String, String> labels) {
        Nomenclature scheme = new Nomenclature();
        scheme.id = 1L;
        scheme.code = "ITM";
        panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(List.of(scheme));
        panache.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by rank", 1L))
                .thenReturn(List.of(level(0, "Activité", 2), level(1, "Rayon", 4), level(2, "Famille", 8)));
        panache.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenAnswer(call -> {
            Object[] args = call.getArguments();
            Object code = args[1] instanceof Object[] values ? values[0] : args[1];
            PanacheQuery<Object> query = mock(PanacheQuery.class);
            String label = labels.get(String.valueOf(code));
            if (label != null) {
                ProductFamily node = new ProductFamily();
                node.code = String.valueOf(code);
                node.description = label;
                when(query.firstResult()).thenReturn(node);
            }
            return query;
        });
    }

    /**
     * Builds one level.
     *
     * @param rank the rank
     * @param label the label
     * @param length the code length
     * @return the level
     */
    private static NomenclatureLevel level(int rank, String label, int length) {
        NomenclatureLevel level = new NomenclatureLevel();
        level.rank = rank;
        level.label = label;
        level.codeLength = length;
        return level;
    }
}
