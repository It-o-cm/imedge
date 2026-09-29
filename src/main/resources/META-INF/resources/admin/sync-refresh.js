/*
 * Auto-refreshes the sync supervision tables every two seconds, in place, without
 * reloading the page (GB-01-01-20/30). Only the table BODIES tagged with a
 * data-refresh-url are replaced — the engine feeds, the referential pull and the
 * store outbox — so the page chrome, the action buttons outside the bodies, the
 * schedule form and the scroll position are all left untouched. The refresh is
 * suspended while the browser tab is hidden. The current query string is
 * forwarded so any active filter is honoured. Adapted from imvaluation's
 * valuation-refresh pattern, generalised to several table bodies at once.
 */
(function () {
    "use strict";

    var INTERVAL_MS = 2000;
    var targets = document.querySelectorAll("tbody[data-refresh-url]");
    if (targets.length === 0) {
        return;
    }
    var inFlight = {};

    /**
     * Fetches the latest rows for one table body and swaps them in; keeps the
     * current rows on a not-ok response or a transient error, and never lets two
     * requests for the same body overlap.
     */
    function refreshOne(tbody) {
        var url = tbody.getAttribute("data-refresh-url");
        if (inFlight[url]) {
            return;
        }
        inFlight[url] = true;
        fetch(url + window.location.search, { headers: { "Accept": "text/html" }, credentials: "same-origin" })
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (html != null) {
                    tbody.innerHTML = html;
                }
            })
            .catch(function () { /* transient error: keep the current rows, try again next tick */ })
            .then(function () { inFlight[url] = false; });
    }

    /**
     * Refreshes every tagged table body once, unless the tab is hidden.
     */
    function refresh() {
        if (document.hidden) {
            return;
        }
        targets.forEach(function (tbody) {
            refreshOne(tbody);
        });
    }

    setInterval(refresh, INTERVAL_MS);
})();
