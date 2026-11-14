/*
 * Register alerts on a mobile device (BO-10-08-02, LS-04-04-09): polls
 * /dashboard/mobile/data every 5 s and draws one card per supervisor call, with
 * the PC dashboard's own gestures, which tell the lane. A pending call offers
 * Acquitter, Autoriser, À traiter en caisse and Refuser; a call already settled
 * says what it became (and why, when refused) and keeps only Acquitter — exactly
 * as the dashboard banner does. The wordings come from the data-* attributes of
 * #mobileCalls, translated by the page.
 */
(function () {
    'use strict';

    var zone = document.getElementById('mobileCalls');
    if (!zone) {
        return;
    }

    /**
     * Sends one gesture, shows the reason of a profile refusal (LS-04-03-13), then
     * redraws the calls.
     */
    function act(url) {
        return fetch(url, { credentials: 'same-origin' }).then(function (r) {
            if (r.status === 403 && (r.headers.get('Content-Type') || '').indexOf('text/plain') === 0) {
                r.text().then(function (t) { window.alert(t); });
            }
            refresh();
        });
    }

    /**
     * Builds one touch button bound to a gesture.
     */
    function button(label, css, url) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn ' + css + ' mobile-btn';
        b.textContent = label;
        b.addEventListener('click', function () { act(url); });
        return b;
    }

    /**
     * Words what a settled call became, with the refusal reason when there is one.
     */
    function outcome(call) {
        return ' [' + (call.statusLabel || call.status) + (call.refusalReason ? ' : ' + call.refusalReason : '') + ']';
    }

    /**
     * Draws the calls, or the empty notice when there is none.
     */
    function render(calls) {
        zone.innerHTML = '';
        if (!calls.length) {
            var empty = document.createElement('p');
            empty.className = 'mobile-empty';
            empty.textContent = zone.dataset.empty;
            zone.appendChild(empty);
            return;
        }
        calls.forEach(function (call) {
            var settled = !!call.status && call.status !== 'PENDING';
            var card = document.createElement('div');
            card.className = 'mobile-card';
            var title = document.createElement('div');
            title.className = 'mobile-card-title';
            title.textContent = call.terminal + ' — ' + (call.operator || '') + ' — ' + (call.time || '');
            var reason = document.createElement('div');
            reason.className = 'mobile-card-reason';
            reason.textContent = (call.reasonLabel || call.reason || '') + (call.message ? ' : ' + (call.gesture || call.message) : '')
                + (settled ? outcome(call) : '');
            var actions = document.createElement('div');
            actions.className = 'mobile-actions';
            actions.appendChild(button(zone.dataset.ack, 'btn-primary', '/dashboard/ack/' + call.id));
            if (!settled) {
                actions.appendChild(button(zone.dataset.grant, '', '/dashboard/call/' + call.id + '/authorise'));
                actions.appendChild(button(zone.dataset.toTill, '', '/dashboard/call/' + call.id + '/to-till'));
                actions.appendChild(button(zone.dataset.refuse, 'btn-danger', '/dashboard/call/' + call.id + '/refuse'));
            }
            card.appendChild(title);
            card.appendChild(reason);
            card.appendChild(actions);
            zone.appendChild(card);
        });
    }

    /**
     * Polls the pending calls and redraws them; a transient error keeps the view.
     */
    function refresh() {
        return fetch('/dashboard/mobile/data', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (data) { render(data.calls || []); })
            .catch(function () {});
    }

    refresh();
    setInterval(refresh, 5000);
})();
