/*
 * Confirmation guard for back-office POST forms. A form carrying a data-confirm
 * attribute asks the operator to confirm before it submits; declining cancels the
 * submission. The listener is delegated on the document (capture phase) so it also
 * guards buttons rendered inside a table body that the auto-refresh replaces after
 * the page loaded — no per-form wiring, no re-binding on refresh.
 */
(function () {
    "use strict";

    /**
     * Cancels the submission when the form's data-confirm prompt is declined;
     * a form without the attribute submits normally.
     */
    function handleSubmit(event) {
        var message = event.target.getAttribute("data-confirm");
        if (message && !window.confirm(message)) {
            event.preventDefault();
        }
    }

    document.addEventListener("submit", handleSubmit, true);
})();
