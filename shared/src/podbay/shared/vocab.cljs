(ns podbay.shared.vocab
  "RDF terms that aren't specific to either app — the generic web-of-data
   and LDP layer that anything talking to a pod needs. App-specific
   vocabularies (ActivityStreams, FOAF, vCard) live with the app that
   means something by them.")

(def rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

;; where a WebID profile points at the pod's storage root
(def pim-storage "http://www.w3.org/ns/pim/space#storage")

;; how a server describes a container and the things inside it
(def ldp-Container "http://www.w3.org/ns/ldp#Container")

;; Where to leave someone a notification. Linked Data Notifications:
;; anyone with Append may POST here, and Append is not Read — a sender
;; can leave a message and cannot read the inbox or anyone else's.
(def ldp-inbox "http://www.w3.org/ns/ldp#inbox")
(def dc-modified "http://purl.org/dc/terms/modified")
(def posix-size "http://www.w3.org/ns/posix/stat#size")
