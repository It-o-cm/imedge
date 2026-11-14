package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.setting.StoreMessage;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * SONDAGE CODES POSTAUX (BO-06-03-03): the postal codes the customers gave at
 * the till during a postal-code survey — a promotion period, typically — and
 * for each code the number and share of the customers, their sales and their
 * mean basket, survey by survey; exported to Excel like every report.
 *
 * <p>The collection is the survey's: a question whose answer type is the
 * postal code, keyed at the till under the survey's mask — the dash of a
 * Portuguese code inserted by the register — and, when the survey is based on
 * an imported list of codes, refused by the register when the code is not in
 * it. This state is the survey state ({@link SurveyReport}) restricted to the
 * postal-code surveys, each answer being a code.
 */
@ApplicationScoped
public class PostalCodeReport extends SurveyReport {

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "postal-codes";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Sondage codes postaux";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-03-03";
    }

    /**
     * {@inheritDoc}
     *
     * @return the survey's columns, the answer named the postal code
     */
    @Override
    public List<ReportColumn> columns() {
        List<ReportColumn> columns = new ArrayList<>(super.columns());
        columns.set(2, ReportColumn.text("answer", "Code postal"));
        return columns;
    }

    /**
     * {@inheritDoc}
     *
     * @return the postal-code surveys only
     */
    @Override
    List<StoreMessage> surveys() {
        List<StoreMessage> postal = new ArrayList<>();
        for (StoreMessage survey : super.surveys()) {
            if (survey.responseType == StoreMessage.ResponseType.POSTAL_CODE) {
                postal.add(survey);
            }
        }
        return postal;
    }
}
