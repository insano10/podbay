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
  "Entries of a container, containers first then files, each alphabetical."
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
  ;; every read here happens either just before or just after a write to
  ;; the very thing being read, so it must revalidate — a cached access
  ;; control resource would report the rules as they were, which reads as
  ;; "the change didn't work"
  (p/let [public (.getPublicAccess ^js universal-access url (fresh-opts))
          agents (.getAgentAccessAll ^js universal-access url (fresh-opts))]
    {:public (access->map public)
     :agents (agents->map agents)}))

(defn- ->js-access
  "Only the modes actually named are sent, so a change can adjust one
   permission without restating the others.

   Control is a *single* flag on the way in, deliberately. The universal
   API is modelled on ACP, which separates reading an access control
   resource from changing it; WAC has one acl:Control and solid-client
   throws outright if the two are set differently. Nothing here wants
   them to differ, so collapsing them makes the unsupported combination
   unrepresentable rather than merely avoided — the difference would
   otherwise work on an ESS pod and throw on a CSS one.

   Reading keeps them separate (see access->map): on ACP they genuinely
   can differ, and hiding that would misreport the pod."
  [m]
  (let [o #js {}]
    (when (contains? m :read) (aset o "read" (boolean (:read m))))
    (when (contains? m :append) (aset o "append" (boolean (:append m))))
    (when (contains? m :write) (aset o "write" (boolean (:write m))))
    (when (contains? m :control)
      (aset o "controlRead" (boolean (:control m)))
      (aset o "controlWrite" (boolean (:control m))))
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
  (p/let [result (.getAgentAccess ^js universal-access url webid (fresh-opts))]
    (access->map result)))

;; ---------------------------------------------------------------------------
;; Access that reaches a container's contents
;;
;; universalAccess sets access for one resource and says so plainly: "if
;; the Resource is a Container, the configured Access will not apply to
;; contained Resources". That makes sharing a container of posts useless
;; on its own — the reader is admitted to the container and refused every
;; file in it.
;;
;; Both servers do support inheritance, under different names and through
;; different APIs, so this is the one place the app can't paper over the
;; difference:
;;
;;   WAC (CSS)  an authorisation with acl:default in the container's .acl
;;   ACP (ESS)  a policy linked from the ACR as acp:memberAccessControl
;;
;; Either way it applies to what is *in* the container when a request is
;; made, not to what was there when the rule was written — so this covers
;; existing files and future ones alike, with no walk over the contents.

(def ^:private acp sc/acp_ess_2)

;; ACP's own vocabulary. Needed because solid-client offers no way to
;; read a *member* policy: getResourcePolicy insists the policy appear
;; in getPolicyUrlAll, which lists only a resource's own, and there is
;; no public accessor for the access control resource as a dataset. It
;; is an ordinary RDF document at a known URL, so the way to read what
;; it says is to fetch and walk it.
(def ^:private acp-ns "http://www.w3.org/ns/solid/acp#")
(def ^:private acp-memberAccessControl (str acp-ns "memberAccessControl"))
(def ^:private acp-apply (str acp-ns "apply"))
(def ^:private acp-allow (str acp-ns "allow"))
(def ^:private acp-anyOf (str acp-ns "anyOf"))
(def ^:private acp-allOf (str acp-ns "allOf"))
(def ^:private acp-agent (str acp-ns "agent"))

(def ^:private acp-sentinels
  "Values that appear in acp:agent but aren't anyone's WebID. ACP writes
   'everyone' as an agent with a magic IRI rather than a predicate of
   its own, so any list of agents has to be sieved before it can be
   shown as a list of people."
  #{(str acp-ns "PublicAgent")
    (str acp-ns "AuthenticatedAgent")
    (str acp-ns "CreatorAgent")})

;; ACP borrows WAC's vocabulary for the access modes themselves
(def ^:private acl-ns "http://www.w3.org/ns/auth/acl#")
(def ^:private mode-keys {(str acl-ns "Read") :read
                          (str acl-ns "Append") :append
                          (str acl-ns "Write") :write})

(def ^:private inheritable-modes
  "Control is deliberately absent. Handing someone the ability to rewrite
   the access rules of every file in a container is not something to do
   as a side effect of sharing it."
  [[:read "read"] [:append "append"] [:write "write"]])

(defn- public?
  "Is this change about everyone, rather than one named person? The
   subject of a grant is either a WebID or the keyword :public."
  [subject]
  (= :public subject))

(defn- wac-default-access+
  "WAC: an acl:default authorisation on the container, which is what its
   children inherit when they have no .acl of their own."
  [url subject access]
  ;; read-modify-write: a cached ACL would be rewritten over whatever the
  ;; server actually holds now
  (p/let [ds (sc/getSolidDatasetWithAcl url (fresh-opts))
          acl (or (sc/getResourceAcl ds)
                  ;; A container with no .acl of its own is governed by an
                  ;; ancestor's. Creating a blank one would silently drop
                  ;; those rules — including, quite possibly, your own
                  ;; control access — so seed it from what it inherits.
                  (if (sc/hasFallbackAcl ds)
                    (sc/createAclFromFallbackAcl ds)
                    (sc/createAcl ds)))
          ;; the setters replace all four modes at once, so the ones not
          ;; being changed have to be read back first
          current (js->clj (or (if (public? subject)
                                 (sc/getPublicDefaultAccess acl)
                                 (sc/getAgentDefaultAccess acl subject))
                               #js {})
                           :keywordize-keys true)
          merged (merge {:read false :append false :write false :control false}
                        current
                        (select-keys access [:read :append :write :control]))
          updated (if (public? subject)
                    (sc/setPublicDefaultAccess acl (clj->js merged))
                    (sc/setAgentDefaultAccess acl subject (clj->js merged)))]
    (sc/saveAclFor ds updated (opts))))

(defn- acp-member-mode
  "ACP: add or remove one agent for one mode, in a matcher and policy
   this app owns.

   One pair per mode, named so a later change finds what an earlier one
   wrote instead of accumulating policies. Modes stay independent, which
   matters because a caller names only the ones it means to change.

   `subject` is a WebID, or :public for everyone.

   Note the *Resource* variants throughout. `setPolicy`/`setMatcher` put
   the Thing in the dataset handed to them — the container's own data —
   whereas `setResourcePolicy`/`setResourceMatcher` put it in that
   resource's access control resource, which is the only part
   `saveAcrFor` writes. Getting this wrong produces an ACR that links a
   policy URL with no policy behind it: a rule that grants nothing,
   saved without complaint. The Resource variants also name Things
   relative to the ACR themselves, so no URL has to be built by hand.

   Agents and the public get **separate matchers**, both linked from the
   one policy by anyOf. They could share one — ACP writes 'everyone' as
   acp:agent with a sentinel IRI, so it's the same predicate — but then
   revoking a person and making a container private would be editing
   one list, and the emptiness test that decides whether the policy
   still constrains anything would have to distinguish the sentinel
   from a WebID. Two matchers keep the two questions apart, and anyOf
   across them is unambiguously 'either'."
  [resource mode subject allow?]
  (let [agent-name (str "podbay-member-" mode "-matcher")
        public-name (str "podbay-member-" mode "-public-matcher")
        policy-name (str "podbay-member-" mode "-policy")
        agent-m (or (.getResourceMatcher acp resource agent-name)
                    (.createResourceMatcherFor acp resource agent-name))
        public-m (or (.getResourceMatcher acp resource public-name)
                     (.createResourceMatcherFor acp resource public-name))
        ;; only the matcher this subject belongs to changes; the other is
        ;; carried through untouched so a public grant can't disturb the
        ;; people already named, or the reverse
        [agent-m public-m]
        (if (public? subject)
          ;; setPublic is addIri, so calling it twice writes the sentinel
        ;; twice — the same trap addAgent has
        [agent-m (cond
                     (not allow?) (.removePublic acp public-m)
                     (.hasPublic acp public-m) public-m
                     :else (.setPublic acp public-m))]
          ;; addAgent appends unconditionally, so granting twice would
          ;; list the same person twice — harmless to evaluate, but the
          ;; access control resource grows every time anyone re-shares
          [(cond
             (not allow?) (.removeAgent acp agent-m subject)
             (some #{subject} (array-seq (.getAgentAll acp agent-m))) agent-m
             :else (.addAgent acp agent-m subject))
           public-m])
        resource (->> agent-m (.setResourceMatcher acp resource))
        resource (->> public-m (.setResourceMatcher acp resource))
        ;; A matcher constraining nothing must never be linked: an anyOf
        ;; naming an empty matcher is a rule with nothing to test, and
        ;; the safe reading of that is not the one to gamble a pod on.
        live (cond-> []
               (seq (array-seq (.getAgentAll acp agent-m)))
               (conj (sc/asUrl agent-m))
               (.hasPublic acp public-m)
               (conj (sc/asUrl public-m)))
        ;; Built from scratch every time rather than read and amended.
        ;; The policy's whole content is one mode and its matchers, so
        ;; stating it outright is both simpler and idempotent —
        ;; setResourcePolicy replaces any previous instance. It also
        ;; sidesteps getResourcePolicy, which returns null for a policy
        ;; linked as a *member* policy however plainly it is there: it
        ;; requires the policy to be in getPolicyUrlAll, and that lists
        ;; only the resource's own.
        ;; With nobody left the policy is written granting nothing, and
        ;; unlinked below. It can't simply be deleted: removeResourcePolicy
        ;; goes through getResourcePolicy, which refuses a member policy
        ;; and returns the resource untouched — so the choice is between
        ;; leaving an "allow Read" behind and leaving an inert husk, and
        ;; the husk is the one that can't be misread.
        granting? (seq live)
        policy (-> (.createResourcePolicyFor acp resource policy-name)
                   (as-> p (.setAllowModes acp p
                                           #js {:read (boolean
                                                       (and granting? (= mode "read")))
                                                :append (boolean
                                                         (and granting? (= mode "append")))
                                                :write (boolean
                                                        (and granting? (= mode "write")))
                                                :controlRead false
                                                :controlWrite false}))
                   (as-> p (reduce #(.addAnyOfMatcherUrl acp %1 %2) p live)))
        policy-url (sc/asUrl policy)
        resource (.setResourcePolicy acp resource policy)]
    (if granting?
      (cond-> resource
        (not (some #{policy-url}
                   (array-seq (.getMemberPolicyUrlAll acp resource))))
        (as-> r (.addMemberPolicyUrl acp r policy-url)))
      (.removeMemberPolicyUrl acp resource policy-url))))

(defn- acp-member-access+ [url subject access]
  ;; read-modify-write, as above
  (p/let [resource (.getSolidDatasetWithAcr acp url (fresh-opts))]
    (if-not (.hasAccessibleAcr acp resource)
      (p/rejected (js/Error. (str "This pod uses ACP but won't let us read the "
                                  "access control resource for " url)))
      (let [acr-url (.getLinkedAcrUrl acp resource)
            updated (reduce (fn [res [k mode]]
                              (if (contains? access k)
                                (acp-member-mode res mode subject (get access k))
                                res))
                            resource
                            inheritable-modes)]
        (p/let [_ (.saveAcrFor acp updated (opts))]
          {:mechanism :acp :acr-url acr-url})))))

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
              resource (.getResourceInfoWithAcr acp url (fresh-opts))]
        (when (.hasAccessibleAcr acp resource)
          (p/let [acr-url (.getLinkedAcrUrl acp resource)
                  ;; fetched as a plain document: the parsed ACR inside
                  ;; `resource` is reachable only through internals
                  acr (sc/getSolidDataset acr-url (fresh-opts))]
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

   Resolves to nil on a pod that isn't ACP, where inheritance is
   acl:default and the access API already accounts for it. The one ACP
   check covers every level: a pod is one system or the other."
  [url root]
  (p/let [acp? (.isAcpControlled acp url (opts))]
    (when acp?
      (p/let [containers (ancestor-containers url root)
              own (when (str/ends-with? url "/") (member-access+ url))
              results (p/all (map member-access+ containers))]
        {:own own
         :inherited (->> (map (fn [container result]
                                (assoc result :container container))
                              containers results)
                         (filter #(or (seq (:agents %)) (:error %)))
                         vec)}))))

(defn set-inherited-access+
  "Set what `subject` — a WebID, or :public for everyone — may do with
   the *contents* of a container.

   Chooses the mechanism from the server rather than from configuration,
   since a pod is one or the other and only it can say which. Resolves
   to what it did, so the app can report the mechanism rather than
   leaving 'nothing appeared to happen' as the only observation."
  [url subject access]
  (p/let [acp? (.isAcpControlled acp url (opts))]
    (if acp?
      (acp-member-access+ url subject access)
      (p/let [_ (wac-default-access+ url subject access)]
        {:mechanism :wac}))))

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

(declare delete-tree+)

(defn- delete-contents+ [url]
  (p/let [entries (list-container+ url)
          _ (p/all (mapv delete-tree+ entries))]
    (sc/deleteContainer url (opts))))

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
  (sc/createContainerAt url (opts)))

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
  "Move or rename a resource. Containers move their whole contents,
   depth-first, each child copied before its original is removed."
  [{:keys [url container?]} target]
  (if container?
    (move-container+ url target)
    (move-file+ url target)))
