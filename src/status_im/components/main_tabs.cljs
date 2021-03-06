(ns status-im.components.main-tabs
  (:require-macros [reagent.ratom :refer [reaction]]
                   [status-im.utils.views :refer [defview]]
                   [cljs.core.async.macros :as am])
  (:require [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [reagent.core :as r]
            [status-im.components.react :refer [view
                                                animated-view
                                                text
                                                image
                                                touchable-highlight
                                                get-dimensions
                                                swiper]]
            [status-im.components.status-bar :refer [status-bar]]
            [status-im.components.drawer.view :refer [drawer-view]]
            [status-im.components.animation :as anim]
            [status-im.components.tabs.bottom-shadow :refer [bottom-shadow-view]]
            [status-im.chats-list.screen :refer [chats-list]]
            [status-im.discover.screen :refer [discover]]
            [status-im.contacts.screen :refer [contact-list]]
            [status-im.components.tabs.tabs :refer [tabs]]
            [status-im.components.tabs.styles :as st]
            [status-im.components.styles :as common-st]
            [status-im.i18n :refer [label]]
            [cljs.core.async :as a]))

(def tab-list
  [{:view-id :chat-list
    :title   (label :t/chats)
    :screen  chats-list
    :icon    :icon_tab_chats
    :index   0}
   {:view-id :discover
    :title   (label :t/discover)
    :screen  discover
    :icon    :icon_tab_discover
    :index   1}
   {:view-id :contact-list
    :title   (label :t/contacts)
    :screen  contact-list
    :icon    :icon_tab_contacts
    :index   2}])

(defn animation-logic [{:keys [offsets val tab-id to-tab-id]}]
  (fn [_]
    (when-let [offsets @offsets]
      (let [from-value (:from offsets)
            to-value   (:to offsets)
            to-tab-id  @to-tab-id]
        (anim/set-value val from-value)
        (when to-value
          (anim/start
            (anim/timing val {:toValue  to-value
                              :duration 300})
            (when (= tab-id to-tab-id)
              (fn [arg]
                (when (.-finished arg)
                  (dispatch [:on-navigated-to-tab]))))))))))

(def tab->index {:chat-list    0
                 :discover     1
                 :contact-list 2})

(def index->tab (clojure.set/map-invert tab->index))

(defn get-tab-index [view-id]
  (get tab->index view-id 0))

(defn scroll-to [prev-view-id view-id]
  (let [p (get-tab-index prev-view-id)
        n (get-tab-index view-id)]
    (- n p)))

(defn on-scroll-end [swiped? dragging? scroll-ended]
  (fn [_ state]
    (a/put! scroll-ended true)
    (when @dragging?
      (reset! dragging? false)
      (let [{:strs [index]} (js->clj state)]
        (reset! swiped? true)
        (dispatch [:navigate-to-tab (index->tab index)])))))

(defn start-scrolling-loop
  "Loop that synchronizes tabs scrolling to avoid an inconsistent state."
  [scroll-start scroll-ended]
  (am/go-loop [[swiper to] (a/<! scroll-start)]
    ;; start scrolling
    (.scrollBy swiper to)
    ;; lock loop until scroll ends
    (a/<! scroll-ended)
    (recur (a/<! scroll-start))))

(defn main-tabs []
  (let [view-id      (subscribe [:get :view-id])
        prev-view-id (subscribe [:get :prev-view-id])
        main-swiper  (r/atom nil)
        swiped?      (r/atom false)
        dragging?    (r/atom false)
        scroll-start (a/chan 10)
        scroll-ended (a/chan 10)]
    (r/create-class
      {:component-did-mount
       #(start-scrolling-loop scroll-start scroll-ended)
       :component-will-update
       (fn []
         (if @swiped?
           (reset! swiped? false)
           (when @main-swiper
             (let [to (scroll-to @prev-view-id @view-id)]
               (a/put! scroll-start [@main-swiper to])))))
       :reagent-render
       (fn []
         [view common-st/flex
          [status-bar {:type :main}]
          [view common-st/flex
           [drawer-view
            [view {:style common-st/flex}
             [swiper (merge
                       st/main-swiper
                       {:index                  (get-tab-index @view-id)
                        :loop                   false
                        :ref                    #(reset! main-swiper %)
                        :onScrollBeginDrag      #(reset! dragging? true)
                        :on-momentum-scroll-end (on-scroll-end swiped? dragging? scroll-ended)})
              [chats-list]
              [discover (= @view-id :discover)]
              [contact-list (= @view-id :contact-list)]]
             [tabs {:selected-view-id @view-id
                    :prev-view-id     @prev-view-id
                    :tab-list         tab-list}]
             [bottom-shadow-view]]]]])})))
