(ns logseq.e2e.block
  (:require [clojure.string :as string]
            [clojure.test :refer [is]]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.keyboard :as k]
            [logseq.e2e.locator :as loc]
            [logseq.e2e.util :as util]
            [wally.main :as w]
            [wally.repl :as repl]))

(defn open-last-block
  "Open the last existing block or pressing add button to create a new block"
  [& {:keys [in-retry?]}]
  (util/double-esc)
  (assert/assert-in-normal-mode?)

  (let [blocks-count (util/page-blocks-count)
        last-block (-> (if (zero? blocks-count)
                         (w/query ".ls-page-blocks .block-add-button")
                         (w/query ".ls-page-blocks .page-blocks-inner .ls-block .block-content"))
                       (last))]
    (w/click last-block)
    (if in-retry?
      (assert/assert-editor-mode)
      (try
        (assert/assert-editor-mode)
        (catch Error _e
          (open-last-block {:in-retry? true}))))))

(defn save-block
  [text]
  (assert/assert-have-count util/editor-q 1)
  (w/click util/editor-q)
  (w/fill util/editor-q text)
  (assert/assert-is-visible (loc/filter util/editor-q :has-text text)))

(defn- current-editor-id
  "DOM id (\"edit-block-<uuid>\") of the visible editor textarea, or nil when
  not in edit mode."
  []
  (when (w/visible? util/editor-q)
    (.getAttribute (w/-query util/editor-q) "id")))

(declare new-block)

(defn- recover-new-block
  "Recovery for a failed `new-block` attempt.

  Block insertion and the editor remount are separate async steps in the app:
  the outliner pipeline transacts the new block and refreshes the UI first,
  and only then schedules moving the editor to it (see
  `frontend.modules.outliner.pipeline`). So a timeout of the \"editor moved to
  a new block\" assertion does NOT prove the Enter keypress had no effect —
  the new block may already exist while the editor still shows the previous
  block. Re-pressing Enter in that state inserts a duplicate block and leaves
  an orphan empty block behind — the root cause of the flaky
  delete-end-test / delete-concat-test-3-blocks failures (CI runs 36402053455
  and 36440039553, where the page ended up with an extra empty block)."
  [title last-id blocks-count-before]
  ;; Give a lagging editor remount a short grace window to finish.
  (let [moved? (loop [i 20]
                 (let [current-id (current-editor-id)]
                   (cond
                     (and (some? current-id) (not= current-id last-id))
                     true

                     (zero? i)
                     false

                     :else
                     (do (Thread/sleep 100)
                         (recur (dec i))))))]
    (if moved?
      ;; Enter worked, only the remount/assert lagged: fill the new block.
      (save-block title)
      (if (> (util/page-blocks-count) blocks-count-before)
        ;; The insert landed but the editor never moved: a fresh empty block
        ;; sits right after the previously edited block. Adopt that block
        ;; instead of pressing Enter again (which would insert a duplicate).
        (let [uuid (subs last-id (count "edit-block-"))
              edited-block (w/-query (str "#ls-block-" uuid))
              orphan (.first (.locator edited-block
                                       "xpath=./following-sibling::*[contains(@class,'ls-block')][1]"))]
          (w/click (.first (.locator orphan ".block-content")))
          (assert/assert-editor-mode)
          (if (string/blank? (util/get-edit-content))
            (save-block title)
            (throw (ex-info "new-block recovery adopted a non-empty block"
                            {:last-id last-id
                             :current-id (current-editor-id)
                             :content (util/get-edit-content)}))))
        ;; Enter really had no effect; safe to retry from scratch.
        (new-block title true)))))

(defn new-block
  [title & [in-retry?]]
  (let [editor (util/get-editor)]
    (when-not editor (open-last-block))
    (let [last-id (current-editor-id)
          blocks-count-before (util/page-blocks-count)]
      (is (some? last-id))
      (k/press "Control+e")
      (k/enter)
      (try
        (assert/assert-is-visible
         (loc/filter ".editor-wrapper"
                     :has "textarea"
                     :has-not (str "#" last-id)))
        (assert/assert-editor-mode)
        (save-block title)
        (catch Throwable e
          (if in-retry?
            (throw (ex-info
                    "new-block exception"
                    {:current-id (current-editor-id)
                     :last-id last-id}
                    e))
            (do (prn :retry-new-block title)
                (recover-new-block title last-id blocks-count-before))))))))

;; TODO: support tree
(defn new-blocks
  [titles]
  (let [editor? (util/get-editor)]
    (when-not editor? (open-last-block))
    (let [value (util/get-edit-content)]
      (if (string/blank? value)         ; empty block
        (save-block (first titles))
        (new-block (first titles))))
    (doseq [title (rest titles)]
      (new-block title))))

(defn delete-blocks
  "Delete the current block if in editing mode, otherwise, delete all the selected blocks."
  []
  (let [editor (util/get-editor)]
    (when editor (util/exit-edit))
    (k/backspace)))

(defn assert-blocks-visible
  "blocks - coll of :block/title"
  [blocks]
  (doseq [block blocks]
    (assert/assert-is-visible (format ".ls-page-blocks .ls-block :text('%s')" block))))

(defn jump-to-block
  [block-text]
  (w/click (w/find-one-by-text ".ls-block .block-content" block-text)))

(defn wait-editor-text
  [text]
  (assert/assert-have-count util/editor-q 1)
  (w/wait-for (format ".editor-wrapper textarea:text('%s')" text)))

(def copy #(k/press "ControlOrMeta+c" {:delay 100}))
(def paste #(k/press "ControlOrMeta+v" {:delay 100}))
(def undo #(k/press "ControlOrMeta+z" {:delay 100}))
(def redo #(k/press "ControlOrMeta+y" {:delay 100}))

(defn- indent-outdent
  [indent?]
  (let [editor (util/get-editor)
        [x1 _] (util/bounding-xy editor)
        _ (if indent? (k/tab) (k/shift+tab))
        [x2 _] (util/bounding-xy editor)]
    (if indent?
      (is (< x1 x2))
      (is (> x1 x2)))))

(defn indent
  []
  (indent-outdent true))

(defn outdent
  []
  (indent-outdent false))

(defn toggle-property
  [property-title property-value]
  (k/press (if util/mac? "ControlOrMeta+p" "Control+Alt+p"))
  (w/fill ".ls-property-dialog .ls-property-input input" property-title)
  (w/wait-for (format "#ac-0.menu-link:has-text('%s')" property-title))
  (k/enter)
  (util/wait-timeout 100)
  (w/click (w/-query ".ls-property-dialog .ls-property-input input"))
  (util/wait-timeout 100)
  (util/input property-value)
  (w/wait-for (format "#ac-0.menu-link:has-text('%s')" property-value))
  (k/enter))

(defn select-blocks
  [n]
  (util/repeat-keyboard n "Shift+ArrowUp"))
