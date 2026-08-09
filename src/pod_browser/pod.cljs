(ns pod-browser.pod
  "Reading a pod as if it were a filesystem.

   This is the only namespace here that touches JS objects; everything
   above it works with plain Clojure maps. Functions suffixed with +
   return promises.

   Listing is cheap: a container's own representation already describes
   its children — size, modification time and type — so one request
   yields a whole directory rather than one request per entry. Servers
   aren't obliged to publish all of that, so every field is optional."
  (:require ["@inrupt/solid-client" :as sc]
            [clojure.string :as str]
            [promesa.core :as p]
            [solid-social.auth :as auth]))

(defn- opts [] #js {:fetch auth/auth-fetch})

;; Pod responses carry no Cache-Control but do carry Last-Modified,
;; which makes them *heuristically* cacheable: the browser may reuse one
;; for a while without asking. That's wrong for anything we might have
;; just changed — a listing fetched right after a delete would still
;; contain the deleted file.
;;
;; "no-cache" rather than "no-store": the response is still cached, but
;; every read revalidates against the server, which answers 304 Not
;; Modified when nothing has changed. Correctness without throwing the
;; cache away — the pod supplies both ETag and Last-Modified, so
;; revalidation is cheap.
(defn- revalidate [init]
  (js/Object.assign #js {} (or init #js {}) #js {:cache "no-cache"}))

(defn- revalidating-fetch [url init]
  (auth/auth-fetch url (revalidate init)))

(defn- fresh-opts [] #js {:fetch revalidating-fetch})

(def ^:private rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
(def ^:private pim-storage "http://www.w3.org/ns/pim/space#storage")
(def ^:private ldp-Container "http://www.w3.org/ns/ldp#Container")
(def ^:private dc-modified "http://purl.org/dc/terms/modified")
(def ^:private posix-size "http://www.w3.org/ns/posix/stat#size")

;; ---------------------------------------------------------------------------
;; Where a pod begins

(defn storage-roots+
  "The storage roots advertised by a WebID profile. Falls back to the
   WebID's own origin, which is right for most providers, so a profile
   that says nothing still gives us somewhere to start."
  [webid]
  (-> (p/let [ds (sc/getSolidDataset webid (opts))
              thing (sc/getThing ds webid)
              urls (when thing (vec (array-seq (sc/getUrlAll thing pim-storage))))]
        (seq urls))
      (p/catch (fn [_] nil))
      (p/then (fn [roots]
                (vec (or roots [(str (.-origin (js/URL. webid)) "/")]))))))

;; ---------------------------------------------------------------------------
;; Listing

(defn entry-name
  "The last path segment of a resource URL, percent-decoded."
  [url]
  (let [trimmed (cond-> url (str/ends-with? url "/") (subs 0 (dec (count url))))
        segment (last (str/split trimmed #"/"))]
    (try
      (js/decodeURIComponent segment)
      (catch :default _ segment))))

(defn- media-type
  "Servers type resources with an IANA media-type IRI, e.g.
   .../iana/media-types/text/turtle#Resource. Pull the media type back
   out of it, since that's what a person wants to see."
  [types]
  (some (fn [t]
          (when-let [[_ mt] (re-find #"/media-types/(.+)#Resource$" t)]
            mt))
        types))

(defn- entry [ds url]
  (let [thing (sc/getThing ds url)
        types (if thing (vec (array-seq (sc/getUrlAll thing rdf-type))) [])]
    {:url url
     :name (entry-name url)
     ;; a trailing slash is the reliable signal; the type is a bonus
     :container? (or (str/ends-with? url "/")
                     (boolean (some #{ldp-Container} types)))
     :size (when thing (sc/getInteger thing posix-size))
     :modified (when thing (sc/getDatetime thing dc-modified))
     :media-type (media-type types)}))

(defn list-container+
  "Entries of a container, folders first then files, each alphabetical."
  [container-url]
  (p/let [ds (sc/getSolidDataset container-url (fresh-opts))]
    (->> (array-seq (sc/getContainedResourceUrlAll ds))
         (map #(entry ds %))
         (sort-by (juxt (complement :container?)
                        #(str/lower-case (:name %))))
         vec)))

;; ---------------------------------------------------------------------------
;; Reading one resource

(defn- headers->map [^js headers]
  (let [acc (atom {})]
    (.forEach headers (fn [v k] (swap! acc assoc k v)))
    @acc))

(defn- textual? [content-type]
  (boolean
   (and content-type
        (or (str/starts-with? content-type "text/")
            (re-find #"json|xml|turtle|n-?triples|n3|trig|sparql|javascript"
                     content-type)))))

(defn- image? [content-type]
  (boolean (and content-type (str/starts-with? content-type "image/"))))

(defn read-resource+
  "Fetch a resource for display. Text-ish content comes back as :text,
   anything else as an :object-url the browser can render or download —
   authenticated bytes can't be handed to an <img src> directly.

   A failed read still resolves, carrying :status and the server's
   message, because 'you may not read this' is information worth showing
   rather than an error to swallow."
  [url]
  (p/let [resp (auth/auth-fetch url (revalidate nil))
          headers (headers->map (.-headers resp))
          content-type (get headers "content-type")
          base {:url url
                :status (.-status resp)
                :ok? (.-ok resp)
                :content-type content-type
                :headers headers}]
    (cond
      (not (.-ok resp))
      (p/let [body (.text resp)] (assoc base :text body))

      (textual? content-type)
      (p/let [body (.text resp)] (assoc base :text body))

      :else
      (p/let [blob (.blob resp)]
        (assoc base
               :object-url (js/URL.createObjectURL blob)
               :image? (image? content-type))))))

(defn release-object-url!
  "Free an :object-url from read-resource+."
  [object-url]
  (js/URL.revokeObjectURL object-url))

;; ---------------------------------------------------------------------------
;; Writing (deleting, for now)

(defn delete+
  "Delete a resource. Containers need a different call, and most servers
   refuse to delete one that still has children."
  [{:keys [url container?]}]
  (if container?
    (sc/deleteContainer url (opts))
    (sc/deleteFile url (opts))))
