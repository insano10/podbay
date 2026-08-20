(ns podbay.comms.containers-test
  "Labelling a container in the composer. Audiences carry a name the
   user chose; anything else can only be described by its path, and the
   path is all we have."
  (:require [cljs.test :refer [deftest is testing]]
            [podbay.comms.pod :as pod]))

(deftest short-container-name-keeps-the-recognisable-tail
  (is (= "social/posts" (pod/short-container-name "https://pod.example/social/posts/")))
  (is (= "posts/a7f3c9" (pod/short-container-name "https://pod.example/social/posts/a7f3c9/"))
      "an opaque audience container is unreadable without its label — which
       is why the manifest exists")
  (testing "a file, not a container"
    (is (= "posts/p.ttl" (pod/short-container-name "https://pod.example/social/posts/p.ttl"))))
  (testing "shallow paths keep what there is"
    (is (= "posts" (pod/short-container-name "https://pod.example/posts/")))
    (is (= "" (pod/short-container-name "https://pod.example/"))))
  (testing "the origin is dropped — it's already obvious from the author"
    (is (= (pod/short-container-name "https://a.example/x/y/")
           (pod/short-container-name "https://b.example/x/y/"))))
  (testing "something that isn't a URL comes back untouched"
    (is (= "not a url" (pod/short-container-name "not a url")))))

(deftest audience-parent-is-the-containers-own-home
  (testing "so it is never offered as somewhere to file another audience"
    (is (= "https://pod.example/podbay/comms/posts/"
           (pod/audience-parent "https://pod.example/podbay/comms/posts/3e739645/"))))
  (testing "works without the trailing slash too"
    (is (= "https://pod.example/podbay/comms/posts/"
           (pod/audience-parent "https://pod.example/podbay/comms/posts/3e739645"))))
  (is (= "https://pod.example/" (pod/audience-parent "https://pod.example/x/"))))
