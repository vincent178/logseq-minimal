(ns frontend.components.repo
  (:require            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.handler.file-based.native-fs :as nfs-handler]
            [frontend.handler.graph :as graph]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.route :as route-handler]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.util.text :as text-util]
            [goog.object :as gobj]
            [logseq.shui.ui :as shui]
            [medley.core :as medley]
            [promesa.core :as p]
            [rum.core :as rum]))

(rum/defc normalized-graph-label
  [{:keys [url remote? graph-e2ee? GraphName] :as graph} on-click]
  (when graph
    [:span.flex.items-center
     (if (config/local-file-based-graph? url)
       (let [local-dir (config/get-local-dir url)
             graph-name (text-util/get-graph-name-from-path url)]
         [:a.flex.items-center {:title local-dir
                                :on-click #(on-click graph)}
          [:span graph-name (when GraphName [:strong.pl-1 "(" GraphName ")"])]
          (when remote? [:strong.px-1.flex.items-center (ui/icon (if graph-e2ee? "lock" "cloud"))])])
       [:a.flex.items-center {:title url
                              :on-click #(on-click graph)}
        (db/get-repo-path (or url GraphName))
        (when remote? [:strong.pl-1.flex.items-center (ui/icon "cloud")])])]))

(defn sort-repos-with-metadata-local
  [repos]
  (if-let [m (and (seq repos) (graph/get-metadata-local))]
    (->> repos
         (map (fn [r] (merge r (get m (:url r)))))
         (sort (fn [r1 r2]
                 (compare (or (:last-seen-at r2) (:created-at r2))
                          (or (:last-seen-at r1) (:created-at r1))))))
    repos))

(defn- safe-locale-date
  [dst]
  (when (number? dst)
    (try
      (.toLocaleString (js/Date. dst))
      (catch js/Error _e nil))))

(defn- delete-local-graph!
  "Permanently delete (db graph) or unlink (file graph) a local graph."
  [repo url _graph-name]
  (let [prompt-str (str "Are you sure you want to unlink the graph \"" url "\" from local folder?")]
    (-> (shui/dialog-confirm!
         [:p.font-medium.-my-4 prompt-str
          [:span.my-2.flex.font-normal.opacity-75
           [:small "It won't remove your local files!"]]])
        (p/then (fn []
                  (repo-handler/remove-repo! repo)
                  (state/pub-event! [:graph/unlinked repo (state/get-current-repo)]))))))

(rum/defc repos-inner
  "Graph list in `All graphs` page. Minimal build: local graphs only."
  [repos]
  (for [{:keys [root url GraphUUID GraphName created-at last-seen-at] :as repo}
        (sort-repos-with-metadata-local repos)
        :let [graph-name GraphName]]
    [:div.flex.justify-between.mb-2.items-center.group
     {:key (or url GraphUUID) "data-testid" url}
     [:div
      [:span.flex.items-center.gap-1
       (normalized-graph-label
        repo
        (fn []
          ;; all graphs are local in the minimal build
          (when root
            (state/pub-event! [:graph/switch url]))))]
      (when-let [time (some-> (or last-seen-at created-at) (safe-locale-date))]
        [:small.text-muted-foreground (str "Last opened at: " time)])]

     [:div.controls
      [:div.flex.flex-row.items-center
       (when (util/electron?)
         [:a.text-xs.items-center.text-gray-08.hover:underline.hidden.group-hover:flex
          {:on-click #(util/open-url (str "file://" root))}
          (shui/tabler-icon "folder-pin") [:span.pl-1 root]])

       (shui/dropdown-menu
        (shui/dropdown-menu-trigger
         {:asChild true}
         (shui/button
          {:variant "ghost"
           :class "graph-action-btn !px-1"
           :size :sm}
          (ui/icon "dots" {:size 15})))
        (shui/dropdown-menu-content
         {:align "end"}
         (when root
           (shui/dropdown-menu-item
            {:key "delete-locally"
             :class "delete-local-graph-menu-item"
             :on-click #(delete-local-graph! repo url graph-name)}
            "Delete local graph"))))]]]))

(rum/defc repos-cp < rum/reactive
  []
  (let [repos (state/sub [:me :repos])
        repos (util/distinct-by :url repos)
        repos (remove #(= (:url %) config/demo-repo) repos)
        ;; minimal build: no remote graphs, everything is local
        local-graphs (remove :remote? repos)]
    [:div#graphs
     (when-not (util/capacitor?)
       [:h1.title (t :graph/all-graphs)])

     [:div.pl-1.content
      {:class (when-not (util/mobile?) "mt-8")}
      [:div
       [:h2.text-lg.font-medium.mb-4 (t :graph/local-graphs)]
       (when (seq local-graphs)
         (repos-inner local-graphs))]]]))

(defn- repos-dropdown-links [repos current-repo & {:as opts}]
  (let [switch-repos (if-not (nil? current-repo)
                       (remove (fn [repo] (= current-repo (:url repo))) repos) repos) ; exclude current repo
        repo-links (mapv
                    (fn [{:keys [url GraphName] :as _graph}]
                      (let [local? (config/local-file-based-graph? url)
                            repo-url (if local?
                                       (db/get-repo-name url)
                                       GraphName)
                            short-repo-name (if local?
                                              (text-util/get-graph-name-from-path repo-url)
                                              GraphName)]
                        (when short-repo-name
                          {:title [:span.flex.items-center.title-wrap short-repo-name]
                           :hover-detail repo-url ;; show full path on hover
                           :options {:on-click
                                     (fn [e]
                                       (when-let [on-click (:on-click opts)]
                                         (on-click e))
                                       (if (gobj/get e "shiftKey")
                                         (state/pub-event! [:graph/open-new-window url])
                                         ;; all graphs are local in the minimal build
                                         (state/pub-event! [:graph/switch url])))}})))
                    switch-repos)]
    (->> repo-links (remove nil?))))

(defn- repos-footer [multiple-windows?]
  [:div.cp__repos-quick-actions
   {:on-click #(shui/popup-hide!)}

   (when (not (config/demo-graph?))
     [:<>
      (shui/button {:size :sm :variant :ghost
                    :title (t :sync-from-local-files-detail)
                    :on-click (fn []
                                (state/pub-event! [:graph/ask-for-re-fresh]))}
                   (shui/tabler-icon "file-report") [:span (t :sync-from-local-files)])

      (shui/button {:size :sm :variant :ghost
                    :title (t :re-index-detail)
                    :on-click (fn []
                                (state/pub-event! [:graph/ask-for-re-index multiple-windows? nil]))}
                   (shui/tabler-icon "folder-bolt") [:span (t :re-index)])])

   (when (util/electron?)
     (shui/button {:size :sm :variant :ghost
                   :on-click (fn []
                               (if (or (nfs-handler/supported?) (mobile-util/native-platform?))
                                 (state/pub-event! [:graph/setup-a-repo])
                                 (route-handler/redirect-to-all-graphs)))}
                  (shui/tabler-icon "folder-plus")
                  [:span (t :new-graph)]))

   (shui/button
    {:size :sm :variant :ghost
     :on-click (fn [] (route-handler/redirect! {:to :import}))}
    (shui/tabler-icon "database-import")
    [:span (t :import-notes)])

   (shui/button {:size :sm :variant :ghost
                 :on-click (fn []
                             (if (util/mobile?)
                               (state/pub-event! [:mobile/set-tab "graphs"])
                               (route-handler/redirect-to-all-graphs)))}
                (shui/tabler-icon "layout-2") [:span (t :all-graphs)])])

(rum/defcs repos-dropdown-content < rum/reactive
  [_state & {:keys [contentid footer?] :as opts
             :or {footer? true}}]
  (let [multiple-windows? false
        current-repo (state/sub :git/current-repo)
        repos (state/sub [:me :repos])
        repos (sort-repos-with-metadata-local repos)
        repos (distinct repos)
        items-fn #(repos-dropdown-links repos current-repo opts)
        header-fn #(when (> (count repos) 1) ; show switch to if there are multiple repos
                     [:div.font-medium.md:text-sm.md:opacity-50.p-2.flex.flex-row.justify-between.items-center
                      [:h4.pb-1 (t :left-side-bar/switch)]])
        _repo-name (when current-repo (db/get-repo-name current-repo))]

    [:div
     {:class (when (<= (count repos) 1) "no-repos")}
     (header-fn)
     [:div.cp__repos-list-wrap
      (for [{:keys [hr item hover-detail title options icon]} (items-fn)]
        (let [on-click' (:on-click options)
              href' (:href options)
              menu-item (if (util/mobile?) ui/menu-link shui/dropdown-menu-item)]
          (if hr
            (if (util/mobile?) [:hr.py-2] (shui/dropdown-menu-separator))
            (menu-item
             (assoc options
                    :title hover-detail
                    :on-click (fn [^js e]
                                (when on-click'
                                  (when-not (false? (on-click' e))
                                    (shui/popup-hide! contentid)))))
             (or item
                 (if href'
                   [:a.flex.items-center.w-full
                    {:href href' :on-click #(shui/popup-hide! contentid)
                     :style {:color "inherit"}} title]
                   [:span.flex.items-center.gap-1.w-full
                    icon [:div title]]))))))]
     (when footer?
       (repos-footer multiple-windows?))]))

(rum/defcs graphs-selector < rum/reactive
  [_state]
  (let [current-repo (state/get-current-repo)
        user-repos (state/get-repos)
        current-repo' (some->> user-repos (medley/find-first #(= current-repo (:url %))))
        repo-name (when current-repo (db/get-repo-name current-repo))
        remote? (:remote? current-repo')
        short-repo-name (if current-repo
                          (db/get-short-repo-name repo-name)
                          "Select a Graph")]
    [:div.cp__graphs-selector.flex.items-center.justify-between
     [:a.item.flex.items-center.gap-1.select-none
      {:title current-repo
       :on-click (fn [^js e]
                   (shui/popup-show! (.closest (.-target e) "a")
                                     (fn [{:keys [id]}] (repos-dropdown-content {:contentid id}))
                                     {:as-dropdown? true
                                      :content-props {:class "repos-list"}
                                      :align :start}))}
      [:span.thumb (shui/tabler-icon (if remote? "cloud" "folder") {:size 16})]
      [:strong short-repo-name]
      (shui/tabler-icon "selector" {:size 18})]]))

;; DB-graph creation UI removed (minimal build is file-graphs only).
