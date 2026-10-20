# Portway verification report

Date: 19 October 2026

## Assessment

Portway meets the documented scope of a **migration assistant for a bounded C# subset**. Its
parser, rule engine, optional AI repair, compilation, review API/UI, CLI and packaging are
implemented and exercised. It is not a complete automatic .NET replacement: compiling generated
code does not establish behavioral equivalence or complete manual migration work.

No separate acceptance specification was found. This review used README.md, ARCHITECTURE.md,
PARSER-NOTES.md, the sample, golden fixtures and CI configuration as the requirements baseline.
The pre-existing untracked STUDY-GUIDE.md and BookstoreApi.zip were left unchanged.

## Verification evidence

| Check | Result |
|---|---|
| `mvn -B verify` | 311 tests: 250 core, 61 application; zero failures, errors or skips |
| Core JaCoCo line coverage | 88.21%; exceeds the 70% gate |
| `npm test --prefix web` | 13 tests pass, including slow re-verification polling |
| `npm run build --prefix web` | TypeScript and Vite production build pass |
| .NET 8 sample build in SDK container | Pass, zero warnings/errors |
| Docker image build | Pass; packaged frontend and backend tested together |
| `BASE=http://127.0.0.1:18080 BUILD=true bash scripts/smoke-test.sh` | All 25 checks pass; includes a real Maven package of downloaded output |
| Packaged CLI migration | 17 generated files, 16 methods: 14 rule mapped, 2 manual; compile PASS |
| Generated application's Maven package | Pass |
| Generated application boot on PostgreSQL 16 | Pass, using a disposable schema and explicit `ddl-auto=create-drop` |
| Generated API runtime | List/create/read/delete, validation 400, missing records 404, create Location header and author serialization pass |
| Golden expectations | All ten cases pass; reviewed the sole golden change adding the enum finding |
| Patch and script checks | `git diff --check` and `bash -n scripts/smoke-test.sh` pass |

The initial sandboxed backend run could not bind mock HTTP servers or access Docker. It was rerun
with the necessary permissions. The successful runs include PostgreSQL Testcontainers, the real
Docker Maven compiler, schema boot tests and mocked AI providers. Expected compiler errors from
negative tests are present in logs; they are not test failures.

## Requirements traced to evidence

| Documented requirement | Assessment |
|---|---|
| Parse C# into structural IR and reject unsupported syntax | Covered by parser/subset tests and ten golden cases |
| Deterministic generation of entities, repositories, services, controllers and project files | Golden, generated compilation and runtime checks pass for the tested subset |
| AI only fills eligible method bodies, with schema validation, budgets, caching and repair | WireMock and PostgreSQL AI pipeline tests pass; live provider behavior not certified |
| Compile generated Java and surface errors/manual work | In-process and Docker verification tests pass; the two sample manual methods remain explicit |
| Confidence reflects the weakest method | Existing migration/API tests pass |
| Review, edits, rejection, download and re-verification | Integration and smoke tests pass; stale-result defects fixed in this review |
| Safe ZIP ingestion and bounded execution | Existing and new regression tests pass; ignored entries now count toward inflation limits |
| Docker compiler isolation | Generated builds pass; unsafe custom build configuration is refused before networked warm-up |
| Frontend filters, review actions and progress | Component tests, new polling regression and production build pass; browser visual testing unavailable |
| CLI and local packaged deployment | Tested successfully |
| Published image/Azure deployment | Configuration reviewed; no release or deployment performed |

## Fixes made during this review

1. **Stale compile results after review:** edits and transitions into/out of rejection now invalidate
   the project and all file results. Old compile findings are removed until verification runs again.
2. **Re-verification races:** a database lock and RUNNING state prevent concurrent edits, deletion
   and duplicate verification from publishing results against a different snapshot. Queue rejection
   leaves the job retryable. Verifier exceptions become persisted failures and terminal events.
3. **False per-file PASS on infrastructure errors:** global build failures leave affected Java files
   NOT_RUN, instead of declaring them compiled merely because no file-specific diagnostic exists.
4. **Frontend timeout-by-polling:** verification remains pending until the actual job finishes,
   instead of refreshing only four times and stopping at 20 seconds. Reports and job caches refresh.
5. **Sample queue rejection cleanup:** unsuccessful sample submission removes the orphan job and
   work directory, matching uploaded-job behavior.
6. **Ignored ZIP entries bypassing limits:** ignored files and directory payloads are explicitly
   drained under the same per-entry and cumulative inflation limits. Archives should exclude build
   output; the integration upload fixture now does so.
7. **Networked Maven warm-up executing edited builds:** only the generated Spring Boot parent and
   build configuration are accepted. Custom plugins, parents, profiles, modules, repositories and
   `.mvn` configuration are refused. Warm-up receives only pom.xml; offline compilation mounts the
   dependency cache read-only. Regression tests verify refusal before any Docker/cache activity.
8. **Silent enum contract changes:** generated enums now produce `ENUM_REPRESENTATION` in review
   findings and migration notes. Numeric values, flags, stored data and JSON representation require
   explicit review. The generated runtime returned `"Fiction"` for an enum supplied as numeric `1`.
9. **Misleading verification claims:** README now distinguishes working CRUD endpoints from the
   manual decimal endpoint, documents upload/build restrictions and updates test counts. Explicit
   `AI=true` smoke testing now fails when no AI translation survives compilation.

## Remaining limits and work for a real migration

- **The sample is not fully migrated without human work.** `TotalCatalogueValue` and `StreamInStock`
  remain intentional stubs with AI off. Runtime testing confirmed `/api/books/value` returns 500.
  The README previously overstated this as every migrated endpoint working; it now states the limit.
- **API/data equivalence is not established by compilation.** Enum numeric values are not preserved;
  enum JSON names, lazy association exclusion, synchronous replacements for async, authentication
  configuration and EF fluent configuration need review. Findings expose these changes; this audit
  does not claim arbitrary .NET projects are behaviorally equivalent after migration.
- **Generated database provisioning is manual.** Runtime checks explicitly created a disposable
  schema and seeded a fictional author. Production schema/data migration is not generated by Portway.
- **A public multi-user service needs further product work.** The current API has no authentication
  or ownership model for uploaded source, edits, downloads and deletion. Jobs execute in a local
  bounded executor; there is no durable worker recovery for an interrupted RUNNING job. These are
  deployment gaps, not failures of the documented local migration workflow.
- **Live AI and cloud release were not tested.** Provider clients and AI orchestration passed mocked
  tests. No paid call, model download, GHCR publish or Azure deployment was performed.
- **Visual browser verification was unavailable.** The computer-use environment exposed no browser.
  Frontend tests/build and packaged HTTP workflow passed, but this is not a visual/accessibility or
  browser end-to-end certification.

The fixed local workflow is verified. Treat Portway as a review-assisted migration tool, and use
project-specific contract tests and manual remediation before replacing a production .NET service.
