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
            [podbay.shared.access :as access]
            [podbay.shared.auth :as auth]
            [podbay.shared.vocab :as v]))


;; Fetch options — including the no-cache reasoning — live in
;; podbay.shared.auth, so both apps mean the same thing by them.

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
  (-> (p/let [ds (sc/getSolidDataset webid (auth/opts))
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
  "Entries of a container, containers first then files, each alphabetical."
  [container-url]
  (p/let [ds (sc/getSolidDataset container-url (auth/fresh-opts))]
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
  (p/let [resp (auth/auth-fetch url (auth/revalidate-init nil))
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

;; The ACP-specific API, needed wherever the universal one can't express
;; something — which turns out to be anything about a container's
;; contents. See "Access that reaches a container's contents" below.
(def ^:private acp sc/acp_ess_2)

(defn- access->map [^js a]
  (when a
    {:read (.-read a)
     :append (.-append a)
     :write (.-write a)
     :control-read (.-controlRead a)
     :control-write (.-controlWrite a)}))

(def ^:private acp-sentinels
  "Values that appear in acp:agent but aren't anyone's WebID. ACP has no
   separate predicate for 'everyone' — it writes an agent with a magic
   IRI — so any list of agents has to be sieved before it can be shown
   as a list of people."
  #{"http://www.w3.org/ns/solid/acp#PublicAgent"
    "http://www.w3.org/ns/solid/acp#AuthenticatedAgent"
    "http://www.w3.org/ns/solid/acp#CreatorAgent"})

(defn- agents->map
  "Named people only.

   getAgentAccessAll enumerates agents with `getAgentUrlAll`, which
   reads every acp:agent in the access control resource and does *not*
   filter the sentinels — unlike `getAgentAll`, the matcher accessor,
   which does. So a publicly readable container reported
   acp:PublicAgent as though it were a person, listed beside real
   contacts with a Revoke button. Displayed, it loses its fragment and
   reads as `www.w3.org/ns/solid/acp`, which looks like an
   organisation rather than a sentinel.

   Dropping them here loses nothing: public access has its own row,
   fed by getPublicAccess, so it was being reported twice — once
   correctly and once as a stranger."
  [^js all]
  (when all
    (into {}
          (for [webid (array-seq (js/Object.keys all))
                :when (not (acp-sentinels webid))]
            [webid (access->map (aget all webid))]))))

(defn- wac-access->map
  "WAC has one acl:Control where the universal shape has two flags."
  [^js a]
  (when a
    {:read (.-read a) :append (.-append a) :write (.-write a)
     :control-read (.-control a) :control-write (.-control a)}))

(defn- wac-agents->map [^js all]
  (when all
    (into {}
          (for [webid (array-seq (js/Object.keys all))]
            [webid (wac-access->map (aget all webid))]))))

;; Defined further down, beside the other hand-rolled ACP readers — the
;; vocabulary it walks is declared there.
(declare authenticated-access+)

(defn access+
  "Rules set **on this resource**, as {:public {…} :agents {webid {…}}}.

   Deliberately not effective access. On ACP that's all the API can
   report anyway, but on WAC `getAgentAccessAll` folds in whatever the
   resource inherits, with no way to tell the two apart afterwards — so
   a file in a shared container listed the people who could read it as
   though each had been granted access to that file, next to a Revoke
   button. Revoking there doesn't undo the container's rule; it writes
   the file its own ACL, which quietly detaches it from the container
   for good. The WAC path therefore reads the resource's own ACL
   directly, and what's inherited is reported separately by
   access-context+.

   Either half is nil when the server won't say, which is different
   from reporting that there is none. Rejects only if the access
   control resource can't be read at all, which is what happens on
   resources you don't control."
  [url]
  ;; every read here happens either just before or just after a write to
  ;; the very thing being read, so it must revalidate — a cached access
  ;; control resource would report the rules as they were, which reads as
  ;; "the change didn't work"
  (p/let [acp? (.isAcpControlled acp url (auth/opts))
          ;; the agent-class tier, which no universal call reports
          authenticated (authenticated-access+ url)]
    (if acp?
      (p/let [public (.getPublicAccess ^js universal-access url (auth/fresh-opts))
              agents (.getAgentAccessAll ^js universal-access url (auth/fresh-opts))]
        {:public (access->map public)
         :authenticated authenticated
         :agents (agents->map agents)})
      (p/let [ds (sc/getSolidDatasetWithAcl url (auth/fresh-opts))]
        (if-let [acl (sc/getResourceAcl ds)]
          {:public (wac-access->map (sc/getPublicResourceAccess acl))
           :authenticated authenticated
           :agents (wac-agents->map (sc/getAgentResourceAccessAll acl))}
          ;; no ACL of its own is an answer, not a silence: nothing is
          ;; set here, and everything this resource allows is inherited
          {:public {:read false :append false :write false
                    :control-read false :control-write false}
           :authenticated nil
           :agents {}})))))

;; Writing access is shared with Comms; re-exported here so this app's
;; state layer keeps talking to one namespace about pods.
(def set-agent-access+ access/set-agent-access+)
(def set-public-access+ access/set-public-access+)
(def set-authenticated-access+ access/set-authenticated-access+)
(def set-inherited-access+ access/set-inherited-access+)

(defn agent-access+
  "One agent's access to one resource. Used to check whether a grant on
   a container actually reached the things inside it."
  [url webid]
  (p/let [result (.getAgentAccess ^js universal-access url webid (auth/fresh-opts))]
    (access->map result)))

;; ---------------------------------------------------------------------------
;; Access that reaches a container's contents
;;
;; Writing it lives in podbay.shared.access — Comms needs the same thing
;; to grant a follower read on one audience. What follows is the reading
;; side, which is this app's own: reporting what a resource's rules are,
;; and telling apart "we didn't write it" from "the server didn't honour
;; it".

(def ^:private acp-ns "http://www.w3.org/ns/solid/acp#")
(def ^:private acp-memberAccessControl (str acp-ns "memberAccessControl"))
(def ^:private acp-apply (str acp-ns "apply"))
(def ^:private acp-allow (str acp-ns "allow"))
(def ^:private acp-anyOf (str acp-ns "anyOf"))
(def ^:private acp-allOf (str acp-ns "allOf"))
(def ^:private acp-agent (str acp-ns "agent"))
(def ^:private acp-accessControl (str acp-ns "accessControl"))
(def ^:private acp-AuthenticatedAgent (str acp-ns "AuthenticatedAgent"))

;; ACP borrows WAC's vocabulary for the access modes themselves
(def ^:private acl-ns "http://www.w3.org/ns/auth/acl#")
(def ^:private mode-keys {(str acl-ns "Read") :read
                          (str acl-ns "Append") :append
                          (str acl-ns "Write") :write})

(defn- authenticated-access+
  "What anyone signed in may do with this resource.

   Read here rather than through the access API, which has no notion of
   an agent class: on ACP that means walking the ACR for a matcher
   carrying the AuthenticatedAgent sentinel, and on WAC finding an
   authorisation whose acl:agentClass is acl:AuthenticatedAgent.

   Offering to grant something the pane couldn't then display would be
   worse than not offering it, so this exists to keep the two halves
   honest."
  [url]
  (-> (p/let [acp? (.isAcpControlled acp url (auth/opts))]
        (if acp?
          (p/let [info (.getResourceInfoWithAcr acp url (auth/fresh-opts))]
            (when (.hasAccessibleAcr acp info)
              (p/let [acr-url (.getLinkedAcrUrl acp info)
                      acr (sc/getSolidDataset acr-url (auth/fresh-opts))]
                ;; every policy the resource applies, not its members
                (let [applies (->> (array-seq (sc/getThingAll acr))
                                   (mapcat #(array-seq
                                             (sc/getUrlAll % acp-accessControl)))
                                   distinct
                                   (keep #(sc/getThing acr %))
                                   (mapcat #(array-seq (sc/getUrlAll % acp-apply)))
                                   (keep #(sc/getThing acr %)))]
                  (->> applies
                       (filter (fn [policy]
                                 (->> (array-seq (sc/getUrlAll policy acp-anyOf))
                                      (keep #(sc/getThing acr %))
                                      (some (fn [m]
                                              (some #{acp-AuthenticatedAgent}
                                                    (array-seq
                                                     (sc/getUrlAll m acp-agent))))))))
                       (mapcat #(array-seq (sc/getUrlAll % acp-allow)))
                       (keep mode-keys)
                       (into {} (map (fn [k] [k true]))))))))
          (p/let [ds (sc/getSolidDatasetWithAcl url (auth/fresh-opts))]
            (when-let [acl (sc/getResourceAcl ds)]
              (access/wac-authenticated-modes acl url)))))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read authenticated access for" url e)
                 nil))))

(defn- policy-grants
  "One policy as {subject {:read true …}}, or nil if it grants nothing.
   Subjects are WebIDs, plus :public for a matcher naming everyone.

   Only anyOf is followed. allOf means *every* matcher must match, so a
   policy carrying one can't be read off as a simple list of people —
   rather than guess, such a policy is skipped and the caller says the
   rules are more complex than it can show. noneOf likewise."
  [acr policy]
  (let [modes (->> (array-seq (sc/getUrlAll policy acp-allow))
                   (keep mode-keys)
                   (into {} (map (fn [k] [k true]))))
        matchers (when (empty? (array-seq (sc/getUrlAll policy acp-allOf)))
                   (->> (array-seq (sc/getUrlAll policy acp-anyOf))
                        (keep #(sc/getThing acr %))))
        ;; "everyone" is not a separate predicate: it's acp:agent with a
        ;; sentinel IRI, so the agent list has to be sieved rather than
        ;; taken at face value. The other sentinels — AuthenticatedAgent,
        ;; CreatorAgent — are real ACP concepts this app doesn't write
        ;; and can't render, so they're dropped rather than shown as
        ;; though they were someone's WebID.
        named (->> matchers
                   (mapcat #(array-seq (sc/getUrlAll % acp-agent)))
                   (remove acp-sentinels))
        subjects (cond-> named
                   (some #(.hasPublic acp %) matchers) (conj :public))]
    (when (and (seq modes) (seq subjects))
      (into {} (map (fn [s] [s modes])) subjects))))

(defn parent-container
  "The container holding `url`, or nil at the top. Works for a container
   too — its parent is the one above it."
  [url]
  (let [trimmed (cond-> url (str/ends-with? url "/") (subs 0 (dec (count url))))
        cut (str/last-index-of trimmed "/")]
    (when (and cut (> cut (count "https:/")))
      (subs trimmed 0 (inc cut)))))

(defn ancestor-containers
  "Every container from the one holding `url` up to and including
   `root`, nearest first. Empty when `url` is the root or outside it —
   a pod is the top of the world here, and walking past it would mean
   asking a server about resources that aren't part of this storage."
  [url root]
  (when (and url root (str/starts-with? url root) (not= url root))
    (loop [u (parent-container url) out []]
      (if (and u (str/starts-with? u root) (>= (count u) (count root)))
        (if (= u root)
          (conj out u)
          (recur (parent-container u) (conj out u)))
        out))))

(defn member-access+
  "Who the *contents* of a container inherit access from it, read from
   the container's own access control resource.

   Returns {:agents {webid {…}}}, covering only the rules this ACR
   actually contains. ESS also links an ancestor's access controls by
   URL into that ancestor's own ACR rather than copying them; those are
   skipped here because the caller walks the ancestors anyway, and
   resolving them would visit the same rules twice.

   Assumes the pod is ACP; callers check once rather than per level."
  [url]
  (-> (p/let [;; resource *info* rather than the dataset: this only
              ;; needs the Link header naming the ACR, and on a
              ;; container the dataset would be the whole listing
              resource (.getResourceInfoWithAcr acp url (auth/fresh-opts))]
        (when (.hasAccessibleAcr acp resource)
          (p/let [acr-url (.getLinkedAcrUrl acp resource)
                  ;; fetched as a plain document: the parsed ACR inside
                  ;; `resource` is reachable only through internals
                  acr (sc/getSolidDataset acr-url (auth/fresh-opts))]
            ;; Found by looking for the predicate rather than by
            ;; assuming the document's subject is its own URL. Nothing
            ;; else carries acp:memberAccessControl, so this is exact,
            ;; and it doesn't depend on a naming convention that varies
            ;; between servers.
            ;; keep resolves only the access controls this document
            ;; defines; a link into an ancestor's ACR finds nothing here
            ;; and drops out, which is what we want
            {:agents (->> (array-seq (sc/getThingAll acr))
                          (mapcat #(array-seq
                                    (sc/getUrlAll % acp-memberAccessControl)))
                          distinct
                          (keep #(sc/getThing acr %))
                          (mapcat #(array-seq (sc/getUrlAll % acp-apply)))
                          (keep #(sc/getThing acr %))
                          (keep #(policy-grants acr %))
                          (apply merge-with merge))})))
      ;; one unreadable level must not blank the whole chain — being
      ;; refused control access on an ancestor is ordinary
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read member access for" url e)
                 {:error (or (some-> ^js e .-message) (str e))}))))

(defn- acl-subjects
  "An ACL's rules as {subject {…}}, with :public alongside the WebIDs,
   read through whichever pair of accessors the caller passes — the
   resource ones for what an ACL sets on its own resource, the default
   ones for what it hands down."
  [acl agents-of public-of]
  (let [agents (or (wac-agents->map (agents-of acl)) {})
        public (wac-access->map (public-of acl))]
    (cond-> agents
      (some true? (vals (select-keys public [:read :append :write])))
      (assoc :public public))))

(defn- wac-access-context+
  "WAC: what a container hands down, and what this resource inherits.

   Only one request, and no walk. acl:default is resolved by the server
   and by solid-client into a single *fallback* ACL — the nearest
   ancestor that has one — so unlike ACP there is no chain to follow
   and no per-level control access to be refused."
  [url]
  (-> (p/let [ds (sc/getSolidDatasetWithAcl url (auth/fresh-opts))]
        (let [own-acl (sc/getResourceAcl ds)
              fallback (sc/getFallbackAcl ds)]
          {:own (when (and own-acl (str/ends-with? url "/"))
                  {:agents (acl-subjects own-acl
                                         sc/getAgentDefaultAccessAll
                                         sc/getPublicDefaultAccess)})
           :inherited (when fallback
                        (let [agents (acl-subjects fallback
                                                   sc/getAgentDefaultAccessAll
                                                   sc/getPublicDefaultAccess)]
                          (when (seq agents)
                            ;; the ACL knows which resource it governs,
                            ;; which is the container to name here
                            [{:container (.-internal_accessTo ^js fallback)
                              :agents agents}])))}))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read inherited access for" url e)
                 nil))))

(defn access-context+
  "Everything the sharing pane needs that the access API can't report:

     :own        what this container grants its own contents (nil for a
                 file, which has no contents)
     :inherited  what `url` inherits from the containers above it,
                 nearest first, as [{:container :agents :error}]

   Only levels that grant something, or that couldn't be read, appear
   in :inherited — a chain of containers granting nothing isn't worth
   four lines of interface. Levels are read concurrently, since they're
   independent and doing them in turn would make opening a deep file
   noticeably slow.

   The one ACP check covers every level: a pod is one system or the
   other."
  [url root]
  (p/let [acp? (.isAcpControlled acp url (auth/opts))]
    (if acp?
      (p/let [containers (ancestor-containers url root)
              own (when (str/ends-with? url "/") (member-access+ url))
              results (p/all (map member-access+ containers))]
        {:own own
         :inherited (->> (map (fn [container result]
                                (assoc result :container container))
                              containers results)
                         (filter #(or (seq (:agents %)) (:error %)))
                         vec)})
      (wac-access-context+ url))))

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
    (sc/deleteContainer url (auth/opts))
    (sc/deleteFile url (auth/opts))))

(declare delete-tree+)

(defn- delete-contents+ [url]
  (p/let [entries (list-container+ url)
          _ (p/all (mapv delete-tree+ entries))]
    (sc/deleteContainer url (auth/opts))))

(defn delete-tree+
  "Delete a resource and, for a container, everything inside it —
   depth-first, because a server won't remove a container that still has
   children.

   There is no bulk delete in Solid: this is one request per resource,
   all the way down. On a deep tree that is a lot of requests, and a
   failure part way leaves whatever it already removed removed."
  [{:keys [url container?] :as entry}]
  (if container?
    (delete-contents+ url)
    (delete+ entry)))

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
  (sc/createContainerAt url (auth/opts)))

;; ---------------------------------------------------------------------------
;; Moving
;;
;; There is no MOVE in Solid: a move is a copy followed by a delete, in
;; that order deliberately — if the copy fails, the original is still
;; there. The cost is that an interrupted move of a container leaves some
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
    (sc/deleteFile from (auth/opts))))

(defn- move-container+ [from to]
  (p/let [_ (-> (sc/createContainerAt to (auth/opts))
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
    (sc/deleteContainer from (auth/opts))))

(defn move+
  "Move or rename a resource. Containers move their whole contents,
   depth-first, each child copied before its original is removed."
  [{:keys [url container?]} target]
  (if container?
    (move-container+ url target)
    (move-file+ url target)))
