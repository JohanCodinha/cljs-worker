(ns worker.hiccup
  "Minimal hiccup to HTML renderer for ClojureScript.
   Supports tag shorthand syntax like :div.class#id"
  (:require [clojure.string :as str]))

(def ^:private void-elements
  #{"area" "base" "br" "col" "embed" "hr" "img" "input"
    "link" "meta" "param" "source" "track" "wbr"})

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- parse-tag
  "Parse tag keyword like :div.class1.class2#id into [tag id classes]"
  [tag]
  (let [s (name tag)
        id-match (re-find #"#([^.#]+)" s)
        id (second id-match)
        classes (->> (re-seq #"\.([^.#]+)" s)
                     (map second)
                     (str/join " "))
        tag-name (first (str/split s #"[.#]"))]
    [(if (empty? tag-name) "div" tag-name)
     id
     (when-not (empty? classes) classes)]))

(defn- render-attrs [attrs id classes]
  (let [merged-class (str/trim (str classes " " (get attrs :class "")))
        merged-id (or id (get attrs :id))
        attrs' (cond-> (dissoc attrs :class :id)
                 (seq merged-class) (assoc :class merged-class)
                 merged-id (assoc :id merged-id))]
    (when (seq attrs')
      (->> attrs'
           (map (fn [[k v]]
                  (cond
                    (true? v) (name k)
                    (or (false? v) (nil? v)) nil
                    :else (str (name k) "=\"" (escape-html v) "\""))))
           (filter some?)
           (str/join " ")
           (str " ")))))

(declare html)

(defn- render-element [[tag & content]]
  (let [[tag-name id classes] (parse-tag tag)
        [attrs children] (if (map? (first content))
                           [(first content) (rest content)]
                           [nil content])
        void? (contains? void-elements tag-name)]
    (str "<" tag-name (render-attrs attrs id classes)
         (if void?
           ">"
           (str ">" (apply str (map html children)) "</" tag-name ">")))))

;; Type for raw HTML (unescaped)
(deftype RawHTML [s]
  Object
  (toString [_] s))

(defn raw
  "Wrap a string to be rendered without HTML escaping."
  [s]
  (RawHTML. s))

(defn html
  "Convert hiccup data structure to HTML string."
  [x]
  (cond
    (nil? x) ""
    (instance? RawHTML x) (str x)
    (string? x) (escape-html x)
    (number? x) (str x)
    (vector? x) (render-element x)
    (seq? x) (apply str (map html x))
    :else (escape-html (str x))))
