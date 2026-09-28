(ns frontend.components.dnd
  (:require ["@dnd-kit/core" :refer [DndContext closestCenter MouseSensor useSensor useSensors]]
            ["@dnd-kit/sortable" :refer [useSortable arrayMove SortableContext verticalListSortingStrategy horizontalListSortingStrategy] :as sortable]
            ["@dnd-kit/utilities" :refer [CSS]]
            [cljs-bean.core :as bean]
            [frontend.rum :as r]
            [frontend.state :as state]
            [logseq.shui.hooks :as hooks]
            [rum.core :as rum]))

(def dnd-context (r/adapt-class DndContext))
(def sortable-context (r/adapt-class SortableContext))
;; (def drag-overlay (r/adapt-class DragOverlay))

(rum/defc non-sortable-item
  [props children]
  [:div props children])

(rum/defc sortable-item
  [props children]
  (let [sortable (useSortable #js {:id (:id props)})
        attributes (.-attributes sortable)
        listeners (.-listeners sortable)
        set-node-ref (.-setNodeRef sortable)
        transform (.-transform sortable)
        transition (.-transition sortable)
        style #js {:transform ((.-toString (.-Transform CSS)) transform)
                   :transition transition}]
    [:div (merge
           {:ref set-node-ref
            :style style}
           (bean/->clj attributes)
           (bean/->clj listeners)
           (dissoc props :id))
     children]))

(defn- same-order?
  "True if two JS arrays of ids hold the same values in the same order."
  [a b]
  (and (= (.-length a) (.-length b))
       (every? true? (map (fn [i] (= (aget a i) (aget b i)))
                          (range (.-length a))))))

(defn- handle-drag-end
  "Applies a completed drag: optimistically lands the item in its new slot, then
  persists via `on-drag-end`. The component's re-sync effect is a no-op when the
  persisted order matches, so the item settles exactly once."
  [{:keys [ids id->item items-state set-items set-active-id on-drag-end]} event]
  (let [active-id (.-id (.-active event))
        over-id (.-id (.-over event))]
    (when active-id
      (when-not (= active-id over-id)
        (let [old-index (.indexOf ids active-id)
              new-index (.indexOf ids over-id)
              ;; Reorder the canonical `ids` (not the possibly-lagging
              ;; `items-state`) so the persisted order derives from the source of
              ;; truth.
              new-items (arrayMove (bean/->js ids) old-index new-index)]
          (when (fn? on-drag-end)
            (let [new-values (->> (map (fn [id]
                                         (let [item (id->item id)]
                                           (if (map? item) (:value item) item)))
                                       new-items)
                                  (remove nil?)
                                  vec)]
              (if (not= (count new-values) (count ids))
                (do
                  (js/console.error "Dnd length not matched: ")
                  {:old-items items-state
                   :new-items new-items})
                (do
                  (set-items new-items)
                  (on-drag-end new-values {:active-id active-id
                                           :over-id over-id
                                           :direction (if (> new-index old-index)
                                                        :down
                                                        :up)}))))))))
    (set-active-id nil)))

(rum/defc items
  [col* {:keys [on-drag-end parent-node vertical? sort-by-inner-element?]
         :or {vertical? true}}]
  (assert (every? :id col*))
  (when (some #(nil? (:id %)) col*)
    (js/console.error "dnd-kit items without id")
    (prn :col col*))
  (let [col (filter :id col*)
        ids (mapv :id col)
        items' (bean/->js ids)
        id->item (zipmap ids col)
        ;; `items-state` drives the SortableContext order (and thus the drop
        ;; animation target). On drop we optimistically set it to the new order
        ;; so the item lands in its final slot immediately. The canonical order
        ;; is `col` (persisted); this effect re-syncs `items-state` to it ONLY
        ;; when they actually differ, so a synchronous persist (already in the
        ;; new order) is a no-op — the item settles once, no back-then-forward.
        [items-state set-items] (rum/use-state items')
        _ (hooks/use-effect! (fn []
                               (when-not (same-order? items-state items')
                                 (set-items items')))
                             [ids])
        [_active-id set-active-id] (rum/use-state nil)
        sensors (useSensors (useSensor MouseSensor (bean/->js {:activationConstraint {:distance 8}})))
        dnd-opts {:sensors sensors
                  :collisionDetection closestCenter
                  :onDragStart (fn [event]
                                 (when-not (state/editing?)
                                   (set-active-id (.-id (.-active event)))))
                  :onDragEnd (fn [event]
                               (handle-drag-end {:ids ids
                                                 :id->item id->item
                                                 :items-state items-state
                                                 :set-items set-items
                                                 :set-active-id set-active-id
                                                 :on-drag-end on-drag-end}
                                                event))}
        sortable-opts {:items items-state
                       :strategy (if vertical?
                                   verticalListSortingStrategy
                                   horizontalListSortingStrategy)}
        ;; Render children in `items-state` order (NOT `col` order) so the DOM
        ;; order and the SortableContext order always come from the same source.
        ;; On drop, `set-items new-items` and the synchronous persist both yield
        ;; the same order in one commit, so the item settles in a single render.
        ;; Guard against a stale `items-state` id (favorite added/removed
        ;; elsewhere) mapping to nil before the re-sync effect runs.
        ordered-items (keep id->item items-state)
        children (for [item ordered-items]
                   (let [id (str (:id item))
                         prop (merge
                               (:prop item)
                               {:key id :id id})]
                     (cond
                       sort-by-inner-element?
                       [:div {:key id} (:content item)]

                       (:disabled? item)
                       (rum/with-key (non-sortable-item prop (:content item)) id)
                       :else
                       (rum/with-key (sortable-item prop (:content item)) id))))
        children' (if parent-node
                    [parent-node {:key "parent-node"} children]
                    children)]
    (dnd-context
     dnd-opts
     (sortable-context sortable-opts children')
     ;; (createPortal
     ;;  (drag-overlay
     ;;   (when active-id
     ;;     (sortable-item {:key active-id
     ;;                     :id active-id}
     ;;                    (:content (first (filter (fn [{:keys [id]}] (= id active-id)) items))))))
     ;;  js/document.body)
     )))
