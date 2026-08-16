(ns podbay.comms.vocab
  "RDF vocabulary constants. Posts use ActivityStreams 2.0 so the data
   stays interoperable with other Solid social apps.")

(def ^:private as "https://www.w3.org/ns/activitystreams#")

(def as-Note (str as "Note"))
(def as-content (str as "content"))
(def as-published (str as "published"))
(def as-attachment (str as "attachment"))
(def as-attributedTo (str as "attributedTo"))
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

(def foaf-name (str foaf "name"))
(def foaf-img (str foaf "img"))
(def vcard-fn (str vcard "fn"))
(def vcard-hasPhoto (str vcard "hasPhoto"))
