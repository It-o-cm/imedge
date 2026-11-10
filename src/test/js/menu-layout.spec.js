import { describe, it, expect, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * The key layout editor of the cash menus (menu-layout.js): the pure edits of a layout, then
 * the editor booted on a page — selecting, labelling, colouring, moving and removing tabs and
 * keys, adding them from the functions, and every drop the drawing accepts.
 */

/** Project-relative path of the source under test. */
const SOURCE = 'src/main/resources/META-INF/resources/admin-assets/menu-layout.js';

/** The texts of the editor, as the page gives them. */
const TEXTS = {
  addTab: 'Ajouter un onglet', newTab: 'Nouvel onglet', newTabLabel: 'NOUVEL ONGLET', noTab: 'Aucun onglet',
  directTab: 'Règlement direct', emptyTab: 'Vide', hiddenKey: 'Masquée', unplaced: 'non placée',
  addKey: 'Ajouter', keyProps: 'Touche', tabProps: 'Onglet', labelRequired: 'Libellé',
  standardColor: 'Standard', moveBefore: 'Avancer', moveAfter: 'Reculer', removeKey: 'Retirer la touche',
  removeTab: "Retirer l'onglet",
};

/**
 * Builds the data the page hands the editor.
 *
 * @param {object} layout the layout of the model
 * @returns {object} the data
 */
function data(layout) {
  return {
    layout,
    standard: { tabs: [{ id: 'articles', keys: [{ fn: 'SEARCH' }] }, { id: 'reglement' }] },
    functions: { SEARCH: 'Recherche article', LOCK: 'Verrouiller', PARK: 'Mise en attente' },
    tabNames: { articles: 'Articles', caisse: 'Caisse', reglement: 'Règlement' },
    colors: { blue: '#1f5fd6', yellow: '#e8c21c' },
    colorNames: { blue: 'Bleu' },
    languages: ['fr', 'nl', 'pt'],
    columns: 2,
    maxTabs: 4,
    maxKeys: 3,
    texts: TEXTS,
  };
}

/**
 * Draws the page around the editor and boots the script afresh on it.
 *
 * @param {object} layout the layout of the model
 * @param {string} bust a token loading the script afresh
 * @returns {Promise<object>} the script's functions
 */
async function boot(layout, bust) {
  document.body.innerHTML = '<form><div id="menuLayoutEditor">'
    + '<button type="button" class="ml-lang-choice ml-on" data-lang="fr">FR</button>'
    + '<button type="button" class="ml-lang-choice" data-lang="nl">NL</button>'
    + '<span class="ml-add-slot"></span><button type="button" class="ml-reset">Standard</button>'
    + '<div class="ml-grid"></div><div class="ml-bar"></div>'
    + '<input class="ml-filter"><ul class="ml-pool-list"></ul><div class="ml-props"></div>'
    + '<input type="hidden" name="layout"></div>'
    + '<input type="checkbox" name="hidden" value="LOCK" checked><input type="checkbox" name="hidden" value="PARK">'
    + '<script type="application/json" id="menuLayoutData"></script></form>';
  document.getElementById('menuLayoutData').textContent = JSON.stringify(data(layout));
  vi.resetModules();
  return loadScript(SOURCE, { bust });
}

/** The layout the form will post. */
function posted() {
  return JSON.parse(document.querySelector('input[name=layout]').value);
}

/** The texts of the elements matching a selector. */
function texts(selector) {
  return [...document.querySelectorAll(selector)].map((node) => node.textContent);
}

/**
 * Drops a payload on an element, the way a drag ends.
 *
 * @param {Element} target the element dropped on
 * @param {string} value the payload, kind:value
 */
function drop(target, value) {
  const over = new Event('dragover', { bubbles: true, cancelable: true });
  target.dispatchEvent(over);
  const event = new Event('drop', { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'dataTransfer', { value: { getData: () => value } });
  target.dispatchEvent(event);
}

/** A layout of two tabs and the settlement tab. */
function sample() {
  return {
    tabs: [
      { id: 'articles', keys: [{ fn: 'SEARCH' }, { fn: 'LOCK', color: 'yellow', labels: { fr: 'FERMER' } }] },
      { id: 'x1', labels: { fr: 'PROMO' }, color: 'blue', keys: [] },
      { id: 'reglement' },
    ],
  };
}

describe('the edits of a layout', () => {
  it('moves an element forward, backward, and ignores a position out of the list', async () => {
    const api = await boot(sample(), 'p1');
    expect(api.moveItem(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'a', 'c']);
    expect(api.moveItem(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b']);
    expect(api.moveItem(['a', 'b'], 0, 9)).toEqual(['b', 'a']);
    expect(api.moveItem(['a', 'b'], 1, -3)).toEqual(['b', 'a']);
    expect(api.moveItem(['a'], 4, 0)).toEqual(['a']);
    expect(api.moveItem(['a'], -1, 0)).toEqual(['a']);
  });

  it('completes the lists and maps the server leaves out', async () => {
    const api = await boot(sample(), 'p2');
    expect(api.normalize({})).toEqual({ tabs: [] });
    expect(api.normalize({ tabs: [{ id: 'a', keys: [{ fn: 'X' }] }] }))
      .toEqual({ tabs: [{ id: 'a', labels: {}, keys: [{ fn: 'X', labels: {} }] }] });
  });

  it('places a function once, never in the settlement tab nor beyond the limit', async () => {
    const api = await boot(sample(), 'p3');
    const layout = api.normalize(sample());
    expect(api.addKey(layout, 'x1', 'PARK', null, 3)).toBe(true);
    expect(api.addKey(layout, 'x1', 'LOCK', 0, 3)).toBe(true);
    expect(layout.tabs[1].keys.map((k) => k.fn)).toEqual(['LOCK', 'PARK']);
    expect(api.addKey(layout, 'x1', 'LOCK', 0, 3)).toBe(false);
    expect(api.addKey(layout, 'x1', 'SEARCH', 99, 3)).toBe(true);
    expect(api.addKey(layout, 'x1', 'OTHER', undefined, 3)).toBe(false);
    expect(api.addKey(layout, 'reglement', 'SEARCH', null, 3)).toBe(false);
    expect(api.addKey(layout, 'nowhere', 'SEARCH', null, 3)).toBe(false);
  });

  it('moves a key to another tab only where it can go', async () => {
    const api = await boot(sample(), 'p4');
    const layout = api.normalize(sample());
    expect(api.moveKeyToTab(layout, 'articles', 1, 'x1', 3)).toBe(true);
    expect(layout.tabs[1].keys[0].fn).toBe('LOCK');
    expect(api.moveKeyToTab(layout, 'articles', 0, 'articles', 3)).toBe(false);
    expect(api.moveKeyToTab(layout, 'articles', 0, 'reglement', 3)).toBe(false);
    expect(api.moveKeyToTab(layout, 'articles', 5, 'x1', 3)).toBe(false);
    expect(api.moveKeyToTab(layout, 'nowhere', 0, 'x1', 3)).toBe(false);
    expect(api.moveKeyToTab(layout, 'articles', 0, 'nowhere', 3)).toBe(false);
    layout.tabs[1].keys.push({ fn: 'SEARCH' });
    expect(api.moveKeyToTab(layout, 'articles', 0, 'x1', 3)).toBe(false);
    layout.tabs[1].keys.pop();
    expect(api.moveKeyToTab(layout, 'articles', 0, 'x1', 1)).toBe(false);
  });

  it('adds a standard or a new tab, within the limit, and takes one out', async () => {
    const api = await boot(sample(), 'p5');
    const layout = api.normalize(sample());
    expect(api.nextCustomId(layout)).toBe('x2');
    expect(api.addTab(layout, 'caisse', 'NEW', 5)).toBe('caisse');
    expect(api.addTab(layout, 'caisse', 'NEW', 5)).toBe(null);
    expect(api.addTab(layout, null, 'NEW', 5)).toBe('x2');
    expect(layout.tabs[4].labels).toEqual({ fr: 'NEW' });
    expect(api.addTab(layout, null, 'NEW', 5)).toBe(null);
    expect(api.removeTab(layout, 'x2')).toBe(true);
    expect(api.removeTab(layout, 'x2')).toBe(false);
  });

  it('sets and clears a label and a colour', async () => {
    const api = await boot(sample(), 'p6');
    const target = {};
    api.setLabel(target, 'nl', ' Zoek ');
    expect(target.labels).toEqual({ nl: 'Zoek' });
    api.setLabel(target, 'nl', '');
    api.setLabel(target, 'pt', null);
    expect(target.labels).toEqual({});
    api.setColor(target, 'blue');
    expect(target.color).toBe('blue');
    api.setColor(target, null);
    expect(target.color).toBeUndefined();
  });

  it('shows a label, else the standard one, else the name', async () => {
    const api = await boot(sample(), 'p7');
    const names = { SEARCH: 'Recherche' };
    expect(api.keyText({ fn: 'SEARCH', labels: { fr: 'CHERCHER' } }, 'fr', names)).toBe('CHERCHER');
    expect(api.keyText({ fn: 'SEARCH' }, 'fr', names)).toBe('Recherche');
    expect(api.keyText({ fn: 'LOCK', labels: {} }, 'nl', names)).toBe('LOCK');
    const tabs = { articles: 'Articles' };
    expect(api.tabText({ id: 'articles', labels: { nl: 'Artikelen' } }, 'nl', tabs)).toBe('Artikelen');
    expect(api.tabText({ id: 'articles' }, 'nl', tabs)).toBe('Articles');
    expect(api.tabText({ id: 'x1', labels: { fr: 'PROMO' } }, 'nl', tabs)).toBe('PROMO');
    expect(api.tabText({ id: 'x2' }, 'nl', tabs)).toBe('x2');
  });

  it('counts the tabs holding a function', async () => {
    const api = await boot(sample(), 'p8');
    const layout = api.normalize(sample());
    expect(api.placements(layout, 'LOCK')).toBe(1);
    expect(api.placements(layout, 'PARK')).toBe(0);
  });
});

describe('the editor on the page', () => {
  it('stays idle on a page without editor', async () => {
    document.body.innerHTML = '<main></main>';
    vi.resetModules();
    const api = await loadScript(SOURCE, { bust: 'e0' });
    expect(api.boot()).toBe(null);
  });

  it('draws the bar, the grid, the functions and the selected tab, and posts the layout', async () => {
    await boot(sample(), 'e1');
    expect(texts('.ml-tab')).toEqual(['Articles', 'PROMO', 'Règlement']);
    expect(document.querySelectorAll('.ml-tab')[1].style.boxShadow).toBe('inset 0 -5px 0 #1f5fd6');
    expect(document.querySelectorAll('.ml-tab')[0].style.boxShadow).toBe('');
    expect(texts('.ml-key')).toEqual(['Recherche article', 'FERMER']);
    expect(document.querySelectorAll('.ml-key')[1].classList.contains('ml-hidden')).toBe(true);
    expect(document.querySelectorAll('.ml-key')[1].style.color).toBe('rgb(17, 17, 17)');
    document.querySelectorAll('.ml-tab')[1].click();
    expect(document.querySelectorAll('.ml-tab')[1].style.color).toBe('rgb(255, 255, 255)');
    document.querySelectorAll('.ml-tab')[0].click();
    expect(texts('.ml-fn-count')).toEqual(['1', '1', 'non placée']);
    expect(document.querySelector('.ml-props-title').textContent).toBe('Onglet · Articles');
    expect(posted().tabs.length).toBe(3);
    expect([...document.querySelectorAll('.ml-add-tab option')].map((o) => o.value)).toEqual(['', '*', 'caisse']);
  });

  it('starts empty on a layout without tab', async () => {
    await boot({}, 'e2');
    expect(document.querySelector('.ml-grid').textContent).toBe('Aucun onglet');
    expect(document.querySelector('.ml-props').innerHTML).toBe('');
  });

  it('shows what the settlement tab does, and an empty tab invites the drop', async () => {
    await boot(sample(), 'e3');
    document.querySelectorAll('.ml-tab')[2].click();
    expect(document.querySelector('.ml-grid').textContent).toBe('Règlement direct');
    document.querySelectorAll('.ml-tab')[1].click();
    expect(document.querySelector('.ml-grid').textContent).toBe('Vide');
  });

  it('labels and colours the selected key in the language chosen', async () => {
    await boot(sample(), 'e4');
    document.querySelectorAll('.ml-key')[0].click();
    expect(document.querySelector('.ml-props-title').textContent).toBe('Touche · Recherche article');
    const inputs = document.querySelectorAll('.ml-labels input');
    inputs[1].value = 'Zoek';
    inputs[1].dispatchEvent(new Event('input'));
    expect(posted().tabs[0].keys[0].labels).toEqual({ nl: 'Zoek' });
    document.querySelectorAll('.ml-lang-choice')[1].click();
    expect(texts('.ml-key')[0]).toBe('Zoek');
    document.querySelectorAll('.ml-swatch')[1].click();
    expect(posted().tabs[0].keys[0].color).toBe('blue');
    expect(document.querySelectorAll('.ml-swatch')[1].title).toBe('Bleu');
    expect(document.querySelectorAll('.ml-swatch')[2].title).toBe('yellow');
    document.querySelector('.ml-swatch-none').click();
    expect(posted().tabs[0].keys[0].color).toBeUndefined();
  });

  it('moves and removes the selected key', async () => {
    await boot(sample(), 'e5');
    document.querySelectorAll('.ml-key')[0].click();
    let buttons = document.querySelectorAll('.ml-actions button');
    expect(buttons[0].disabled).toBe(true);
    buttons[1].click();
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['LOCK', 'SEARCH']);
    buttons = document.querySelectorAll('.ml-actions button');
    expect(buttons[1].disabled).toBe(true);
    buttons[0].click();
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['SEARCH', 'LOCK']);
    document.querySelectorAll('.ml-actions button')[2].click();
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['LOCK']);
  });

  it('moves and removes the selected tab', async () => {
    await boot(sample(), 'e6');
    document.querySelectorAll('.ml-tab')[1].click();
    document.querySelectorAll('.ml-actions button')[0].click();
    expect(posted().tabs.map((t) => t.id)).toEqual(['x1', 'articles', 'reglement']);
    document.querySelectorAll('.ml-actions button')[1].click();
    expect(posted().tabs.map((t) => t.id)).toEqual(['articles', 'x1', 'reglement']);
    document.querySelectorAll('.ml-actions button')[2].click();
    expect(posted().tabs.map((t) => t.id)).toEqual(['articles', 'reglement']);
    document.querySelectorAll('.ml-actions button')[2].click();
    document.querySelectorAll('.ml-actions button')[2].click();
    expect(posted().tabs).toEqual([]);
    expect(document.querySelector('.ml-grid').textContent).toBe('Aucun onglet');
  });

  it('adds a new tab and a standard one from the bar, up to the limit', async () => {
    await boot(sample(), 'e7');
    const add = document.querySelector('.ml-add-tab');
    add.value = '*';
    add.dispatchEvent(new Event('change'));
    expect(posted().tabs[3]).toEqual({ id: 'x2', labels: { fr: 'NOUVEL ONGLET' }, keys: [] });
    expect(document.querySelector('.ml-tab.ml-on').textContent).toBe('NOUVEL ONGLET');
    expect(document.querySelector('.ml-add-tab').disabled).toBe(true);
    const full = document.querySelector('.ml-add-tab');
    full.value = '*';
    full.dispatchEvent(new Event('change'));
    expect(posted().tabs.length).toBe(4);
  });

  it('adds a standard tab picked from the bar dropdown, selecting it', async () => {
    await boot(sample(), 'e7b');
    const add = document.querySelector('.ml-add-tab');
    add.value = 'caisse';
    add.dispatchEvent(new Event('change'));
    expect(posted().tabs.map((tab) => tab.id)).toEqual(['articles', 'x1', 'reglement', 'caisse']);
    expect(document.querySelector('.ml-tab.ml-on').textContent).toBe('Caisse');
  });

  it('builds an element with its class and text, and omits the class when none is given', async () => {
    const api = await boot(sample(), 'elx');
    const classed = api.el('span', 'ml-x', 'Hi');
    expect(classed.className).toBe('ml-x');
    expect(classed.textContent).toBe('Hi');
    const bare = api.el('span', '', null);
    expect(bare.className).toBe('');
    expect(bare.textContent).toBe('');
  });

  it('adds a function to the tab shown, and filters the functions', async () => {
    await boot(sample(), 'e8');
    document.querySelectorAll('.ml-tab')[1].click();
    document.querySelectorAll('.ml-fn-add')[2].click();
    expect(posted().tabs[1].keys).toEqual([{ fn: 'PARK', labels: {} }]);
    const filter = document.querySelector('.ml-filter');
    filter.value = 'verr';
    filter.dispatchEvent(new Event('input'));
    expect(texts('.ml-fn-label')).toEqual(['Verrouiller']);
  });

  it('restores the standard layout', async () => {
    await boot(sample(), 'e9');
    document.querySelector('.ml-reset').click();
    expect(posted().tabs.map((t) => t.id)).toEqual(['articles', 'reglement']);
  });

  it('redraws the grid when a function is hidden or shown', async () => {
    await boot(sample(), 'e10');
    const box = document.querySelector('input[name=hidden][value=LOCK]');
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    expect(document.querySelectorAll('.ml-hidden').length).toBe(0);
  });

  it('reorders the keys and places a function by dropping on the grid', async () => {
    await boot(sample(), 'e11');
    drop(document.querySelectorAll('.ml-key')[0], 'key:1');
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['LOCK', 'SEARCH']);
    drop(document.querySelector('.ml-grid'), 'key:0');
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['SEARCH', 'LOCK']);
    drop(document.querySelectorAll('.ml-key')[1], 'fn:PARK');
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['SEARCH', 'PARK', 'LOCK']);
    drop(document.querySelector('.ml-grid'), 'tab:x1');
    expect(posted().tabs[0].keys.length).toBe(3);
  });

  it('ignores a drop on the grid without tab, and a drop without payload', async () => {
    await boot({}, 'e12');
    drop(document.querySelector('.ml-grid'), 'fn:PARK');
    expect(posted().tabs).toEqual([]);
    await boot(sample(), 'e13');
    drop(document.querySelector('.ml-grid'), 'nothing');
    const event = new Event('drop', { bubbles: true, cancelable: true });
    document.querySelector('.ml-grid').dispatchEvent(event);
    expect(posted().tabs[0].keys.length).toBe(2);
  });

  it('reorders the tabs, moves a key and places a function by dropping on a tab', async () => {
    await boot(sample(), 'e14');
    drop(document.querySelectorAll('.ml-tab')[0], 'tab:x1');
    expect(posted().tabs.map((t) => t.id)).toEqual(['x1', 'articles', 'reglement']);
    drop(document.querySelectorAll('.ml-tab')[0], 'key:0');
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['SEARCH']);
    drop(document.querySelectorAll('.ml-tab')[0], 'fn:PARK');
    expect(posted().tabs[0].keys.map((k) => k.fn)).toEqual(['SEARCH', 'PARK']);
    drop(document.querySelectorAll('.ml-tab')[0], 'other:1');
    expect(posted().tabs[0].keys.length).toBe(2);
  });

  it('marks a drop target while something is dragged over it, and starts a drag with its payload', async () => {
    await boot(sample(), 'e15');
    const tab = document.querySelectorAll('.ml-tab')[0];
    tab.dispatchEvent(new Event('dragover', { cancelable: true }));
    expect(tab.classList.contains('ml-over')).toBe(true);
    tab.dispatchEvent(new Event('dragleave'));
    expect(tab.classList.contains('ml-over')).toBe(false);
    const start = new Event('dragstart');
    const transfer = { setData: vi.fn() };
    Object.defineProperty(start, 'dataTransfer', { value: transfer });
    document.querySelectorAll('.ml-fn')[0].dispatchEvent(start);
    expect(transfer.setData).toHaveBeenCalledWith('text/plain', 'fn:SEARCH');
    expect(transfer.effectAllowed).toBe('move');
  });
});
