import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of confirm.js — the data-confirm guard behind the "Livrer
 * maintenant" / "Rejouer" / "Annuler" buttons. The file is a browser IIFE that registers a
 * delegated submit listener and defines handleSubmit(). The bench mocks window.confirm and
 * drives handleSubmit() with a fake submit event, asserting the three arms: no attribute
 * (submits), confirmed (submits), declined (prevented).
 */

/**
 * Project-relative path of the source under test.
 */
const SOURCE = 'src/main/resources/META-INF/resources/admin/confirm.js';

/**
 * The module object exposing handleSubmit() once loaded.
 */
let confirmModule;

/**
 * Builds a fake submit event over a form carrying (or not) a data-confirm attribute.
 *
 * @param {?string} message the data-confirm prompt, or null for a form without it
 * @returns {object} the fake event with a spied preventDefault
 */
function submitEvent(message) {
  return {
    target: { getAttribute: (name) => (name === 'data-confirm' ? message : null) },
    preventDefault: vi.fn(),
  };
}

/**
 * Imports the module once; its boot only registers a document listener, which is harmless.
 */
beforeAll(async () => {
  confirmModule = await loadScript(SOURCE);
});

/**
 * Restores window.confirm after the suite.
 */
afterAll(() => {
  vi.unstubAllGlobals();
});

/**
 * Resets any confirm stub before each test.
 */
beforeEach(() => {
  vi.unstubAllGlobals();
});

describe('handleSubmit', () => {
  /**
   * A form without a data-confirm attribute submits normally: the message is falsy so the
   * compound short-circuits on its first arm and preventDefault is never called.
   */
  it('lets a form without data-confirm submit', () => {
    const confirm = vi.fn(() => false);
    vi.stubGlobal('confirm', confirm);
    const event = submitEvent(null);
    confirmModule.handleSubmit(event);
    expect(confirm).not.toHaveBeenCalled();
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  /**
   * A confirmed prompt lets the form submit: the message is truthy and confirm returns true,
   * so !confirm(...) is false and preventDefault is never called.
   */
  it('lets the form submit when the prompt is confirmed', () => {
    const confirm = vi.fn(() => true);
    vi.stubGlobal('confirm', confirm);
    const event = submitEvent('Livrer maintenant le flux OFFERS ?');
    confirmModule.handleSubmit(event);
    expect(confirm).toHaveBeenCalledWith('Livrer maintenant le flux OFFERS ?');
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  /**
   * A declined prompt cancels the submission: the message is truthy and confirm returns
   * false, so !confirm(...) is true and preventDefault is called.
   */
  it('cancels the submission when the prompt is declined', () => {
    const confirm = vi.fn(() => false);
    vi.stubGlobal('confirm', confirm);
    const event = submitEvent('Annuler la planification du flux OFFERS ?');
    confirmModule.handleSubmit(event);
    expect(confirm).toHaveBeenCalled();
    expect(event.preventDefault).toHaveBeenCalledTimes(1);
  });
});
