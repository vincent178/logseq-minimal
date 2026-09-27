(ns logseq.e2e.settings
  (:require [logseq.e2e.assert :as assert]
            [logseq.e2e.keyboard :as k]
            [wally.main :as w])
  (:import (com.microsoft.playwright TimeoutError)))

(defn developer-mode
  ;; This runs before the graph has finished loading, so the header/settings
  ;; dropdown can still re-render mid-click and detach the target element.
  ;; Retry once on a stale-element timeout to ride out the initialization.
  [& {:keys [in-retry?]}]
  (let [f (fn []
            (w/click "button[title='More'] .ls-icon-dots")
            (w/click ".ls-icon-settings")
            (w/click "[data-id='advanced']")
            (let [q (.last (w/-query ".ui__toggle [aria-checked='false']"))]
              (when (.isVisible q)
                (w/click q)))
            (k/esc)
            (assert/assert-in-normal-mode?))]
    (if in-retry?
      (f)
      (try
        (f)
        (catch TimeoutError _e
          (k/esc)
          (developer-mode :in-retry? true))))))
