(ns podbay.comms.mentions
  "Finding @mentions in post text.

   Mentions are stored as as:tag pointing at an as:Mention, which carries
   the WebID (as:href) and the literal text as written (as:name). The
   text is the source of truth: mentions are re-derived from the finished
   post when it's saved, rather than tracked while typing. Editing a
   sentence can't then leave the RDF describing a mention the text no
   longer contains — deleting half of '@Alice' simply means she isn't
   mentioned, which is what anyone would expect.

   Everything here is pure, so the same scan both extracts mentions on
   save and highlights them on render."
  (:require [clojure.string :as str]))

(defn- escape-regex [s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\$&"))

(defn- pattern
  "Matches any of the given labels where a mention could legitimately
   start and end.

   Labels are tried longest-first so '@Alice Smith' wins over '@Alice'
   — JS alternation takes the first branch that matches, not the
   longest, so the ordering is what makes it a longest match. The
   leading group keeps '@Alice' from being found inside an email
   address, and the trailing lookahead stops '@Alices' matching a
   contact called Alice."
  [labels]
  (js/RegExp. (str "(^|[^\\w@])("
                   (->> labels
                        (sort-by count >)
                        (map escape-regex)
                        (str/join "|"))
                   ")(?![\\w])")
              "g"))

(defn scan
  "Split `text` into a sequence of plain strings and mention maps.

   `candidates` are {:webid :label} — label being the mention as it
   appears in the text, '@' included. Anything not matching a candidate
   stays plain text, so a bare '@someone' the app doesn't know is left
   exactly as typed rather than being guessed at."
  [text candidates]
  (if (or (str/blank? text) (empty? candidates))
    (if (str/blank? text) [] [text])
    (let [by-label (into {} (map (juxt :label identity)) candidates)
          re (pattern (keys by-label))]
      (loop [out [] from 0]
        (if-let [m (.exec re text)]
          (let [;; the boundary character is part of the match but not of
                ;; the mention, so it belongs to the text before it
                start (+ (.-index m) (count (aget m 1)))
                label (aget m 2)]
            (recur (cond-> out
                     (> start from) (conj (subs text from start))
                     :always (conj (by-label label)))
                   (+ start (count label))))
          (cond-> out
            (< from (count text)) (conj (subs text from))))))))

(defn candidates
  "Mention candidates for the people you follow: {:webid :label}, where
   the label is their display name prefixed with '@'. Names with spaces
   are fine — matching only ever considers names we already know, so
   there's no need to guess where one ends."
  [webids name-of]
  (for [webid webids
        :let [n (name-of webid)]
        :when (seq n)]
    {:webid webid :label (str "@" n)}))

(defn extract
  "The distinct mentions actually present in `text`, in the order they
   appear. This is what gets written to the pod."
  [text candidates]
  (->> (scan text candidates)
       (remove string?)
       (distinct)
       (vec)))
