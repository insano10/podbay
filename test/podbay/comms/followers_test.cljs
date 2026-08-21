(ns podbay.comms.followers-test
  "The name of a follower's shared-with document. Both sides derive it
   from the same WebID and neither is told it, so the two derivations
   have to agree exactly — which is why WebIDs are never normalised."
  (:require [cljs.test :refer [deftest is testing]]
            [podbay.comms.pod :as pod]))

(def css "https://alice.solidcommunity.net/profile/card#me")
(def ess "https://id.inrupt.com/alice")

(deftest a-webid-round-trips
  (testing "so a follower finds the document its owner wrote"
    (doseq [webid [css ess
                   "https://alice.example/"
                   "https://alice.example/profile/card"
                   "https://éxample.test/card#me"
                   "https://alice.example/a b/card#me"]]
      (is (= webid (pod/filename->follower
                    (str "https://pod.example/podbay/comms/shared-with/"
                         (pod/follower-filename webid))))
          webid))))

(deftest the-filename-is-a-single-path-segment
  (testing "or it would silently create containers"
    (is (not (re-find #"/" (pod/follower-filename css)))))
  (is (= "https%3A%2F%2Fid.inrupt.com%2Falice.ttl" (pod/follower-filename ess))))

(deftest webids-that-differ-get-different-documents
  (testing "no normalising: these are different IRIs and may denote
            different things, so treating them alike would be wrong"
    (is (not= (pod/follower-filename "https://a.example/card")
              (pod/follower-filename "https://a.example/card#me")))
    (is (not= (pod/follower-filename "https://a.example/card#me")
              (pod/follower-filename "https://a.example/card#me/")))
    (is (not= (pod/follower-filename "https://A.example/card#me")
              (pod/follower-filename "https://a.example/card#me")))))
