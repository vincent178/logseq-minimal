(ns frontend.components.imports
  "Import data into a file-based graph. (DB-graph import removed.)"
  (:require [clojure.string :as string]
            [frontend.components.onboarding.setups :as setups]
            [frontend.components.svg :as svg]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.file-based.import :as file-import-handler]
            [frontend.handler.import :as import-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [goog.object :as gobj]
            [logseq.shui.dialog.core :as shui-dialog]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [rum.core :as rum]))

;; Can't name this component as `frontend.components.import` since shadow-cljs
;; will complain about it.

(defonce *opml-imported-pages (atom nil))

(defn- finished-cb
  []
  (notification/show! "Import finished!" :success)
  (shui/dialog-close! :import-indicator)
  (route-handler/redirect-to-home!)
  (if util/web-platform?
    (js/window.location.reload)
    (js/setTimeout ui-handler/re-render-root! 500)))

(defn- roam-import-handler
  [e]
  (let [file      (first (array-seq (.-files (.-target e))))
        file-name (gobj/get file "name")]
    (if (string/ends-with? file-name ".json")
      (do
        (state/set-state! :graph/importing :roam-json)
        (let [reader (js/FileReader.)]
          (set! (.-onload reader)
                (fn [e]
                  (let [text (.. e -target -result)]
                    (file-import-handler/import-from-roam-json!
                     text
                     #(do
                        (state/set-state! :graph/importing nil)
                        (finished-cb))))))
          (.readAsText reader file)))
      (notification/show! "Please choose a JSON file."
                          :error))))

(defn- lsq-import-handler
  "Import a plain-text graph from an EDN/JSON export. DB-graph imports
   (SQLite / debug-transit / DB-EDN) removed with DB graphs."
  [e]
  (let [file      (first (array-seq (.-files (.-target e))))
        file-name (some-> (gobj/get file "name")
                          (string/lower-case))
        edn? (string/ends-with? file-name ".edn")
        json? (string/ends-with? file-name ".json")]
    (cond
      (or edn? json?)
      (do
        (state/set-state! :graph/importing :logseq)
        (let [reader (js/FileReader.)
              import-f (if edn?
                         import-handler/import-from-edn!
                         import-handler/import-from-json!)]
          (set! (.-onload reader)
                (fn [e]
                  (let [text (.. e -target -result)]
                    (import-f
                     text
                     #(do
                        (state/set-state! :graph/importing nil)
                        (finished-cb))))))
          (.readAsText reader file)))

      :else
      (notification/show! "Please choose an EDN or a JSON file."
                          :error))))

(defn- opml-import-handler
  [e]
  (let [file      (first (array-seq (.-files (.-target e))))
        file-name (gobj/get file "name")]
    (if (string/ends-with? file-name ".opml")
      (do
        (state/set-state! :graph/importing :opml)
        (let [reader (js/FileReader.)]
          (set! (.-onload reader)
                (fn [e]
                  (let [text (.. e -target -result)]
                    (import-handler/import-from-opml! text
                                                      (fn [pages]
                                                        (reset! *opml-imported-pages pages)
                                                        (state/set-state! :graph/importing nil)
                                                        (finished-cb))))))
          (.readAsText reader file)))
      (notification/show! "Please choose a OPML file."
                          :error))))

(rum/defc indicator-progress < rum/reactive
  []
  (let [{:keys [total current-idx current-page]} (state/sub :graph/importing-state)
        left-label (if (and current-idx total (= current-idx total))
                     [:div.flex.flex-row.font-bold "Loading ..."]
                     [:div.flex.flex-row.font-bold
                      (t :importing)
                      [:div.hidden.md:flex.flex-row
                       [:span.mr-1 ": "]
                       [:div.text-ellipsis-wrapper {:style {:max-width 300}}
                        current-page]]])
        width (js/Math.round (* (.toFixed (/ current-idx total) 2) 100))
        process (when (and total current-idx)
                  (str current-idx "/" total))]
    [:div.p-5
     (ui/progress-bar-with-label width left-label process)]))

(rum/defc import-indicator
  [importing?]
  (hooks/use-effect!
   (fn []
     (when (and importing? (not (shui-dialog/get-modal :import-indicator)))
       (shui/dialog-open! indicator-progress
                          {:id :import-indicator
                           :content-props
                           {:onPointerDownOutside #(.preventDefault %)
                            :onOpenAutoFocus #(.preventDefault %)}})))
   [importing?])
  [:<>])

(rum/defc ^:large-vars/cleanup-todo importer < rum/reactive
  [{:keys [query-params]}]
  (let [support-file-based? (config/local-file-based-graph? (state/get-current-repo))
        importing? (state/sub :graph/importing)]
    [:<>
     (import-indicator importing?)
     (when-not importing?
       (setups/setups-container
        :importer
        [:article.flex.flex-col.items-center.importer.py-16.px-8
         (when-not (util/mobile?)
           [:section.c.text-center
            [:h1 (t :on-boarding/importing-title)]
            [:h2 (t :on-boarding/importing-desc)]])
         [:section.d.md:flex.flex-col
          ;; DB-graph imports (SQLite / File-to-DB / Debug Transit / EDN-to-DB)
          ;; removed: the minimal build is file-based only.

          (when (and (util/electron?) support-file-based?)
            [:label.action-input.flex.items-center.mx-2.my-2
             [:span.as-flex-center [:i (svg/logo 28)]]
             [:span.flex.flex-col
              [[:strong "EDN / JSON to plain text graph"]
               [:small (t :on-boarding/importing-lsq-desc)]]]
             [:input.absolute.hidden
              {:id "import-lsq"
               :type "file"
               :on-change lsq-import-handler}]])

          (when (and (util/electron?) support-file-based?)
            [:label.action-input.flex.items-center.mx-2.my-2
             [:span.as-flex-center [:i (svg/roam-research 28)]]
             [:div.flex.flex-col
              [[:strong "RoamResearch"]
               [:small (t :on-boarding/importing-roam-desc)]]]
             [:input.absolute.hidden
              {:id "import-roam"
               :type "file"
               :on-change roam-import-handler}]])

          (when (and (util/electron?) support-file-based?)
            [:label.action-input.flex.items-center.mx-2.my-2
             [:span.as-flex-center.ml-1 (ui/icon "sitemap" {:size 26})]
             [:span.flex.flex-col
              [[:strong "OPML"]
               [:small (t :on-boarding/importing-opml-desc)]]]

             [:input.absolute.hidden
              {:id "import-opml"
               :type "file"
               :on-change opml-import-handler}]])]

         (when (= "picker" (:from query-params))
           [:section.e
            [:a.button {:on-click #(route-handler/redirect-to-home!)} "Skip"]])]))]))
