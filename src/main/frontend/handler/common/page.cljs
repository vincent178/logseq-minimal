(ns frontend.handler.common.page
  "Common fns for file and db based page handlers, including create!, delete!
  and favorite fns. This ns should be agnostic of file or db concerns but there
  is still some file-specific tech debt to remove from create!"
  (:require             [clojure.string :as string]
            [dommy.core :as dom]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.fs :as fs]
            [frontend.handler.config :as config-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.modules.outliner.op :as outliner-op]
            [frontend.modules.outliner.ui :as ui-outliner-tx]
            [frontend.state :as state]
            [logseq.common.util :as common-util]
            [promesa.core :as p]))

(defn <create!
  ([title]
   (<create! title {}))
  ([title {:keys [redirect? today-journal?]
           :or   {redirect? true}
           :as options}]
   (when (string? title)
     (p/let [_repo (state/get-current-repo)
             title' title]
       (cond
         :else
         (when-not (string/blank? title')
           (p/let [options' options
                   [_page-name page-uuid] (ui-outliner-tx/transact!
                                           {:outliner-op :create-page}
                                           (outliner-op/create-page! title' options'))
                   page (db/get-page (or page-uuid title'))]
             (when redirect?
               (route-handler/redirect-to-page! page-uuid)
               (when-not today-journal?
                 ;; After redirecting to the new page, click its block-add-button
                 ;; to open the editor. The page (and thus the button) is rendered
                 ;; asynchronously by the route, and under load this can take longer
                 ;; than a single fixed delay. A one-shot timeout loses the click and
                 ;; leaves the page blank with no editor, so poll (bounded) until the
                 ;; button appears before clicking.
                 (let [page-id (str (:db/id page))
                       find-button #(->> (dom/sel ".block-add-button")
                                         (filter (fn [b] (= page-id (dom/attr b "parentblockid"))))
                                         first)
                       attempts 50                       ; 50 * 100ms = 5s max
                       poll (fn poll [n]
                              (when (pos? n)
                                (if-let [block-add-button (find-button)]
                                  (.click block-add-button)
                                  (js/setTimeout (fn [] (poll (dec n))) 100))))]
                   (js/setTimeout (fn [] (poll attempts)) 100))))
             page)))))))

;; favorite fns
;; ============
(defn file-favorited?
  [page-name-or-uuid]
  (let [page-uuid (:block/uuid (db/get-page page-name-or-uuid))
        favorites (->> (:favorites (state/get-config))
                       (filter string?)
                       (map string/lower-case)
                       (set))]
    (or (contains? favorites (string/lower-case page-name-or-uuid))
        (and page-uuid
             (contains? favorites (string/lower-case (str page-uuid)))))))

(defn file-favorite-page!
  "Favorites a page, storing its uuid string in the `:favorites` config list
  so order survives page renames."
  [page-name]
  (when-not (string/blank? page-name)
    (let [favorite (or (some-> (:block/uuid (db/get-page page-name)) str)
                       page-name)
          favorites (->
                     (cons
                      favorite
                      (or (:favorites (state/get-config)) []))
                     (distinct)
                     (vec))]
      (config-handler/set-config! :favorites favorites))))

(defn file-unfavorite-page!
  "Removes a page from favorites, matching either its uuid string (current
  format) or its legacy page-name entry."
  [page-name]
  (when-not (string/blank? page-name)
    (let [page-uuid (:block/uuid (db/get-page page-name))
          old-favorites (:favorites (state/get-config))
          new-favorites (->> old-favorites
                             (remove #(or (= (string/lower-case %) (string/lower-case page-name))
                                          (and page-uuid
                                               (= (string/lower-case %)
                                                  (string/lower-case (str page-uuid))))))
                             (vec))]
      (when-not (= old-favorites new-favorites)
        (config-handler/set-config! :favorites new-favorites)))))

;; favorites fns end ================

(defn <delete!
  "Deletes a page. If delete is successful calls ok-handler. Otherwise calls error-handler
   if given. Note that error-handler is being called in addition to error messages that worker
   already provides"
  [page-uuid-or-name ok-handler & {:keys [error-handler]}]
  (when page-uuid-or-name
    (assert (or (uuid? page-uuid-or-name) (string? page-uuid-or-name)))
    (when-let [page-uuid (or (and (uuid? page-uuid-or-name) page-uuid-or-name)
                             (:block/uuid (db/get-page page-uuid-or-name)))]
      (when @state/*db-worker
        (let [page (db/entity [:block/uuid page-uuid])
              default-home (state/get-default-home)
              home-page? (= (:block/title page) (:page default-home))]
          (p/do!
           (when home-page?
             (p/do!
              (config-handler/set-config! :default-home (dissoc default-home :page))
              (config-handler/set-config! :feature/enable-journals? true)
              (notification/show! "Journals enabled" :success)))
           (-> (p/let [res (ui-outliner-tx/transact!
                            {:outliner-op :delete-page}
                            (outliner-op/delete-page! page-uuid))]
                 (if res
                   (when ok-handler (ok-handler))
                   (when error-handler (error-handler))))
               (p/catch (fn [error]
                          (js/console.error error))))))))))

;; other fns
;; =========

(defn after-page-deleted!
  [repo page-name file-path tx-meta]
  (let [repo-dir (config/get-repo-dir repo)]
      ;; TODO: move favorite && unfavorite to worker too
    (file-unfavorite-page! page-name)

    (when (and (not= :rename-page (:real-outliner-op tx-meta))
               (= (some-> (state/get-current-page) common-util/page-name-sanity-lc)
                  (common-util/page-name-sanity-lc page-name)))
      (route-handler/redirect-to-home!))

    ;; TODO: why need this?
    (ui-handler/re-render-root!)

    (when file-path
      (-> (p/let [exists? (fs/file-exists? repo-dir file-path)]
            (when exists? (fs/unlink! repo (config/get-repo-fpath repo file-path) nil)))
          (p/catch (fn [error] (js/console.error error)))))))

(defn rename-file!
  "emit file-rename events to :file/rename-event-chan
   force-fs? - when true, rename file event the db transact is failed."
  [old-path new-path]
  (let [repo (state/get-current-repo)]
    (->
     (p/let [_ (state/offer-file-rename-event-chan! {:repo repo
                                                     :old-path old-path
                                                     :new-path new-path})]
       (fs/rename! repo old-path new-path))
     (p/catch (fn [error]
                (println "file rename failed: " error))))))

(defn after-page-renamed!
  [repo {:keys [page-id old-name new-name old-path new-path]}]
  (let [old-page-name       (common-util/page-name-sanity-lc old-name)
        redirect? (= (some-> (state/get-current-page) common-util/page-name-sanity-lc)
                     (common-util/page-name-sanity-lc old-page-name))
        page (db/entity repo page-id)]

    ;; Redirect to the newly renamed page
    (when redirect?
      (route-handler/redirect! {:to          :page
                                :push        false
                                :path-params {:name (str (:block/uuid page))}}))


    (let [home (get (state/get-config) :default-home {})]
      (when (= old-page-name (common-util/page-name-sanity-lc (get home :page "")))
        (config-handler/set-config! :default-home (assoc home :page new-name))))

    (when (and old-path new-path)
      (rename-file! old-path new-path))

    (ui-handler/re-render-root!)))
