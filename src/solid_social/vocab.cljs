(ns solid-social.vocab
  "RDF vocabulary constants. Posts use ActivityStreams 2.0 so the data
   stays interoperable with other Solid social apps.")

(def rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

(def ^:private as "https://www.w3.org/ns/activitystreams#")

(def as-Note (str as "Note"))
(def as-content (str as "content"))
(def as-published (str as "published"))
(def as-attachment (str as "attachment"))
(def as-attributedTo (str as "attributedTo"))
(def as-following (str as "following"))

(def ^:private foaf "http://xmlns.com/foaf/0.1/")
(def ^:private vcard "http://www.w3.org/2006/vcard/ns#")

;; where a WebID profile points at the pod's storage root
(def pim-storage "http://www.w3.org/ns/pim/space#storage")

(def foaf-name (str foaf "name"))
(def foaf-img (str foaf "img"))
(def vcard-fn (str vcard "fn"))
(def vcard-hasPhoto (str vcard "hasPhoto"))
