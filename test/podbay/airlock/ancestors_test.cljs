(ns podbay.airlock.ancestors-test
  "Walking up a pod's container chain, which the sharing pane does to
   find what a resource inherits. Pure URL arithmetic, and easy to get
   off by one segment — particularly since the two pods this app is used
   against put their storage root in different places."
  (:require [cljs.test :refer [deftest is testing]]
            [podbay.airlock.pod :as pod]))

;; ESS puts a pod below the origin; CSS puts it at the origin root. The
;; walk has to stop at the root in both cases, never climb past it.
(def ess "https://storage.example/ce914d4c/")
(def css "https://jenny.example/")

(deftest parent-container-strips-one-segment
  (is (= "https://x.example/a/b/" (pod/parent-container "https://x.example/a/b/c.ttl")))
  (is (= "https://x.example/a/" (pod/parent-container "https://x.example/a/b/"))
      "a container's parent is the one above it, not itself")
  (is (= "https://x.example/" (pod/parent-container "https://x.example/c.ttl")))
  (is (nil? (pod/parent-container "https://x.example/"))
      "there is nothing above an origin root"))

(deftest ancestors-of-a-file
  (testing "a pod below the origin"
    (is (= [(str ess "social/posts/default-private/")
            (str ess "social/posts/")
            (str ess "social/")
            ess]
           (pod/ancestor-containers (str ess "social/posts/default-private/p.ttl") ess))
        "nearest first, up to and including the storage root"))
  (testing "a pod at the origin root"
    (is (= [(str css "social/posts/") (str css "social/") css]
           (pod/ancestor-containers (str css "social/posts/p.ttl") css)))))

(deftest ancestors-of-a-container
  (is (= [(str ess "social/posts/") (str ess "social/") ess]
         (pod/ancestor-containers (str ess "social/posts/default-private/") ess))))

(deftest the-walk-stops-at-the-storage-root
  (is (= [ess] (pod/ancestor-containers (str ess "notes.ttl") ess))
      "a pod is the top of the world here; above it is someone else's")
  ;; nil rather than [] — callers map over it, where the two behave alike
  (is (nil? (pod/ancestor-containers ess ess))
      "the root itself has no ancestors"))

(deftest anything-outside-the-root-is-refused
  (is (nil? (pod/ancestor-containers "https://elsewhere.example/a/b.ttl" ess))
      "walking a URL from another origin would ask the wrong server")
  (is (nil? (pod/ancestor-containers nil ess)))
  (is (nil? (pod/ancestor-containers (str ess "a.ttl") nil))))
