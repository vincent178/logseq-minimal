(ns ^:no-doc frontend.handler.assets
  (:require [clojure.string :as string]
            [frontend.common.thread-api :as thread-api :refer [def-thread-api]]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.common.config :as common-config]
            [logseq.common.path :as path]
            [logseq.common.util :as common-util]
            [logseq.db.common.entity-plus :as entity-plus]
            [logseq.db.frontend.asset :as db-asset]
            [medley.core :as medley]
            [promesa.core :as p]))

(defn get-area-block-asset-url
  "Returns asset url for an area block used by pdf assets."
  [db block page]
  (let [db-based? (entity-plus/db-based-graph? db)]
    (when-some [uuid' (:block/uuid block)]
      (if db-based?
        (when-let [image (:logseq.property.pdf/hl-image block)]
          (str "./assets/" (:block/uuid image) ".png"))
        (let [props (and block page (:block/properties block))
              prop-lookup-fn #(get %1 (keyword (name %2)))]
          (when-some [stamp (:hl-stamp props)]
            (let [group-key      (string/replace-first (:block/title page) #"^hls__" "")
                  hl-page        (prop-lookup-fn props :logseq.property.pdf/hl-page)
                  encoded-chars? (boolean (re-find #"(?i)%[0-9a-f]{2}" group-key))
                  group-key      (if encoded-chars? (js/encodeURI group-key) group-key)]
              (str "./assets/" group-key "/" (str hl-page "_" uuid' "_" stamp ".png")))))))))

(defn alias-enabled?
  []
  (and (util/electron?)
       (:assets/alias-enabled? @state/state)))

(defn clean-path-prefix
  [path]
  (when (string? path)
    (string/replace-first path #"^[.\/\\]*(assets)[\/\\]+" "")))

(defn check-alias-path?
  [path]
  (and (string? path)
       (some-> path
               (clean-path-prefix)
               (string/starts-with? "@"))))

(defn get-alias-dirs
  []
  (:assets/alias-dirs @state/state))

(defn get-alias-by-dir
  [dir]
  (when-let [alias-dirs (and (alias-enabled?) (seq (get-alias-dirs)))]
    (medley/find-first #(= dir (:dir (second %1)))
                       (medley/indexed alias-dirs))))

(defn get-alias-by-name
  [name]
  (when-let [alias-dirs (and (alias-enabled?) (seq (get-alias-dirs)))]
    (medley/find-first #(= name (:name (second %1)))
                       (medley/indexed alias-dirs))))

(defn resolve-asset-real-path-url
  [repo rpath]
  (when-let [rpath (and (string? rpath)
                        (string/replace rpath #"^[.\/\\]+" ""))]
    (let [rpath (if-not (string/starts-with? rpath common-config/local-assets-dir)
                  (path/path-join common-config/local-assets-dir rpath)
                  rpath)
          encoded-chars? (boolean (re-find #"(?i)%[0-9a-f]{2}" rpath))
          rpath (if encoded-chars? (js/decodeURI rpath) rpath)
          graph-root (config/get-repo-dir repo)
          has-schema? (string/starts-with? graph-root "file:")]
      (if has-schema?
        (path/path-join graph-root rpath)
        (path/prepend-protocol "file:" (path/path-join graph-root rpath))))))

(defn normalize-asset-resource-url
  "try to convert resource file to url asset link"
  [path]
  (let [protocol-link? (common-config/protocol-path? path)]
    (cond
      protocol-link?
      path

      ;; BUG: avoid double encoding from PDF assets
      (path/absolute? path)
      (if (boolean (re-find #"(?i)%[0-9a-f]{2}" path)) ;; has encoded chars?
        ;; Incoming path might be already URL encoded. from PDF assets
        (path/path-join "file://" (common-util/safe-decode-uri-component path))
        (path/path-join "file://" path))

      :else ;; relative path or alias path
      (some-> (resolve-asset-real-path-url (state/get-current-repo) path)
              (common-util/safe-decode-uri-component)))))

(defn get-matched-alias-by-ext
  [ext]
  (when-let [ext (and (alias-enabled?)
                      (string? ext)
                      (not (string/blank? ext))
                      (util/safe-lower-case ext))]

    (let [alias (medley/find-first
                 (fn [{:keys [exts]}]
                   (some #(string/ends-with? ext %) exts))
                 (get-alias-dirs))]
      alias)))

(defn get-asset-file-link
  "Link text for inserting to markdown/org"
  [format url file-name image?]
  (let [pdf?   (and url (string/ends-with? (string/lower-case url) ".pdf"))
        media? (and url (or (config/ext-of-audio? url)
                            (config/ext-of-video? url)))]
    (case (keyword format)
      :markdown (util/format (str (when (or image? media? pdf?) "!") "[%s](%s)") file-name url)
      :org (if image?
             (util/format "[[%s]]" url)
             (util/format "[[%s][%s]]" url file-name))
      nil)))

(defn <make-asset-url
  "Make accessible asset url from path.
   If path is absolute url, return it directly.
   If path is relative path, return blob url or file url according to environment."
  ([path] (<make-asset-url path (try (js/URL. path) (catch :default _ nil))))
  ([path ^js js-url]
   ;; path start with "/assets"(editor)
   (let [repo (state/get-current-repo)
           repo-dir (config/get-repo-dir repo)
           local-asset? (common-config/local-relative-asset? path)
           ;; Hack for path calculation
           path (string/replace path #"^(\.\.)?/" "./")
           js-url? (not (nil? js-url))]
       (cond
         js-url?
         path                                               ;; just return the original

         (and (alias-enabled?)
              (check-alias-path? path))
         (resolve-asset-real-path-url (state/get-current-repo) path)

         (util/electron?)
         (let [full-path (if local-asset?
                           (path/path-join repo-dir path) path)]
           ;; fullpath will be encoded
           (path/prepend-protocol "file:" full-path))

         ;(mobile-util/native-platform?)
         ;(mobile-util/convert-file-src full-path)
         ))))

(defn get-file-checksum
  [^js file]
  (-> (if (string? file) file (.arrayBuffer file))
      (p/then db-asset/<get-file-array-buffer-checksum)))

(defn ensure-assets-dir!
  [repo]
  (p/let [repo-dir (config/get-repo-dir repo)
          assets-dir "assets"
          _ (fs/mkdir-if-not-exists (path/path-join repo-dir assets-dir))]
    [repo-dir assets-dir]))

(defn <get-all-asset-file-paths
  [repo]
  (when-let [path (config/get-repo-assets-root repo)]
    (p/catch (fs/readdir path {:path-only? true})
             (constantly nil))))

(defn <read-asset
  "Throw if asset not found"
  [repo asset-block-id asset-type]
  (let [repo-dir (config/get-repo-dir repo)
        file-path (path/path-join common-config/local-assets-dir
                                  (str asset-block-id "." asset-type))]
    (fs/read-file-raw repo-dir file-path {})))

(defn <get-asset-file-metadata
  [repo asset-block-id asset-type]
  (-> (p/let [file (<read-asset repo asset-block-id asset-type)
              blob (js/Blob. (array file) (clj->js {:type "image"}))
              checksum (get-file-checksum blob)]
        {:checksum checksum})
      (p/catch (constantly nil))))

(defn <write-asset
  [repo asset-block-id asset-type data]
  (let [asset-block-id-str (str asset-block-id)
        repo-dir (config/get-repo-dir repo)
        file-path (path/path-join common-config/local-assets-dir
                                  (str asset-block-id-str "." asset-type))]
    (p/do!
     (fs/write-plain-text-file! repo repo-dir file-path data {})
     (state/update-state!
      :assets/asset-file-write-finish
      (fn [m] (assoc-in m [repo asset-block-id-str] (common-util/time-ms)))))))

(comment
  ;; en/decrypt assets
  (def repo (state/get-current-repo))
  (p/let [aes-key (crypt/<generate-aes-key)
          asset (<read-asset repo "6903201e-9573-4914-ae88-7d3f1d095d1f" "png")
          encrypted-asset (crypt/<encrypt-uint8array aes-key asset)
          decrypted-asset (crypt/<decrypt-uint8array aes-key encrypted-asset)]
    (def asset asset)
    (def xxxx encrypted-asset)
    (prn :decrypted (.-length decrypted-asset)
         :origin (.-length asset))))

(defn <unlink-asset
  [repo asset-block-id asset-type]
  (let [file-path (path/path-join (config/get-repo-dir repo)
                                  common-config/local-assets-dir
                                  (str asset-block-id "." asset-type))]
    (p/catch (fs/unlink! repo file-path {}) (constantly nil))))

(def-thread-api :thread-api/unlink-asset
  [repo asset-block-id asset-type]
  (<unlink-asset repo asset-block-id asset-type))

(def-thread-api :thread-api/get-all-asset-file-paths
  [repo]
  (<get-all-asset-file-paths repo))

(def-thread-api :thread-api/get-asset-file-metadata
  [repo asset-block-id asset-type]
  (<get-asset-file-metadata repo asset-block-id asset-type))

(comment
  ;; read asset
  (p/let [repo "logseq_db_demo"
          ;; Existing asset block's id
          asset-block-id-str "672c5a1d-8171-4259-9f35-470c3c67e37f"
          asset-type "png"
          data (<read-asset repo asset-block-id-str asset-type)]
    (js/console.dir data))

  ;; write asset
  (p/let [repo "logseq_db_demo"
          ;; Existing asset block's id
          asset-block-id-str "672c5a1d-8171-4259-9f35-470c3c67e37f"
          asset-type "png"
          data (<read-asset repo asset-block-id-str asset-type)
          new-asset-id (random-uuid)
          result (<write-asset repo new-asset-id asset-type data)]
    (js/console.dir result)))
