# Coverage campaign — imedge

Objective: **100 %** of branches AND lines (JaCoCo) and 100 % of branches (vitest/istanbul),
with no exclusion, no `@Generated`, no assertion-free test. The gate in `pom.xml` is raised
from 0.99 to 1.00 and the vitest config carries a 100 % threshold; neither ever goes down.

## Baseline (2026-11-10, `mvn -o clean verify -DskipITs=true`)

| Counter | Covered / Total | % |
|---|---|---|
| Java BRANCH | 21023 / 21028 | 99.9762 % |
| Java LINE | 40466 / 40478 | 99.9704 % |
| JS branch (3 of 4 files attributed) | 41 / 41 | 100 % |

## Work-list (classes/files with a missed branch or line, sorted by missed branches)

| File | Missed branch | Missed line | Resolution |
|---|---|---|---|
| `com.intermarche.edge.service.EcommerceReturnService` | 3 | 0 | 1 dead sub-condition removed, 2 reachable arms tested |
| `com.intermarche.edge.ui.admin.AdminCashMenuResource` | 2 | 10 | delete() test resurrected (unclosed Javadoc) + editorData() catch seam |
| `com.intermarche.edge.domain.store.MenuLayout` | 0 | 2 | defensive `toJson()` catch — minimal seam + test (copied class) |
| `admin-assets/menu-layout.js` | (unmeasured) | (unmeasured) | lost istanbul attribution (bust query) — harness fix; then 2 real branches covered |

`MenuLayout` is a **copied class** (identical to impos' `com.intermarche.pos.domain.store.MenuLayout`
except package/imports); the src/main and test changes are mirrored there character-for-character.
`EcommerceReturnService` and `AdminCashMenuResource` are imedge-only (no impos counterpart).

## Final (2026-11-10)

| Counter | Covered / Total | % |
|---|---|---|
| Java BRANCH | 21026 / 21026 | 100 % |
| Java LINE | 40478 / 40478 | 100 % |
| JS branch (4 of 4 files attributed) | 220 / 220 | 100 % |
| JS statement | 400 / 400 | 100 % |

Remaining classes with a gap: **0**.

## src/main changes (each minimal, behaviour-preserving, one line of justification)

- `service/EcommerceReturnService.java`: removed the dead `found == null ||` sub-condition in
  `previousReturns` (case 1 — `match()` guarantees every value put into `matched` is non-null,
  so that leg could never be taken). The two remaining reachable legs (null `lineUid`, blank
  `lineUid`, both reached by EAN matching) and the already-counted leg are now tested.
- `ui/admin/AdminCashMenuResource.java`: `MAPPER` made package-private non-final (was
  `private static final`) — seam so a unit test can swap in a mapper that throws and drive the
  defensive `editorData()` catch that no real editor data can reach (case 3).
- `domain/store/MenuLayout.java`: `MAPPER` made package-private non-final — seam for the
  defensive `toJson()` catch (case 3). Mirrored from impos.

## Test-only fixes (not src/main)

- `src/test/java/.../ui/admin/AdminCashMenuResourceTest.java`: closed an unclosed Javadoc
  (`/** … is.` with no `*/`) on the `delete` test. The malformed comment had silently swallowed
  the whole `deleteRefusesTheUnknownAndDeletesTheKnown` method, disabling it — which is why
  `delete()` showed as uncovered. Added the `editorData()` serialisation-failure test.
- `src/test/js/harness.js`: `loadScript` no longer appends a `?bust=` query to the import URL.
  The query made istanbul attribute coverage to `…/menu-layout.js?bust=p1`, which the
  `admin-assets/*.js` include does not match, so the file vanished from the report;
  re-evaluation is already driven by the caller's `vi.resetModules()`.
