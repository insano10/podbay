(ns podbay.shared.access
  "Writing access control, for both systems a pod might implement.

   Shared because two apps need it for different reasons: Airlock
   manages sharing as its declared purpose, and Comms grants a follower
   read on one audience. The rules about what may be granted live with
   each app; how to write it lives here.

   Reading access is *not* here. Airlock reports what a resource's rules
   are and diagnoses why a grant didn't reach something, which is
   file-browser work; Comms only ever needs to write."
  (:require ["@inrupt/solid-client" :as sc]
            [promesa.core :as p]
            [podbay.shared.auth :as auth]))

(def ^:private universal-access sc/universalAccess)

;; The ACP-specific API, needed wherever the universal one can't express
;; something — which turns out to be anything about a container's
;; contents.
(def ^:private acp sc/acp_ess_2)

(defn- access->map [^js a]
  (when a
    {:read (.-read a)
     :append (.-append a)
     :write (.-write a)
     :control-read (.-controlRead a)
     :control-write (.-controlWrite a)}))

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
                                  (->js-access access) (auth/opts))]
    (access->map result)))

(defn set-public-access+
  "Change what everyone — including people who aren't signed in — can do."
  [url access]
  (p/let [result (.setPublicAccess ^js universal-access url
                                   (->js-access access) (auth/opts))]
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

;; ACP's own vocabulary. Needed because solid-client offers no way to
;; read a *member* policy: getResourcePolicy insists the policy appear
;; in getPolicyUrlAll, which lists only a resource's own, and there is
;; no public accessor for the access control resource as a dataset. It
;; is an ordinary RDF document at a known URL, so the way to read what
;; it says is to fetch and walk it.
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
  (p/let [ds (sc/getSolidDatasetWithAcl url (auth/fresh-opts))
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
    (sc/saveAclFor ds updated (auth/opts))))

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
  (p/let [resource (.getSolidDatasetWithAcr acp url (auth/fresh-opts))]
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
        (p/let [_ (.saveAcrFor acp updated (auth/opts))]
          {:mechanism :acp :acr-url acr-url})))))

(defn set-inherited-access+
  "Set what `subject` — a WebID, or :public for everyone — may do with
   the *contents* of a container.

   Chooses the mechanism from the server rather than from configuration,
   since a pod is one or the other and only it can say which. Resolves
   to what it did, so the app can report the mechanism rather than
   leaving 'nothing appeared to happen' as the only observation."
  [url subject access]
  (p/let [acp? (.isAcpControlled acp url (auth/opts))]
    (if acp?
      (acp-member-access+ url subject access)
      (p/let [_ (wac-default-access+ url subject access)]
        {:mechanism :wac}))))

;; ---------------------------------------------------------------------------
;; Writing

