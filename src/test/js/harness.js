import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/**
 * Absolute path of the project root, derived from this file's location
 * (src/test/js/harness.js is three directories below the root).
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * Loads one of the static browser sources and returns its top-level functions.
 * <p>
 * The sources are plain IIFEs, not ES modules, and stay untouched on disk. They are made
 * importable by the `expose-ui-iife` Vite plugin (see vitest.config.js), which injects a
 * capture of each IIFE's top-level functions onto a `__ui_exports__` object and default-
 * exports it. Importing through Vite — rather than reading the file and eval-ing it — is
 * what lets Istanbul instrument the code under its real path, so branch coverage is
 * attributed to the actual source file. The IIFE body runs once against the current jsdom
 * document on first import; callers that boot DOM code control the DOM beforehand so that
 * run does not throw.
 *
 * @param {string} relativePath Project-relative path of the source, e.g.
 *   "src/main/resources/META-INF/resources/admin-assets/sync-refresh.js".
 * @param {object} [options] Loading options.
 * @param {string|number} [options.bust] A re-evaluation marker kept for callers that boot the
 *   same source several times (a second boot covers a boot arm a single evaluation cannot
 *   reach). The fresh evaluation is driven by the caller's {@code vi.resetModules()}, not by a
 *   URL query: a query-suffixed id (…/menu-layout.js?bust=p1) is instrumented but attributed
 *   to that suffixed path, which the coverage include (admin-assets/*.js) does not match, so
 *   the file would vanish from the report. Importing the real, unsuffixed path keeps Istanbul's
 *   attribution on the actual source file while resetModules still forces the re-run.
 * @returns {Promise<object>} The object exposing the source's top-level functions.
 */
export async function loadScript(relativePath, options = {}) {
  void options.bust;
  const absolute = resolve(ROOT, relativePath);
  const url = pathToFileURL(absolute).href;
  const module = await import(/* @vite-ignore */ url);
  return module.default;
}
