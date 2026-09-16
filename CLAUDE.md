# imedge — test generation conventions

imedge is the STORE NODE of the impos suite: it aggregates what the
registers push, holds and exports the shop's referential, hosts the
back office and supervises the line. It sells nothing — no drawer, no
scanner, no payment terminal, no sale screen.

The domain model, the sync payloads and the back office are a COPY of
impos', kept file-identical on purpose (same package
com.intermarche.pos, same class names): the two nodes share a database
schema and a wire format, so a change to either must be carried across
by a plain file copy. Never "improve" a copied class on one side only.

## Test architecture — NON-NEGOTIABLE
- Plain unit tests only: JUnit 5 + Mockito. NEVER @QuarkusTest,
  never H2, never boot the application for a class-level test.
- Mock all collaborators.
- Panache entities are NOT bytecode-enhanced under plain mvn test:
  static finders resolve to PanacheEntityBase — mock them with
  Mockito.mockStatic(PanacheEntityBase.class), and neutralize
  persist() with Mockito.mockConstruction(<Entity>.class).
- Static mocks go in try-with-resources blocks.
- Each test must be fully isolated: no shared state, no ordering
  dependency, assertions on absolute expected values.

## Style
- Code and comments in English.
- Javadoc on EVERY method without exception, test methods and
  private helpers included.
- Assertions: org.junit.jupiter.api.Assertions only — never AssertJ.
- No blank lines inside method bodies.
- One complete, compilable test class per production class.

## Coverage
- Target: 100% JaCoCo branch coverage on the target class.
- Systematically cover BOTH arms (null and non-null) of every
  ternary and null guard — do not wait for a JaCoCo re-run.
- Always report the branch count, not only the percentage.

## Scope — STRICT
- Never modify anything under src/main. If a bug or an obstacle
  to testability is found, stop and report it in one line.
- Touch only the test class being generated.

## Per-class workflow
1. Read the target class (and only what you actually need).
2. Enumerate every branch before writing.
3. Write the test class → mvn -Dtest=XTest test → fix until green.
4. mvn verify → read JaCoCo for the class → fill missing branches.
5. Report: branch count, coverage, files read, iterations.

## Integration tests (*IT classes)

- ONE IT, gated by <skipITs>true</skipITs>: run it with
  `mvn -q verify -DskipUTs=true -DskipITs=false`.
- SecurityExtensionLivenessIT proves @RolesAllowed is ENFORCED by the
  container on the machine surfaces (CSV import, GraphQL), which a
  Mockito unit test can never detect because it never runs through a
  CDI interceptor. It is the ONE reason this project boots Quarkus in
  a test.
- No Playwright here, and no browser scenario. The two-node proof
  (DemoStoreIT) boots a REGISTER, plays a sale on its screen and then
  polls this node's dashboard over HTTP: the application under test is
  the register, so that class belongs to impos and runs from there,
  against a node this project has started.
