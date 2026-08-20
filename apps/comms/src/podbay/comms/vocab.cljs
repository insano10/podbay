(ns podbay.comms.vocab
  "RDF vocabulary constants. Posts use ActivityStreams 2.0 so the data
   stays interoperable with other Solid social apps.")

(def ^:private as "https://www.w3.org/ns/activitystreams#")

(def as-Note (str as "Note"))
(def as-content (str as "content"))
(def as-published (str as "published"))
(def as-attachment (str as "attachment"))
(def as-attributedTo (str as "attributedTo"))

;; Mentioning someone in a post. as:tag points at an as:Mention — a
;; subtype of as:Link, so it carries as:href (who) and as:name (the text
;; as written). Deliberately *not* as:to: that is ActivityPub's delivery
;; and visibility list, and a post naming recipients there without
;; as:Public reads as a direct message. These posts are a bulletin
;; board, not a mailbox, and there is no delivery step to route.
(def as-tag (str as "tag"))
(def as-Mention (str as "Mention"))
(def as-href (str as "href"))
(def as-name (str as "name"))

(def as-generator (str as "generator"))
(def as-following (str as "following"))

(def ^:private foaf "http://xmlns.com/foaf/0.1/")
(def ^:private vcard "http://www.w3.org/2006/vcard/ns#")

;; Type indexes: how a pod advertises where each kind of data lives, so
;; apps discover containers instead of assuming container names.
(def ^:private solid "http://www.w3.org/ns/solid/terms#")

(def solid-publicTypeIndex (str solid "publicTypeIndex"))
(def solid-TypeRegistration (str solid "TypeRegistration"))
(def solid-forClass (str solid "forClass"))
(def solid-instanceContainer (str solid "instanceContainer"))
(def solid-instance (str solid "instance"))

(def ^:private dcterms "http://purl.org/dc/terms/")

;; The audience manifest names each container Comms created and what the
;; user calls it. No invented vocabulary: dcterms:title is exactly a
;; human label, and solid:instanceContainer already means "the container
;; holding these". Anything in that document carrying an
;; instanceContainer is an audience — the document's own path says what
;; kind of thing it holds, so the subjects need no type of their own.
(def dcterms-title (str dcterms "title"))

(def foaf-name (str foaf "name"))
(def foaf-img (str foaf "img"))
(def vcard-fn (str vcard "fn"))
(def vcard-hasPhoto (str vcard "hasPhoto"))
