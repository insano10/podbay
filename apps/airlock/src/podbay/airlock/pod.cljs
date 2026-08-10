(ns podbay.airlock.pod
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
            [podbay.shared.auth :as auth]
            [podbay.shared.vocab :as v]))

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

;; ---------------------------------------------------------------------------
;; Where a pod begins

(defn storage-roots+
  "The storage roots advertised by a WebID profile. Falls back to the
   WebID's own origin, which is right for most providers, so a profile
   that says nothing still gives us somewhere to start.

   That fallback is for a profile we *read* which doesn't say — not for
   one we couldn't read. Rooting the browser at a guessed origin after a
   failed fetch would silently show you the wrong pod."
  [webid]
  (-> (p/let [ds (sc/getSolidDataset webid (opts))
              thing (sc/getThing ds webid)
              urls (when thing (vec (array-seq (sc/getUrlAll thing v/pim-storage))))]
        (seq urls))
      (p/catch (fn [e]
                 (if (= 404 (some-> ^js e .-statusCode))
                   nil
                   (p/rejected e))))
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
        types (if thing (vec (array-seq (sc/getUrlAll thing v/rdf-type))) [])]
    {:url url
     :name (entry-name url)
     ;; a trailing slash is the reliable signal; the type is a bonus
     :container? (or (str/ends-with? url "/")
                     (boolean (some #{v/ldp-Container} types)))
     :size (when thing (sc/getInteger thing v/posix-size))
     :modified (when thing (sc/getDatetime thing v/dc-modified))
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
;; Who can see this
;;
;; The two servers this app is used against don't share an access-control
;; system: Community Solid Server implements WAC (an .acl document beside
;; each resource), Inrupt ESS implements ACP (composed policies). We ask
;; through solid-client's universal access API, which speaks both.
;;
;; Reading an access control resource itself requires *control* access,
;; so this only succeeds on data you own. Being refused is a normal
;; answer here, not a malfunction.

(def ^:private universal-access sc/universalAccess)

(defn- access->map [^js a]
  (when a
    {:read (.-read a)
     :append (.-append a)
     :write (.-write a)
     :control-read (.-controlRead a)
     :control-write (.-controlWrite a)}))

(defn- agents->map [^js all]
  (when all
    (into {}
          (for [webid (array-seq (js/Object.keys all))]
            [webid (access->map (aget all webid))]))))

(defn access+
  "Who can do what with a resource.

   Resolves to {:public {…} :agents {webid {…}}}. Either half is nil
   when the server won't say — a server may simply not report public
   access, which is different from reporting that there is none. Rejects
   only if the access control resource can't be read at all, which is
   what happens on resources you don't control."
  [url]
  (p/let [public (.getPublicAccess ^js universal-access url (opts))
          agents (.getAgentAccessAll ^js universal-access url (opts))]
    {:public (access->map public)
     :agents (agents->map agents)}))

(defn- ->js-access
  "Only the modes actually named are sent, so a change can adjust one
   permission without restating the others."
  [m]
  (let [o #js {}]
    (when (contains? m :read) (aset o "read" (boolean (:read m))))
    (when (contains? m :append) (aset o "append" (boolean (:append m))))
    (when (contains? m :write) (aset o "write" (boolean (:write m))))
    (when (contains? m :control-read) (aset o "controlRead" (boolean (:control-read m))))
    (when (contains? m :control-write) (aset o "controlWrite" (boolean (:control-write m))))
    o))

(defn set-agent-access+
  "Grant or revoke one person's access. Needs control access, so it only
   works on a pod you own."
  [url webid access]
  (p/let [result (.setAgentAccess ^js universal-access url webid
                                  (->js-access access) (opts))]
    (access->map result)))

(defn set-public-access+
  "Change what everyone — including people who aren't signed in — can do."
  [url access]
  (p/let [result (.setPublicAccess ^js universal-access url
                                   (->js-access access) (opts))]
    (access->map result)))

(defn agent-access+
  "One agent's access to one resource. Used to check whether a grant on
   a container actually reached the things inside it."
  [url webid]
  (p/let [result (.getAgentAccess ^js universal-access url webid (opts))]
    (access->map result)))

;; ---------------------------------------------------------------------------
;; Writing (deleting, for now)

(defn delete+
  "Delete a resource. Containers need a different call, and most servers
   refuse to delete one that still has children."
  [{:keys [url container?]}]
  (if container?
    (sc/deleteContainer url (opts))
    (sc/deleteFile url (opts))))
