(ns frontend.handler.events.ui
  "UI events"
  (:require [frontend.components.cmdk.core :as cmdk]
            [frontend.components.page :as component-page]
            [frontend.components.plugins :as plugin]
            [frontend.components.quick-add :as quick-add]
            [frontend.components.select :as select]
            [frontend.components.selection :as selection]
            [frontend.components.settings :as settings]
            [frontend.components.shell :as shell]
            [frontend.components.whiteboard :as whiteboard]
            [frontend.context.i18n :refer [t]]
            [frontend.extensions.srs :as srs]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.events :as events]
            [frontend.handler.file-based.native-fs :as nfs-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [goog.dom :as gdom]
            [logseq.shui.ui :as shui]))

(defmethod events/handle :go/search [_]
  (when-not (editor-handler/dialog-exists? :ls-dialog-cmdk)
    (shui/dialog-open!
     cmdk/cmdk-modal
     {:id :ls-dialog-cmdk
      :align :top
      :content-props {:class "ls-dialog-cmdk"}
      :close-btn? false
      :onEscapeKeyDown (fn [e] (.preventDefault e))})))

(defmethod events/handle :command/run [_]
  (when (util/electron?)
    (shui/dialog-open! shell/shell)))

(defmethod events/handle :notification/show [[_ {:keys [content status clear?]}]]
  (notification/show! content status clear?))

(defmethod events/handle :command/run [_]
  (when (util/electron?)
    (shui/dialog-open! shell/shell)))

(defmethod events/handle :go/plugins [_]
  (plugin/open-plugins-modal!))

(defmethod events/handle :go/plugins-waiting-lists [_]
  (plugin/open-waiting-updates-modal!))

(defmethod events/handle :go/plugins-from-file [[_ plugins]]
  (plugin/open-plugins-from-file-modal! plugins))

(defmethod events/handle :go/install-plugin-from-github [[_]]
  (shui/dialog-open!
   (plugin/install-from-github-release-container)))

(defmethod events/handle :go/plugins-settings [[_ pid nav? title]]
  (when pid
    (state/set-state! :plugin/focused-settings pid)
    (state/set-state! :plugin/navs-settings? (not (false? nav?)))
    (plugin/open-focused-settings-modal! title)))

(defmethod events/handle :go/proxy-settings [[_ agent-opts]]
  (shui/dialog-open!
   (plugin/user-proxy-settings-container agent-opts)
   {:id :https-proxy-panel :center? true :class "lg:max-w-2xl"}))

(defmethod events/handle :redirect-to-home [_]
  (page-handler/create-today-journal!)
  (when (util/capacitor?)
    (state/pub-event! [:mobile/set-tab "home"])))

(defmethod events/handle :page/show-delete-dialog [[_ selected-rows ok-handler]]
  (shui/dialog-open!
   (component-page/batch-delete-dialog selected-rows ok-handler)))

(defmethod events/handle :modal/show-cards [[_ _cards-id]]
  (shui/dialog-open!
   srs/global-cards
   {:id :srs
    :label "flashcards__cp"}))

(defmethod events/handle :modal/show-themes-modal [[_ classic?]]
  (if classic?
    (plugin/open-select-theme!)
    (route-handler/go-to-search! :themes)))

(defmethod events/handle :ui/toggle-appearance [_]
  (let [popup-id "appearance_settings"]
    (if (gdom/getElement popup-id)
      (shui/popup-hide! popup-id)
      (shui/popup-show!
       (js/document.querySelector ".toolbar-dots-btn")
       (fn []
         (settings/appearance))
       {:id popup-id
        :align :end}))))

(defmethod events/handle :plugin/consume-updates [[_ id prev-pending? updated?]]
  (let [downloading?   (:plugin/updates-downloading? @state/state)
        auto-checking? (plugin-handler/get-auto-checking?)]
    (when-let [coming (and (not downloading?)
                           (get-in @state/state [:plugin/updates-coming id]))]
      (let [error-code (:error-code coming)
            error-code (if (= error-code (str :no-new-version)) nil error-code)
            title      (:title coming)]
        (when (and prev-pending? (not auto-checking?))
          (if-not error-code
            (plugin/set-updates-sub-content! (str title "...") 0)
            (notification/show!
             (str "[Checked]<" title "> " error-code) :error)))))

    (if (and updated? downloading?)
      ;; try to start consume downloading item
      (if-let [next-coming (state/get-next-selected-coming-update)]
        (plugin-handler/check-or-update-marketplace-plugin!
         (assoc next-coming :only-check false :error-code nil)
         (fn [^js e] (js/console.error "[Download Err]" next-coming e)))
        (plugin-handler/close-updates-downloading))

      ;; try to start consume pending item
      (if-let [next-pending (second (first (:plugin/updates-pending @state/state)))]
        (do
          (println "Updates: take next pending - " (:id next-pending))
          (js/setTimeout
           #(plugin-handler/check-or-update-marketplace-plugin!
             (assoc next-pending :only-check true :auto-check auto-checking? :error-code nil)
             (fn [^js e]
               (notification/show! (.toString e) :error)
               (js/console.error "[Check Err]" next-pending e))) 500))

        ;; try to open waiting updates list
        (do (when (and prev-pending? (not auto-checking?)
                       (seq (state/all-available-coming-updates)))
              (plugin/open-waiting-updates-modal!))
            (plugin-handler/set-auto-checking! false))))))

(defmethod events/handle :plugin/loader-perf-tip [[_ {:keys [^js o _s _e]}]]
  (when-let [opts (.-options o)]
    (notification/show!
     (plugin/perf-tip-content (.-id o) (.-name opts) (.-url opts))
     :warning false (.-id o))))

(defn- refresh-cb []
  (page-handler/create-today-journal!))

(defmethod events/handle :graph/ask-for-re-fresh [_]
  (shui/dialog-open!
   [:div {:style {:max-width 700}}
    [:p (t :sync-from-local-changes-detected)]
    [:div.flex.justify-end
     (ui/button
      (t :yes)
      :autoFocus "on"
      :class "ui__modal-enter"
      :on-click (fn []
                  (shui/dialog-close!)
                  (nfs-handler/refresh! (state/get-current-repo) refresh-cb)))]]))

(defmethod events/handle :dialog-select/graph-open []
  (select/dialog-select! :graph-open))

(defmethod events/handle :dialog-select/graph-remove []
  (select/dialog-select! :graph-remove))

(defmethod events/handle :dialog-select/db-graph-replace []
  (select/dialog-select! :db-graph-replace))

(defn- hide-action-bar!
  []
  (when (editor-handler/popup-exists? :selection-action-bar)
    (shui/popup-hide! :selection-action-bar)))

(defmethod events/handle :editor/show-action-bar []
  (let [selection (state/get-selection-blocks)
        first-visible-block (some #(when (util/el-visible-in-viewport? % true) %) selection)]
    (when first-visible-block
      (hide-action-bar!)
      (shui/popup-show!
       first-visible-block
       (fn []
         (selection/action-bar))
       {:id :selection-action-bar
        :root-props {:modal false}
        :content-props {:side "top"
                        :class "!py-0 !px-0 !border-none"
                        :modal? false}
        :auto-side? false
        :align :start}))))

(defmethod events/handle :editor/hide-action-bar []
  (hide-action-bar!)
  (state/set-state! :mobile/show-action-bar? false))

(defmethod events/handle :user/logout [[_]]
  ;; no accounts in the minimal build — logout is a no-op
  nil)

(defmethod events/handle :user/login [[_ _host-ui?]]
  ;; no accounts in the minimal build — login is a no-op
  nil)

(defmethod events/handle :whiteboard/onboarding [[_ opts]]
  (shui/dialog-open!
   (fn [{:keys [close]}] (whiteboard/onboarding-welcome close))
   (merge {:close-btn?      false
           :center?         true
           :close-backdrop? false} opts)))

(defmethod events/handle :user/fetch-info-and-graphs [[_]]
  ;; no accounts or remote graphs in the minimal build
  (state/set-state! [:ui/loading? :login] false))

(defmethod events/handle :dialog/show-block [[_ block option]]
  (shui/dialog-open!
   [:div.p-8.w-full.h-full
    (component-page/page-container block option)]
   {:id :ls-dialog-block
    :align :top
    :content-props {:class "ls-dialog-block"}
    :onEscapeKeyDown (fn [e] (.preventDefault e))}))

(defmethod events/handle :dialog/quick-add [_]
  (shui/dialog-open!
   [:div.w-full.h-full
    (quick-add/quick-add)]
   {:id :ls-dialog-quick-add
    :align :top
    :content-props {:class "ls-dialog-quick-add"}
    :onEscapeKeyDown (fn [e] (.preventDefault e))}))
