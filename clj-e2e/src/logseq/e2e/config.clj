(ns logseq.e2e.config)

(defonce *port (atom 3002))
(defonce *headless (atom true))
(defonce *slow-mo (atom 100))                  ; Set `slow-mo` lower to find more flaky tests
;; Playwright's default locator timeout is 5000ms, which is too tight for SPA
;; boot / page-title render on cold or CPU-contended CI runners and was the
;; root cause of the flaky `[data-testid="page title"]` failures. Raise the
;; page-wide default so every wait/assert inherits a realistic budget.
(defonce *default-timeout (atom 30000))
