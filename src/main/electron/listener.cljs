(ns electron.listener
  "System-component-like ns that defines listeners by event name to receive ipc
  messages from electron's main process"
  (:require [cljs-bean.core :as bean]
            [dommy.core :as dom]
            [electron.ipc :as ipc]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.db.file-based.model :as file-model]
            [frontend.fs.watcher-handler :as watcher-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.handler.search :as search-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [logseq.common.path :as path]
            [logseq.common.util :as common-util]
            [promesa.core :as p]))

(defn- safe-api-call
  "Force the callback result to be nil, otherwise, ipc calls could lead to
  window crash."
  [k f]
  (js/window.apis.on k (fn [data] (f data) nil)))

(defn ^:large-vars/cleanup-todo listen-to-electron!
  []
  ;; TODO: move "file-watcher" to electron.ipc.channels
  (safe-api-call "file-watcher"
                 (fn [data]
                   (let [{:keys [type payload]} (bean/->clj data)
                         path (common-util/path-normalize (:path payload))
                         dir (:dir payload)
                         payload (assoc payload :path (path/relative-path dir path))]
                     (watcher-handler/handle-changed! type payload))))

  (safe-api-call "notification"
                 (fn [data]
                   (let [{:keys [type payload]} (bean/->clj data)
                         type (keyword type)
                         comp [:div (str payload)]]
                     (notification/show! comp type false))))

  (safe-api-call "rebuildSearchIndice"
                 (fn [_data]
                   (prn "Rebuild search indices")
                   (search-handler/rebuild-indices!)))

  (safe-api-call "setGitUsernameAndEmail"
                 (fn []
                   (state/pub-event! [:modal/set-git-username-and-email])))

  (safe-api-call "setCurrentGraph"
                 (fn []
                   (when-let [graph (state/get-current-repo)]
                     (ipc/ipc :setCurrentGraph graph))))

  (safe-api-call "redirect"
                 (fn [data]
                   (let [{:keys [payload]} (bean/->clj data)
                         payload (update payload :to keyword)]
                     (route-handler/redirect! payload))))

  (safe-api-call "redirectWhenExists"
                 ;;  Redirect to the given page or block when the provided page or block exists.
                 ;;  Either :page-name or :block-id is required.
                 ;;  :page-name : the title of the page.
                 ;;  :block-id : uuid.
                 (fn [data]
                   (let [{:keys [page-name block-id file]} (bean/->clj data)]
                     (cond
                       page-name
                       (when (db/get-page page-name)
                         (route-handler/redirect-to-page! page-name {:block-id block-id}))

                       block-id
                       (p/let [block (db-async/<get-block (state/get-current-repo) block-id {:children? false})]
                         (if block
                           (route-handler/redirect-to-page! block-id)
                           (notification/show! (str "Open link failed. Block-id `" block-id "` doesn't exist in the graph.") :error false)))

                       file
                       (if-let [db-page-name (file-model/get-file-page file false)]
                         (route-handler/redirect-to-page! db-page-name)
                         (notification/show! (str "Open link failed. File `" file "` doesn't exist in the graph.") :error false))))))

  (safe-api-call "foundInPage"
                 (fn [data]
                   (let [data' (bean/->clj data)]
                     (state/set-state! [:ui/find-in-page :matches] data')
                     (dom/remove-style! (dom/by-id "search-in-page-input") :visibility)
                     (dom/set-text! (dom/by-id "search-in-page-placeholder") "")
                     (ui/focus-element "search-in-page-input"))))

  (safe-api-call "quickCapture"
                 (fn [args]
                   (state/pub-event! [:editor/quick-capture args])))

  (safe-api-call "openNewWindowOfGraph"
                 ;; Handle open new window in renderer, until the destination graph doesn't rely on setting local storage
                 ;; No db cache persisting ensured. Should be handled by the caller
                 (fn [repo]
                   (ui-handler/open-new-window-or-tab! repo))))

(defn listen!
  []
  (listen-to-electron!))
