(ns status-im.commands.handlers.jail
  (:require [re-frame.core :refer [after dispatch subscribe trim-v debug]]
            [status-im.utils.handlers :as u]
            [status-im.utils.utils :refer [http-get show-popup]]
            [status-im.components.status :as status]
            [status-im.utils.types :refer [json->clj]]
            [status-im.commands.utils :refer [generate-hiccup reg-handler]]
            [clojure.string :as s]
            [status-im.components.react :as r]
            [status-im.models.commands :as cm]
            [status-im.constants :refer [console-chat-id]]
            [taoensso.timbre :as log]))

(defn render-command
  [db [chat-id message-id markup]]
  (let [hiccup (generate-hiccup markup)]
    (assoc-in db [:rendered-commands chat-id message-id] hiccup)))

(defn command-handler!
  [_ [chat-id
      {:keys [command-message] :as parameters}
      {:keys [result error]}]]
  (let [{:keys [context returned]} result
        {handler-error :error} returned]
    (log/debug "command handler: " result error parameters)
    (cond
      handler-error
      (log/debug :error-from-handler handler-error
                 :chat-id chat-id
                 :command command-message)

      result
      (let [command'    (assoc command-message :handler-data returned)
            parameters' (assoc parameters :command command')]
        (if (:eth_sendTransaction context)
          (dispatch [:wait-for-transaction (:id command-message) parameters'])
          (dispatch [:prepare-command! chat-id parameters'])))

      (not (or error handler-error))
      (dispatch [:prepare-command! chat-id parameters])

      :else nil)))

(defn suggestions-handler!
  [{:keys [contacts] :as db} [{:keys [chat-id]} {:keys [result]}]]
  (let [{:keys [markup webViewUrl]} (:returned result)
        {:keys [dapp? dapp-url]} (get contacts chat-id)
        hiccup       (generate-hiccup markup)
        web-view-url (if (and (= webViewUrl "dapp-url") dapp? dapp-url)
                       dapp-url
                       webViewUrl)]
    (-> db
        (assoc-in [:suggestions chat-id] hiccup)
        (assoc-in [:web-view-url chat-id] web-view-url)
        (assoc-in [:has-suggestions? chat-id] (or hiccup web-view-url)))))

(defn suggestions-events-handler!
  [{:keys [current-chat-id] :as db} [[n data]]]
  (log/debug "Suggestion event: " data)
  (let [{:keys [dapp?] :as contact} (get-in db [:contacts current-chat-id])
        command? (= :command (:type (cm/get-chat-command db)))]
  (case (keyword n)
    :set-value (if command?
                 (dispatch [:fill-chat-command-content data])
                 (when dapp?
                   (dispatch [:set-chat-input-text data])))
    ;; todo show error?
    nil)))

(defn command-preview
  [_ [command-message {:keys [result]}]]
  (let [result' (:returned result)]
    (dispatch [:send-chat-message
               (if result'
                 (assoc command-message
                   :preview (generate-hiccup result')
                   :preview-string (str result'))
                 command-message)])))

(defn print-error-message! [message]
  (fn [_ params]
    (when (:error (last params))
      (show-popup "Error" (s/join "\n" [message params]))
      (log/debug message params))))

(reg-handler ::render-command render-command)

(reg-handler :command-handler!
  (after (print-error-message! "Error on command handling"))
  (u/side-effect! command-handler!))

(reg-handler :suggestions-handler
  [(after #(dispatch [:animate-show-response]))
   (after (print-error-message! "Error on param suggestions"))
   (after (fn [_ [{:keys [command]}]]
            (when (= :on-send (keyword (:suggestions-trigger command)))
              #_(when (:webViewUrl (:returned result))
                (dispatch [:set-soft-input-mode :pan]))
              (r/dismiss-keyboard!))))]
  suggestions-handler!)
(reg-handler :suggestions-event! (u/side-effect! suggestions-events-handler!))

(reg-handler :command-preview
  (after (print-error-message! "Error on command preview"))
  (u/side-effect! command-preview))

(reg-handler :set-local-storage
  (fn [{:keys [current-chat-id] :as db} [{:keys [data] :as event}]]
    (log/debug "Got event: " event)
    (assoc-in db [:local-storage current-chat-id] data)))
