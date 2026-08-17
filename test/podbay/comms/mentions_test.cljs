(ns podbay.comms.mentions-test
  "The mention scanner is the one piece of Comms worth testing without a
   browser or a pod: it's pure, and the matching rules are fiddly enough
   to get subtly wrong — longest-match, where a mention may start and
   end, and what to do with a name it doesn't recognise."
  (:require [cljs.test :refer [deftest is testing]]
            [podbay.comms.mentions :as mentions]))

(def alice {:webid "https://alice.example/card#me" :label "@Alice"})
(def alice-smith {:webid "https://as.example/card#me" :label "@Alice Smith"})
(def bob {:webid "https://bob.example/card#me" :label "@Bob"})
(def candidates [alice alice-smith bob])

(defn- labels
  "A scan as plain strings, with mentions reduced to their labels, so a
   test can state the whole split in one literal."
  [segments]
  (mapv #(if (string? %) % (:label %)) segments))

(deftest scan-splits-text-around-mentions
  (is (= ["nothing here"] (labels (mentions/scan "nothing here" candidates)))
      "text with no mention is one segment, not a character soup")
  (is (= ["@Alice" " hello"] (labels (mentions/scan "@Alice hello" candidates))))
  (is (= ["hi " "@Bob" " ok"] (labels (mentions/scan "hi @Bob ok" candidates))))
  (is (= ["say hi to " "@Bob"] (labels (mentions/scan "say hi to @Bob" candidates))))
  (is (= ["@Alice" " and " "@Bob" " both"]
         (labels (mentions/scan "@Alice and @Bob both" candidates))))
  (is (= ["@Bob" ", hello"] (labels (mentions/scan "@Bob, hello" candidates)))
      "punctuation ends a mention")
  (is (= [] (labels (mentions/scan "" candidates))))
  (is (= ["@Alice hi"] (labels (mentions/scan "@Alice hi" [])))
      "with nobody to match, the text is left whole"))

(deftest scan-matches-the-longest-name
  (testing "names may contain spaces, so the longer one has to win"
    (is (= ["@Alice Smith" " hi"]
           (labels (mentions/scan "@Alice Smith hi" candidates)))))
  (testing "and the shorter still matches when the longer doesn't"
    (is (= ["@Alice" " hi"] (labels (mentions/scan "@Alice hi" candidates))))))

(deftest scan-respects-boundaries
  (is (= ["mail me@Alice.example"]
         (labels (mentions/scan "mail me@Alice.example" candidates)))
      "an @ inside an email address does not start a mention")
  (is (= ["@Alices are great"]
         (labels (mentions/scan "@Alices are great" candidates)))
      "a name must end where the text does, or @Alices would match @Alice"))

(deftest scan-leaves-unknown-handles-alone
  (is (= ["@Nobody here"] (labels (mentions/scan "@Nobody here" candidates)))
      "a mention has to resolve to a WebID, and guessing would be worse"))

(deftest scan-resolves-to-the-right-person
  (is (= ["https://as.example/card#me"]
         (mapv :webid (remove string? (mentions/scan "yo @Alice Smith" candidates))))))

(deftest extract-gives-what-was-actually-written
  (is (= [bob alice] (mentions/extract "@Bob then @Alice then @Bob" candidates))
      "distinct, in the order they appear — this is what reaches the pod")
  (is (= [] (mentions/extract "just text" candidates)))
  (is (= [] (mentions/extract "@Nobody" candidates))))

(deftest candidates-are-labelled-with-an-at-sign
  (is (= [{:webid "w1" :label "@Ada"}]
         (vec (mentions/candidates ["w1"] {"w1" "Ada"})))))
