(ns frontend.mobile.util
  "Minimal build stub: the mobile client has been removed. These fns keep
   desktop/electron call sites compiling; all native checks return false and
   native side-effects are no-ops, so the desktop code path is always taken."
  (:require [clojure.string :as string]))

(defn platform [] "web")

(defn native-platform? [] false)

(defn native-ios? [] false)

(defn native-android? [] false)

(defn convert-file-src [path-str] path-str)

(defn hide-splash [] nil)

(defn set-native-interface-style!
  "No-op: no native shell to style."
  [_mode _system?]
  nil)

(defn native-iphone-without-notch? [] false)

(defn native-iphone? [] false)

(defn native-ipad? [] false)

(defn in-iCloud-container-path?
  "Check whether `path' is logseq's iCloud container path on iOS"
  [path]
  (string/includes? path "/iCloud~com~logseq~logseq/"))

(defn mobile-focus-hidden-input [] nil)
