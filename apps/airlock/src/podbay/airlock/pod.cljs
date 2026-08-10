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

(defn parse-links
  "A Link header into {rel url}. Auxiliary resources are advertised here
   and nowhere else — never construct their URLs: <name>.acl and
   <name>.meta are one server's convention, and ESS names its access
   control resources differently."
  [header]
  (when header
    (into {}
          (for [part (str/split header #",\s*(?=<)")
                :let [[_ url] (re-find #"<([^>]*)>" part)
                      [_ rel] (re-find #"rel=\"?([^\";]+)\"?" part)]
                :when (and url rel)]
            [rel url]))))

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
                :headers headers
                ;; kept for If-Match when saving an edit
                :etag (get headers "etag")
                :links (parse-links (get headers "link"))}]
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

(defn attached+
  "The auxiliary resources hanging off a resource — its access control
   resource, its description — as {rel url}.

   Costs a HEAD per resource, because containers list their children but
   not the things attached to those children. That's why revealing them
   is a deliberate act rather than the default."
  [url]
  (-> (p/let [resp (auth/auth-fetch url #js {:method "HEAD" :cache "no-cache"})]
        (parse-links (.get (.-headers resp) "link")))
      (p/catch (fn [_] nil))))

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
;; Writing

(defn- put+ [url text content-type extra-headers]
  (auth/auth-fetch
   url
   (clj->js {:method "PUT"
             :headers (merge {"Content-Type" (or content-type "text/turtle")}
                             extra-headers)
             :body text})))

(defn- current-etag+
  "The resource's validator as it stands now, or nil."
  [url]
  (-> (p/let [resp (auth/auth-fetch url #js {:method "HEAD" :cache "no-cache"})]
        (when (.-ok resp) (.get (.-headers resp) "etag")))
      (p/catch (fn [_] nil))))

(defn save-text+
  "Replace a text resource's contents, sending it back as it was typed.

   Deliberately a raw PUT rather than parsing and reserialising through
   solid-client: someone hand-editing Turtle wants their comments,
   prefixes and layout preserved, not normalised away.

   Saves are guarded with If-Match so a resource changed by someone else
   isn't silently overwritten. That guard needs care, because servers
   commonly issue *weak* ETags (`W/\"…\"`) and If-Match is defined to
   compare strongly — so a 412 may mean 'someone else changed this' or
   merely 'this server won't honour a weak validator'. Rather than guess,
   re-read the validator: unchanged means the precondition was refused on
   principle and the write is safe to repeat; changed means a real
   conflict, which is reported and never overwritten."
  [{:keys [url content-type etag]} text]
  (p/let [resp (put+ url text content-type (when etag {"If-Match" etag}))
          resp (if (and etag (= 412 (.-status resp)))
                 (p/let [current (current-etag+ url)]
                   (if (or (nil? current) (= etag current))
                     (put+ url text content-type nil)
                     (p/rejected
                      (js/Error. "Someone else changed this since you opened it — reopen it and reapply your edit."))))
                 resp)]
    (if (.-ok resp)
      ;; Hand back the new validator so the caller can keep editing. A
      ;; save makes the ETag it was holding stale, and re-sending that
      ;; one looks exactly like someone else having changed the file —
      ;; the conflict check would fire on your own previous save.
      ;; Servers usually return it on the PUT; ask only if not.
      (p/let [returned (.get (.-headers resp) "etag")
              etag (or returned (current-etag+ url))]
        {:etag etag})
      (p/let [body (.text resp)]
        (p/rejected (js/Error. (str "Save failed: " (.-status resp)
                                    (when (seq body) (str " — " body)))))))))

(defn delete+
  "Delete a resource. Containers need a different call, and most servers
   refuse to delete one that still has children."
  [{:keys [url container?]}]
  (if container?
    (sc/deleteContainer url (opts))
    (sc/deleteFile url (opts))))

;; ---------------------------------------------------------------------------
;; Creating

(def ^:private extension-types
  {"ttl" "text/turtle"
   "n3" "text/n3"
   "trig" "application/trig"
   "nt" "application/n-triples"
   "jsonld" "application/ld+json"
   "json" "application/json"
   "md" "text/markdown"
   "html" "text/html"
   "css" "text/css"
   "js" "text/javascript"
   "csv" "text/csv"})

(defn content-type-for
  "Guessed from the extension. A pod stores whatever content type you
   declare, and gets it wrong quietly — a Turtle document served as
   text/plain won't parse as RDF for anyone."
  [name]
  (let [ext (str/lower-case (or (second (re-find #"\.([^.]+)$" name)) ""))]
    (get extension-types ext "text/plain")))

(defn create-file+
  "Create an empty resource. Empty Turtle is a valid document, so this
   gives you something to open in the editor."
  [url content-type]
  (p/let [init (doto #js {:method "PUT"
                          :headers #js {"Content-Type" content-type}}
                 (aset "body" ""))
          resp (auth/auth-fetch url init)]
    (if (.-ok resp)
      resp
      (p/let [body (.text resp)]
        (p/rejected (js/Error. (str (.-status resp)
                                    (when (seq body) (str " — " body)))))))))

(defn upload-file+
  "Store a picked file as a resource, bytes as-is.

   The browser usually knows the content type from the file itself,
   which beats guessing; fall back to the extension when it doesn't, and
   to octet-stream when even that fails — better an honest 'unknown
   bytes' than a wrong type a pod will faithfully serve forever."
  [url ^js file]
  (p/let [declared (.-type file)
          content-type (cond
                         (seq declared) declared
                         :else (let [guessed (content-type-for (.-name file))]
                                 (if (= guessed "text/plain")
                                   "application/octet-stream"
                                   guessed)))
          init (doto #js {:method "PUT"
                          :headers #js {"Content-Type" content-type}}
                 (aset "body" file))
          resp (auth/auth-fetch url init)]
    (if (.-ok resp)
      resp
      (p/let [body (.text resp)]
        (p/rejected (js/Error. (str (.-name file) ": " (.-status resp)
                                    (when (seq body) (str " — " body)))))))))

(defn create-container+ [url]
  (sc/createContainerAt url (opts)))

;; ---------------------------------------------------------------------------
;; Moving
;;
;; There is no MOVE in Solid: a move is a copy followed by a delete, in
;; that order deliberately — if the copy fails, the original is still
;; there. The cost is that an interrupted move of a folder leaves some
;; items in both places rather than losing any.
;;
;; Two things do NOT come along, and both matter enough that the UI says
;; so before you commit: a resource's access control belongs to its URL,
;; so the copy inherits whatever the destination gives it; and nothing
;; that references the old URL is rewritten.

(declare move+)

(defn- move-file+ [from to]
  (p/let [resp (auth/auth-fetch from #js {:cache "no-store"})
          _ (when-not (.-ok resp)
              (p/rejected (js/Error. (str "Couldn't read " from ": " (.-status resp)))))
          blob (.blob resp)
          content-type (or (.get (.-headers resp) "content-type")
                           "application/octet-stream")
          init (doto #js {:method "PUT"
                          :headers #js {"Content-Type" content-type}}
                 (aset "body" blob))
          put (auth/auth-fetch to init)
          _ (when-not (.-ok put)
              (p/rejected (js/Error. (str "Couldn't write " to ": " (.-status put)))))]
    ;; only now is it safe to let go of the original
    (sc/deleteFile from (opts))))

(defn- move-container+ [from to]
  (p/let [_ (-> (sc/createContainerAt to (opts))
                (p/catch (fn [e]
                           ;; already there is the state we want
                           (if (#{409 412} (some-> ^js e .-statusCode))
                             nil
                             (p/rejected e)))))
          entries (list-container+ from)
          _ (p/all (mapv (fn [{:keys [name container?] :as child}]
                           (move+ child (str to (js/encodeURIComponent name)
                                             (when container? "/"))))
                         entries))]
    ;; empty at last, so the server will accept the delete
    (sc/deleteContainer from (opts))))

(defn move+
  "Move or rename a resource. Folders move their whole contents,
   depth-first, each child copied before its original is removed."
  [{:keys [url container?]} target]
  (if container?
    (move-container+ url target)
    (move-file+ url target)))
