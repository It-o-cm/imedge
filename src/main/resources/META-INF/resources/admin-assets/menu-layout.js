/*
 * The KEY LAYOUT editor of a cash menu model: the register's grid and bottom bar
 * drawn as the cashier sees them, the functions to place beside them, and the
 * properties — labels per language, colour — of the tab or key selected.
 * Everything is arranged by dragging (or by the arrows and the + of each
 * function, for a touch screen); the layout travels in the form's hidden
 * "layout" field as JSON, sanitised again by the server.
 */
(function () {
    'use strict';

    /** The editor state: the layout, the selection and the language shown. */
    var editor = null;

    /**
     * Moves an element of a list from one position to another, in place.
     *
     * @param {Array} list the list
     * @param {number} from the current position
     * @param {number} to the wanted position, counted before the removal
     * @returns {Array} the list
     */
    function moveItem(list, from, to) {
        if (from < 0 || from >= list.length) {
            return list;
        }
        var item = list.splice(from, 1)[0];
        var at = to > from ? to - 1 : to;
        at = Math.max(0, Math.min(list.length, at));
        list.splice(at, 0, item);
        return list;
    }

    /**
     * Gives every tab and key of a layout read from the server its lists and
     * maps: the server leaves empty ones out.
     *
     * @param {object} layout the layout read
     * @returns {object} the same layout, completed
     */
    function normalize(layout) {
        layout.tabs = layout.tabs || [];
        layout.tabs.forEach(function (tab) {
            tab.labels = tab.labels || {};
            tab.keys = tab.keys || [];
            tab.keys.forEach(function (key) {
                key.labels = key.labels || {};
            });
        });
        return layout;
    }

    /**
     * Finds a tab of the layout by its id.
     *
     * @param {object} layout the layout
     * @param {string} id the tab id
     * @returns {?object} the tab, or null
     */
    function findTab(layout, id) {
        for (var i = 0; i < layout.tabs.length; i++) {
            if (layout.tabs[i].id === id) {
                return layout.tabs[i];
            }
        }
        return null;
    }

    /**
     * Tells whether a tab opens the payment screen instead of a grid.
     *
     * @param {object} tab the tab
     * @returns {boolean} true for the settlement tab
     */
    function isDirect(tab) {
        return tab.id === 'reglement';
    }

    /**
     * Places a function in a tab, once: at a position, or at the end.
     *
     * @param {object} layout the layout
     * @param {string} tabId the tab
     * @param {string} fn the function name
     * @param {?number} at the position, or null for the end
     * @param {number} max the largest number of keys of a tab
     * @returns {boolean} true when the key was placed
     */
    function addKey(layout, tabId, fn, at, max) {
        var tab = findTab(layout, tabId);
        if (!tab || isDirect(tab) || tab.keys.length >= max || keyIndex(tab, fn) >= 0) {
            return false;
        }
        var key = { fn: fn, labels: {} };
        var position = at === null || at === undefined ? tab.keys.length : Math.max(0, Math.min(tab.keys.length, at));
        tab.keys.splice(position, 0, key);
        return true;
    }

    /**
     * Finds the position of a function in a tab.
     *
     * @param {object} tab the tab
     * @param {string} fn the function name
     * @returns {number} the position, -1 when the tab does not hold it
     */
    function keyIndex(tab, fn) {
        for (var i = 0; i < tab.keys.length; i++) {
            if (tab.keys[i].fn === fn) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Moves a key of one tab to the end of another; a function the target
     * already holds stays where it is.
     *
     * @param {object} layout the layout
     * @param {string} fromId the tab holding the key
     * @param {number} index the key's position
     * @param {string} toId the target tab
     * @param {number} max the largest number of keys of a tab
     * @returns {boolean} true when the key moved
     */
    function moveKeyToTab(layout, fromId, index, toId, max) {
        var from = findTab(layout, fromId);
        var to = findTab(layout, toId);
        if (!from || !to || fromId === toId || isDirect(to) || !from.keys[index]) {
            return false;
        }
        var key = from.keys[index];
        if (keyIndex(to, key.fn) >= 0 || to.keys.length >= max) {
            return false;
        }
        from.keys.splice(index, 1);
        to.keys.push(key);
        return true;
    }

    /**
     * The next free id of a created tab.
     *
     * @param {object} layout the layout
     * @returns {string} the id, x1 for the first
     */
    function nextCustomId(layout) {
        var n = 1;
        while (findTab(layout, 'x' + n)) {
            n++;
        }
        return 'x' + n;
    }

    /**
     * Adds a tab at the end of the bar: a standard one taken out earlier, or a
     * new one with the given French label.
     *
     * @param {object} layout the layout
     * @param {?string} id the standard tab id, or null for a new tab
     * @param {string} label the French label of a new tab
     * @param {number} max the largest number of tabs
     * @returns {?string} the id of the tab added, or null when none was
     */
    function addTab(layout, id, label, max) {
        if (layout.tabs.length >= max || (id && findTab(layout, id))) {
            return null;
        }
        var tab = { id: id || nextCustomId(layout), labels: {}, keys: [] };
        if (!id) {
            tab.labels.fr = label;
        }
        layout.tabs.push(tab);
        return tab.id;
    }

    /**
     * Takes a tab out of the bar.
     *
     * @param {object} layout the layout
     * @param {string} id the tab id
     * @returns {boolean} true when a tab was taken out
     */
    function removeTab(layout, id) {
        for (var i = 0; i < layout.tabs.length; i++) {
            if (layout.tabs[i].id === id) {
                layout.tabs.splice(i, 1);
                return true;
            }
        }
        return false;
    }

    /**
     * Sets or clears the label of a tab or key in one language.
     *
     * @param {object} target the tab or key
     * @param {string} lang the language
     * @param {string} text the label, blank for the standard one
     */
    function setLabel(target, lang, text) {
        target.labels = target.labels || {};
        var value = (text || '').trim();
        if (value) {
            target.labels[lang] = value;
        } else {
            delete target.labels[lang];
        }
    }

    /**
     * Sets or clears the colour of a tab or key.
     *
     * @param {object} target the tab or key
     * @param {?string} color the colour name, or null for the standard one
     */
    function setColor(target, color) {
        if (color) {
            target.color = color;
        } else {
            delete target.color;
        }
    }

    /**
     * The text a key shows in a language: its own label, else the standard one.
     *
     * @param {object} key the key
     * @param {string} lang the language
     * @param {object} names the standard labels of the functions, by name
     * @returns {string} the text
     */
    function keyText(key, lang, names) {
        return (key.labels && key.labels[lang]) || names[key.fn] || key.fn;
    }

    /**
     * The text a tab shows in a language: its own label, else the standard one,
     * else its French label.
     *
     * @param {object} tab the tab
     * @param {string} lang the language
     * @param {object} names the standard labels of the tabs, by id
     * @returns {string} the text
     */
    function tabText(tab, lang, names) {
        var labels = tab.labels || {};
        return labels[lang] || names[tab.id] || labels.fr || tab.id;
    }

    /**
     * Counts the tabs that hold a function.
     *
     * @param {object} layout the layout
     * @param {string} fn the function name
     * @returns {number} the number of tabs
     */
    function placements(layout, fn) {
        var count = 0;
        layout.tabs.forEach(function (tab) {
            if (keyIndex(tab, fn) >= 0) {
                count++;
            }
        });
        return count;
    }

    /**
     * Builds an element with a class and a text.
     *
     * @param {string} tag the tag name
     * @param {string} cls the class, possibly empty
     * @param {?string} text the text, or null
     * @returns {HTMLElement} the element
     */
    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) {
            node.className = cls;
        }
        if (text !== null && text !== undefined) {
            node.textContent = text;
        }
        return node;
    }

    /**
     * Paints a tab or key with its colour.
     *
     * @param {HTMLElement} node the element
     * @param {?string} color the colour name, or null
     */
    function paint(node, color) {
        var hex = color ? editor.data.colors[color] : null;
        if (hex) {
            node.style.background = hex;
            node.style.color = color === 'yellow' || color === 'orange' ? '#111' : '#fff';
            node.style.borderColor = hex;
        }
    }

    /**
     * Marks a tab not shown with its colour, as the register does: a band
     * along the bottom of the muted face.
     *
     * @param {HTMLElement} node the tab element
     * @param {?string} color the colour name, or null
     */
    function band(node, color) {
        var hex = color ? editor.data.colors[color] : null;
        if (hex) {
            node.style.boxShadow = 'inset 0 -5px 0 ' + hex;
        }
    }

    /**
     * Writes the layout into the form's hidden field.
     */
    function sync() {
        editor.field.value = JSON.stringify(editor.layout);
    }

    /**
     * Redraws the whole editor and writes the layout into the form.
     */
    function render() {
        renderBar();
        renderGrid();
        renderPool();
        renderProps();
        sync();
    }

    /**
     * Reads the payload of a drag.
     *
     * @param {DragEvent} event the drop event
     * @returns {Array<string>} the kind and the value, e.g. ['key', '3']
     */
    function payload(event) {
        var raw = event.dataTransfer ? event.dataTransfer.getData('text/plain') : '';
        var mark = raw.indexOf(':');
        return mark < 0 ? ['', ''] : [raw.substring(0, mark), raw.substring(mark + 1)];
    }

    /**
     * Makes an element a drag source.
     *
     * @param {HTMLElement} node the element
     * @param {string} value the payload, kind:value
     */
    function dragSource(node, value) {
        node.draggable = true;
        node.addEventListener('dragstart', function (event) {
            event.dataTransfer.setData('text/plain', value);
            event.dataTransfer.effectAllowed = 'move';
        });
    }

    /**
     * Makes an element a drop target.
     *
     * @param {HTMLElement} node the element
     * @param {function(string, string)} onDrop called with the kind and value dropped
     */
    function dropTarget(node, onDrop) {
        node.addEventListener('dragover', function (event) {
            event.preventDefault();
            node.classList.add('ml-over');
        });
        node.addEventListener('dragleave', function () {
            node.classList.remove('ml-over');
        });
        node.addEventListener('drop', function (event) {
            event.preventDefault();
            event.stopPropagation();
            node.classList.remove('ml-over');
            var dropped = payload(event);
            onDrop(dropped[0], dropped[1]);
        });
    }

    /**
     * Handles what is dropped on a tab of the bar: a tab goes before it, a key
     * moves into it, a function is placed in it.
     *
     * @param {string} id the tab dropped on
     * @param {string} kind the kind dropped
     * @param {string} value the value dropped
     */
    function dropOnTab(id, kind, value) {
        var layout = editor.layout;
        if (kind === 'tab') {
            var from = layout.tabs.indexOf(findTab(layout, value));
            var to = layout.tabs.indexOf(findTab(layout, id));
            moveItem(layout.tabs, from, to);
        } else if (kind === 'key') {
            moveKeyToTab(layout, editor.tab, Number(value), id, editor.data.maxKeys);
        } else if (kind === 'fn') {
            addKey(layout, id, value, null, editor.data.maxKeys);
        }
        render();
    }

    /**
     * Handles what is dropped on the grid: a key goes before the given
     * position, a function is placed there.
     *
     * @param {?number} at the position dropped on, or null for the end
     * @param {string} kind the kind dropped
     * @param {string} value the value dropped
     */
    function dropOnGrid(at, kind, value) {
        var tab = findTab(editor.layout, editor.tab);
        if (!tab) {
            return;
        }
        if (kind === 'key') {
            moveItem(tab.keys, Number(value), at === null ? tab.keys.length : at);
            editor.key = null;
        } else if (kind === 'fn') {
            addKey(editor.layout, editor.tab, value, at, editor.data.maxKeys);
        }
        render();
    }

    /**
     * Draws the bottom bar's tabs, and the choice adding a tab above it.
     */
    function renderBar() {
        var bar = editor.root.querySelector('.ml-bar');
        var slot = editor.root.querySelector('.ml-add-slot');
        bar.innerHTML = '';
        slot.innerHTML = '';
        editor.layout.tabs.forEach(function (tab) {
            var button = el('button', 'ml-tab' + (tab.id === editor.tab ? ' ml-on' : ''),
                tabText(tab, editor.lang, editor.data.tabNames));
            button.type = 'button';
            button.title = button.textContent;
            button.dataset.tab = tab.id;
            if (tab.id === editor.tab) {
                paint(button, tab.color);
            } else {
                band(button, tab.color);
            }
            button.addEventListener('click', function () {
                editor.tab = tab.id;
                editor.key = null;
                render();
            });
            dragSource(button, 'tab:' + tab.id);
            dropTarget(button, function (kind, value) { dropOnTab(tab.id, kind, value); });
            bar.appendChild(button);
        });
        var add = el('select', 'ml-add-tab', null);
        add.setAttribute('aria-label', editor.data.texts.addTab);
        add.className = 'input-control ml-add-tab';
        add.appendChild(new Option('+ ' + editor.data.texts.addTab, ''));
        add.appendChild(new Option(editor.data.texts.newTab, '*'));
        Object.keys(editor.data.tabNames).forEach(function (id) {
            if (!findTab(editor.layout, id)) {
                add.appendChild(new Option(editor.data.tabNames[id], id));
            }
        });
        add.disabled = editor.layout.tabs.length >= editor.data.maxTabs;
        add.addEventListener('change', function () {
            var added = addTab(editor.layout, add.value === '*' ? null : add.value,
                editor.data.texts.newTabLabel, editor.data.maxTabs);
            if (added) {
                editor.tab = added;
                editor.key = null;
            }
            render();
        });
        slot.appendChild(add);
    }

    /**
     * Draws the grid of the selected tab, or what the settlement tab does.
     */
    function renderGrid() {
        var grid = editor.root.querySelector('.ml-grid');
        grid.innerHTML = '';
        grid.style.gridTemplateColumns = 'repeat(' + editor.data.columns + ', minmax(0, 1fr))';
        var tab = findTab(editor.layout, editor.tab);
        if (!tab) {
            grid.appendChild(el('p', 'ml-note', editor.data.texts.noTab));
            return;
        }
        if (isDirect(tab)) {
            grid.appendChild(el('p', 'ml-note', editor.data.texts.directTab));
            return;
        }
        tab.keys.forEach(function (key, index) {
            var hidden = editor.hidden().indexOf(key.fn) >= 0;
            var button = el('button', 'ml-key' + (editor.key === index ? ' ml-on' : '') + (hidden ? ' ml-hidden' : ''),
                keyText(key, editor.lang, editor.data.functions));
            button.type = 'button';
            if (hidden) {
                button.title = editor.data.texts.hiddenKey;
            }
            paint(button, key.color);
            button.addEventListener('click', function () {
                editor.key = index;
                render();
            });
            dragSource(button, 'key:' + index);
            dropTarget(button, function (kind, value) { dropOnGrid(index, kind, value); });
            grid.appendChild(button);
        });
        if (tab.keys.length === 0) {
            grid.appendChild(el('p', 'ml-note', editor.data.texts.emptyTab));
        }
    }

    /**
     * Draws the list of the functions, each with the number of tabs holding it.
     */
    function renderPool() {
        var list = editor.root.querySelector('.ml-pool-list');
        var filter = (editor.root.querySelector('.ml-filter').value || '').trim().toLowerCase();
        list.innerHTML = '';
        Object.keys(editor.data.functions).forEach(function (fn) {
            var label = editor.data.functions[fn];
            if (filter && label.toLowerCase().indexOf(filter) < 0) {
                return;
            }
            var count = placements(editor.layout, fn);
            var item = el('li', 'ml-fn' + (count === 0 ? ' ml-free' : ''), null);
            item.appendChild(el('span', 'ml-fn-label', label));
            item.appendChild(el('span', 'ml-fn-count', count === 0 ? editor.data.texts.unplaced : String(count)));
            var add = el('button', 'ml-fn-add', '+');
            add.type = 'button';
            add.title = editor.data.texts.addKey;
            add.addEventListener('click', function () {
                addKey(editor.layout, editor.tab, fn, null, editor.data.maxKeys);
                render();
            });
            item.appendChild(add);
            dragSource(item, 'fn:' + fn);
            list.appendChild(item);
        });
    }

    /**
     * Draws the properties of the selected key, or else of the selected tab.
     */
    function renderProps() {
        var props = editor.root.querySelector('.ml-props');
        props.innerHTML = '';
        var tab = findTab(editor.layout, editor.tab);
        if (!tab) {
            return;
        }
        var key = editor.key === null ? null : tab.keys[editor.key];
        var target = key || tab;
        var standard = key ? editor.data.functions[key.fn] : (editor.data.tabNames[tab.id] || '');
        props.appendChild(el('h3', 'ml-props-title', key ? editor.data.texts.keyProps + ' · ' + standard
            : editor.data.texts.tabProps + ' · ' + tabText(tab, 'fr', editor.data.tabNames)));
        var labels = el('div', 'ml-labels', null);
        editor.data.languages.forEach(function (lang) {
            var field = el('label', 'ml-label', null);
            field.appendChild(el('span', 'ml-lang', lang.toUpperCase()));
            var input = el('input', 'input-control', null);
            input.type = 'text';
            input.maxLength = 40;
            input.value = (target.labels && target.labels[lang]) || '';
            input.placeholder = standard || editor.data.texts.labelRequired;
            input.addEventListener('input', function () {
                setLabel(target, lang, input.value);
                renderBar();
                renderGrid();
                sync();
            });
            field.appendChild(input);
            labels.appendChild(field);
        });
        props.appendChild(labels);
        var colors = el('div', 'ml-colors', null);
        var none = el('button', 'ml-swatch ml-swatch-none' + (target.color ? '' : ' ml-on'), editor.data.texts.standardColor);
        none.type = 'button';
        none.addEventListener('click', function () {
            setColor(target, null);
            render();
        });
        colors.appendChild(none);
        Object.keys(editor.data.colors).forEach(function (color) {
            var swatch = el('button', 'ml-swatch' + (target.color === color ? ' ml-on' : ''), null);
            swatch.type = 'button';
            swatch.title = editor.data.colorNames[color] || color;
            swatch.setAttribute('aria-label', swatch.title);
            swatch.style.background = editor.data.colors[color];
            swatch.addEventListener('click', function () {
                setColor(target, color);
                render();
            });
            colors.appendChild(swatch);
        });
        props.appendChild(colors);
        props.appendChild(actions(tab, key));
    }

    /**
     * Builds the actions of the selection: move it, take it out.
     *
     * @param {object} tab the selected tab
     * @param {?object} key the selected key, or null when the tab is selected
     * @returns {HTMLElement} the row of actions
     */
    function actions(tab, key) {
        var row = el('div', 'ml-actions', null);
        var list = key ? tab.keys : editor.layout.tabs;
        var index = key ? editor.key : editor.layout.tabs.indexOf(tab);
        var before = el('button', 'btn btn-small', '←');
        before.type = 'button';
        before.title = editor.data.texts.moveBefore;
        before.disabled = index === 0;
        before.addEventListener('click', function () {
            moveItem(list, index, index - 1);
            if (key) {
                editor.key = index - 1;
            }
            render();
        });
        var after = el('button', 'btn btn-small', '→');
        after.type = 'button';
        after.title = editor.data.texts.moveAfter;
        after.disabled = index === list.length - 1;
        after.addEventListener('click', function () {
            moveItem(list, index, index + 2);
            if (key) {
                editor.key = index + 1;
            }
            render();
        });
        var remove = el('button', 'btn btn-small btn-danger', key ? editor.data.texts.removeKey : editor.data.texts.removeTab);
        remove.type = 'button';
        remove.addEventListener('click', function () {
            if (key) {
                tab.keys.splice(index, 1);
                editor.key = null;
            } else {
                removeTab(editor.layout, tab.id);
                editor.tab = editor.layout.tabs.length ? editor.layout.tabs[0].id : null;
            }
            render();
        });
        row.appendChild(before);
        row.appendChild(after);
        row.appendChild(remove);
        return row;
    }

    /**
     * Starts the editor on the page, when the page carries one.
     *
     * @returns {?object} the editor state, or null when the page has no editor
     */
    function boot() {
        var root = document.getElementById('menuLayoutEditor');
        if (!root) {
            return null;
        }
        var data = JSON.parse(document.getElementById('menuLayoutData').textContent);
        var form = root.closest('form');
        editor = {
            root: root,
            data: data,
            layout: normalize(data.layout),
            field: form.querySelector('input[name=layout]'),
            tab: data.layout.tabs && data.layout.tabs.length ? data.layout.tabs[0].id : null,
            key: null,
            lang: 'fr',
            hidden: function () {
                return Array.prototype.map.call(form.querySelectorAll('input[name=hidden]:checked'),
                    function (box) { return box.value; });
            }
        };
        root.querySelectorAll('.ml-lang-choice').forEach(function (choice) {
            choice.addEventListener('click', function () {
                editor.lang = choice.dataset.lang;
                root.querySelectorAll('.ml-lang-choice').forEach(function (other) {
                    other.classList.toggle('ml-on', other === choice);
                });
                render();
            });
        });
        root.querySelector('.ml-filter').addEventListener('input', renderPool);
        dropTarget(root.querySelector('.ml-grid'), function (kind, value) { dropOnGrid(null, kind, value); });
        root.querySelector('.ml-reset').addEventListener('click', function () {
            editor.layout = normalize(JSON.parse(JSON.stringify(data.standard)));
            editor.tab = editor.layout.tabs[0].id;
            editor.key = null;
            render();
        });
        form.querySelectorAll('input[name=hidden]').forEach(function (box) {
            box.addEventListener('change', renderGrid);
        });
        render();
        return editor;
    }

    boot();
})();
