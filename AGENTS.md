## Repository Layout
- `src/`: Core source code
  - `src/main/`: The core logic of the application
    - `src/main/mobile/`: Mobile app code
    - `src/main/frontend/inference_worker/`: Code running in a webworker for text-embedding and vector-search
    - `src/main/frontend/worker/`: Code running in an another webworker
        - `src/main/frontend/worker/rtc/`: RTC(Real Time Collaboration) related code
    - `src/main/frontend/components/`: UI components
  - `src/electron/`: Code specifically for the Electron desktop application.
  - `src/test/`: unit-tests
- `deps/`: Internal dependencies/modules
- `clj-e2e/`: End to end test code

## Common used cljs keywords
- All commonly used ClojureScript keywords are defined using `logseq.common.defkeywords/defkeyword`.
- Search for `defkeywords` to find all the definitions.

## Testing Commands
- Local smoke test (verify everything before merge): `npm run verify`
- Run linters and unit-tests: `bb dev:lint-and-test`
- Run single focused unit-test:
  - Add the `:focus` keyword to the test case: `(deftest ^:focus test-name ...)`
  - `bb dev:test -i focus`
- E2E tests files are located in `/clj-e2e`

## Common used cljs keywords
- All commonly used ClojureScript keywords are defined using `logseq.common.defkeywords/defkeyword`.
- Search for `defkeywords` to find all the definitions.

## Working Tree Hygiene

Clean up after yourself: kill any background processes you spawned (servers, watchers) and leave the checkout on the branch and state you found it in, unless the task explicitly requires otherwise.

## Flaky Tests

Treat every flaky test as an app bug by default. Dig until the failure mechanism is proven (reproduction, logs, or code proof) — in the app code first. Test-side mitigations such as raising timeouts, blind retries, or skipping are not fixes; they suppress the signal and hide real product bugs. Only touch the test harness when the mechanism is proven to live there.

## Code Guidance
- Keep in mind: @prompts/review.md

## Review Checklist
- Linters and unit-tests must pass
- Check the review notes listed in `prompts/review.md`.

