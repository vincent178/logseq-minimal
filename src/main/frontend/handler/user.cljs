(ns frontend.handler.user
  "User/account stubs for the minimal, account-free build.

  This build has no login, no Logseq account, and no sync, so every
  identity accessor returns a logged-out value (nil / false). The fns are
  kept as a stable seam so that callers elsewhere in the app keep compiling;
  any call site that gates behaviour on login simply never activates."
  (:require-macros [frontend.handler.user])
  (:require [cljs.core.async :refer [go]]
            [frontend.handler.config :as config-handler]
            [frontend.state :as state]
            [missionary.core :as m]))

(defn set-preferred-format!
  [format]
  (when format
    (config-handler/set-config! :preferred-format format)
    (state/set-preferred-format! format)))

(defn set-preferred-workflow!
  [workflow]
  (when workflow
    (config-handler/set-config! :preferred-workflow workflow)
    (state/set-preferred-workflow! workflow)))

;;; identity accessors — always logged out in the minimal build

(defn email [] nil)

(defn username [] nil)

(defn user-uuid [] nil)

(defn <user-uuid
  "Async variant; with no account there is never a uuid."
  []
  (go nil))

(defn logged-in? [] false)

(defn has-refresh-token? [] false)

;;; no-op lifecycle (kept so startup / event handlers are inert)

(defn restore-tokens-from-localstorage [] nil)

(defn logout [] nil)

(defn upgrade
  "No paid accounts in the minimal build."
  []
  nil)

;;; token plumbing — referenced by legacy sync code; inert without an account

(defn <ensure-id&access-token
  "There is never a token to ensure; resolves with nil."
  []
  (go nil))

(def task--ensure-id&access-token
  "Missionary task variant; completes immediately with no token."
  (m/sp nil))

(defn new-task--upload-user-avatar
  "No avatars without an account."
  [_avatar-str]
  (m/sp nil))

;;; user groups / feature gating — never active without an account

(defn rtc-group? [] false)

(defn alpha-user? [] false)

(defn beta-user? [] false)

(defn alpha-or-beta-user? [] false)

(defn get-user-type [_repo] nil)

(defn manager? [_repo] false)
