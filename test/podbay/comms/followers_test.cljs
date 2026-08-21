(ns podbay.comms.followers-test
  "The name of a follower's shared-with document. Both sides derive it
   from the same WebID and neither is told it, so the two derivations
   have to agree exactly — which is why WebIDs are never normalised.

   Percent-encoding was tried first and is unsafe: Community Solid
   Server decoded `%2F` when creating the resource while the ACL kept
   the name we asked for, leaving the file governed by an authorisation
   naming a URL that did not exist. Only RFC 3986's unreserved set is
   guaranteed to survive, because escaping those characters is defined
   as equivalent to not escaping them."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [podbay.comms.pod :as pod]))

(def css "https://alice.solidcommunity.net/profile/card#me")
(def ess "https://id.inrupt.com/alice")

(def ^:private webids
  [css ess
   "https://alice.example/"
   "https://alice.example/card"
   "https://alice.example/card#me"
   "https://A.example/card#me"
   "http://alice.example/card#me"
   "https://alice.example/a b/card#me"
   "https://alice.example/a_b/card#me"
   "https://\u00e9xample.test/card#me"])

(deftest only-characters-no-server-may-rewrite
  (doseq [webid webids]
    (let [f (pod/follower-filename webid)]
      (is (re-matches #"[A-Za-z0-9._~-]+" f) f)
      (is (not (re-find #"%" f)) (str "percent escape in " f)))))

(deftest a-single-path-segment
  (testing "or the server creates containers instead of a file"
    (doseq [webid webids]
      (is (not (re-find #"/" (pod/follower-filename webid))) webid))))

(deftest survives-url-parsing-untouched
  (doseq [webid webids]
    (let [f (pod/follower-filename webid)
          href (.-href (js/URL. (str "https://pod.example/shared-with/" f)))]
      (is (str/ends-with? href f) href))))

(deftest injective
  (testing "two WebIDs must never share a document — one follower's
            record overwriting another's would hand out the wrong access"
    (let [names (map pod/follower-filename webids)]
      (is (= (count names) (count (distinct names)))
          (str "collision among " (pr-str names)))))
  (testing "including the pairs a normaliser would conflate"
    (is (not= (pod/follower-filename "https://a.example/card")
              (pod/follower-filename "https://a.example/card#me")))
    (is (not= (pod/follower-filename "https://a.example/card#me")
              (pod/follower-filename "https://a.example/card#me/")))
    (is (not= (pod/follower-filename "https://A.example/card#me")
              (pod/follower-filename "https://a.example/card#me")))
    (is (not= (pod/follower-filename "http://a.example/card#me")
              (pod/follower-filename "https://a.example/card#me"))))
  (testing "and a literal underscore, or it would collide with an escape"
    (is (not= (pod/follower-filename "https://a.example/a_2Fb")
              (pod/follower-filename "https://a.example/a/b")))))

(deftest deterministic
  (testing "both sides derive it independently and must agree"
    (doseq [webid webids]
      (is (= (pod/follower-filename webid) (pod/follower-filename webid))))))

(deftest still-legible
  (testing "the point of not hashing it"
    (is (= "https_3A_2F_2Fid.inrupt.com_2Falice.ttl"
           (pod/follower-filename ess)))
    (is (str/includes? (pod/follower-filename ess) "id.inrupt.com"))
    (is (str/includes? (pod/follower-filename ess) "alice"))))
