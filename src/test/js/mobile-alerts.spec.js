import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of mobile-alerts.js — the register alerts on a mobile device
 * (BO-10-08-02, LS-04-04-09). A pending call offers Acquitter, Autoriser, « À traiter en
 * caisse » and Refuser, each calling the PC dashboard's own gesture; a settled call says
 * what it became and keeps only Acquitter. The bench installs #mobileCalls before import,
 * mocks fetch, alert and the clock, and drives render(), act(), refresh() and the boot.
 */

/**
 * Project-relative path of the source under test.
 */
const SOURCE = 'src/main/resources/META-INF/resources/admin-assets/mobile-alerts.js';

/**
 * The module object exposing the page functions once loaded.
 */
let page;

/**
 * Drains the microtask queue so a mocked fetch chain settles.
 */
async function flush() {
  for (let i = 0; i < 10; i += 1) {
    await Promise.resolve();
  }
}

/**
 * Builds a fetch answer.
 *
 * @param {number} status the HTTP status
 * @param {?string} type the Content-Type header, or null
 * @param {*} body the JSON body, also the text body
 * @returns {object} the fake response
 */
function answer(status, type, body) {
  return {
    status,
    headers: { get: () => type },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(body),
  };
}

/**
 * The zone the calls are drawn into.
 *
 * @returns {HTMLElement} #mobileCalls
 */
function zone() {
  return document.getElementById('mobileCalls');
}

/**
 * The labels of the buttons of the n-th card.
 *
 * @param {number} n the card index
 * @returns {string[]} the button labels, in order
 */
function labels(n) {
  return [...zone().querySelectorAll('.mobile-card')[n].querySelectorAll('button')].map((b) => b.textContent);
}

/**
 * Installs the page, fake timers and mocks, then imports the module (its boot polls once).
 */
beforeAll(async () => {
  vi.useFakeTimers();
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(answer(200, 'application/json', { calls: [] }))));
  vi.stubGlobal('alert', vi.fn());
  document.body.innerHTML = '<div id="mobileCalls" data-empty="Aucune alerte" data-ack="Acquitter"'
    + ' data-grant="Autoriser" data-to-till="À traiter en caisse" data-refuse="Refuser"></div>';
  page = await loadScript(SOURCE);
  await flush();
});

/**
 * Restores the clock and the globals.
 */
afterAll(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/**
 * Resets the mocks before each case.
 */
beforeEach(() => {
  fetch.mockReset();
  window.alert.mockReset();
});

describe('boot', () => {
  /**
   * The boot polled the calls once and drew the empty notice.
   */
  it('draws the empty notice at load', () => {
    expect(zone().textContent).toBe('Aucune alerte');
  });

  /**
   * The poll runs again every five seconds.
   */
  it('polls every five seconds', async () => {
    fetch.mockResolvedValue(answer(200, 'application/json', { calls: [] }));
    vi.advanceTimersByTime(5000);
    expect(fetch).toHaveBeenCalledWith('/dashboard/mobile/data', { credentials: 'same-origin' });
  });
});

describe('render', () => {
  /**
   * A pending call offers the four gestures, « À traiter en caisse » among them, each
   * bound to the dashboard's own address.
   */
  it('offers the four gestures on a pending call', async () => {
    page.render([{ id: 7, terminal: 'C04', operator: 'Jean', time: '10:00', reasonLabel: 'Prix', reason: 'PRICE',
      message: 'remise', gesture: 'Remise ligne', status: 'PENDING' }]);
    expect(labels(0)).toEqual(['Acquitter', 'Autoriser', 'À traiter en caisse', 'Refuser']);
    expect(zone().querySelector('.mobile-card-title').textContent).toBe('C04 — Jean — 10:00');
    expect(zone().querySelector('.mobile-card-reason').textContent).toBe('Prix : Remise ligne');
    fetch.mockResolvedValue(answer(204, null, ''));
    const urls = [];
    zone().querySelectorAll('button').forEach((b) => { b.click(); urls.push(fetch.mock.calls.at(-1)[0]); });
    await flush();
    expect(urls).toEqual(['/dashboard/ack/7', '/dashboard/call/7/authorise', '/dashboard/call/7/to-till',
      '/dashboard/call/7/refuse']);
  });

  /**
   * A call without a status is a pending one; the missing operator, time, label and
   * gesture fall back to what is known.
   */
  it('treats a call without status as pending', () => {
    page.render([{ id: 1, terminal: 'C01', reason: 'AIDE', message: 'vite' },
      { id: 2, terminal: 'C02' }]);
    expect(labels(0)).toHaveLength(4);
    expect(zone().querySelectorAll('.mobile-card-title')[0].textContent).toBe('C01 —  — ');
    expect(zone().querySelectorAll('.mobile-card-reason')[0].textContent).toBe('AIDE : vite');
    expect(zone().querySelectorAll('.mobile-card-reason')[1].textContent).toBe('');
  });

  /**
   * A settled call says what it became — and why, when refused — and keeps only
   * Acquitter, as the dashboard banner does.
   */
  it('keeps only Acquitter on a settled call', () => {
    page.render([{ id: 3, terminal: 'C03', reasonLabel: 'Prix', status: 'REFUSED', statusLabel: 'refusée', refusalReason: 'Non' },
      { id: 4, terminal: 'C04', reasonLabel: 'Prix', status: 'TO_HANDLE_AT_TILL', statusLabel: 'à traiter en caisse', refusalReason: '' },
      { id: 5, terminal: 'C05', reasonLabel: 'Prix', status: 'HANDLED_AT_TILL' }]);
    expect(labels(0)).toEqual(['Acquitter']);
    expect(labels(1)).toEqual(['Acquitter']);
    const reasons = zone().querySelectorAll('.mobile-card-reason');
    expect(reasons[0].textContent).toBe('Prix [refusée : Non]');
    expect(reasons[1].textContent).toBe('Prix [à traiter en caisse]');
    expect(reasons[2].textContent).toBe('Prix [HANDLED_AT_TILL]');
  });
});

describe('act', () => {
  /**
   * A refusal for a missing profile is shown, then the calls are redrawn.
   */
  it('shows a profile refusal', async () => {
    fetch.mockResolvedValueOnce(answer(403, 'text/plain;charset=UTF-8', 'Profil requis'))
      .mockResolvedValue(answer(200, 'application/json', { calls: [] }));
    await page.act('/dashboard/call/1/authorise');
    await flush();
    expect(window.alert).toHaveBeenCalledWith('Profil requis');
    expect(fetch).toHaveBeenLastCalledWith('/dashboard/mobile/data', { credentials: 'same-origin' });
  });

  /**
   * A 403 that is not a worded refusal, with or without a type, and a success alert
   * nothing.
   */
  it('alerts nothing otherwise', async () => {
    fetch.mockResolvedValue(answer(403, 'text/html', ''));
    await page.act('/x');
    fetch.mockResolvedValue(answer(403, null, ''));
    await page.act('/y');
    fetch.mockResolvedValue(answer(204, 'text/plain', ''));
    await page.act('/z');
    await flush();
    expect(window.alert).not.toHaveBeenCalled();
  });
});

describe('refresh', () => {
  /**
   * An answer without calls draws the empty notice.
   */
  it('draws the empty notice without calls', async () => {
    fetch.mockResolvedValue(answer(200, 'application/json', {}));
    await page.refresh();
    expect(zone().textContent).toBe('Aucune alerte');
  });

  /**
   * A transient error keeps the current view.
   */
  it('keeps the view on a transient error', async () => {
    page.render([{ id: 9, terminal: 'C09', status: 'PENDING' }]);
    fetch.mockRejectedValue(new Error('offline'));
    await page.refresh();
    expect(zone().querySelectorAll('.mobile-card')).toHaveLength(1);
  });
});

describe('boot guard', () => {
  /**
   * A page without the zone (alerts deactivated) does nothing at all: the same source is
   * evaluated afresh after a module reset, so Istanbul counts the early return on the
   * real file.
   */
  it('stays idle without the zone', async () => {
    const original = zone();
    original.remove();
    vi.resetModules();
    const second = await loadScript(SOURCE);
    expect(second).toBeTruthy();
    expect(fetch).not.toHaveBeenCalled();
    document.body.appendChild(original);
  });
});
