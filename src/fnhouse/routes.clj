(ns fnhouse.routes
  "TODO"
  (:use plumbing.core)
  (:require
   [clojure.string :as str]
   [schema.core :as s]
   [plumbing.fnk.schema :as fnk-schema]
   [plumbing.map :as map]
   [fnhouse.schemas :as schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private: parsing handler match paths and client uris

(def +single-wildcard+
  "Matches a single route segment"
  ::*)

(def +multiple-wildcard+
  "Matches one or more route segments"
  ::**)

(s/defn split-path :- [String]
  [path :- String]
  (filter seq (str/split path #"/")))

(s/defn uri-arg-ks :- [s/Keyword]
  "Parse out the uri arg ks from a declared handler path"
  [^String path]
  (keep (fn [^String segment]
          (when (.startsWith segment ":")
            (keyword (subs segment 1))))
        (split-path path)))

(s/defn match-tokens :- [(s/either String (s/enum +single-wildcard+ +multiple-wildcard+))]
  "Parse a declared handler path into a token sequence for efficient route matching,
   where equivalent uri arg types are collapsed to a single token."
  [^String path]
  (map (fn [^String segment]
         (cond
          (= ":**" segment) +multiple-wildcard+
          (.startsWith segment ":") +single-wildcard+
          :else segment))
       (split-path path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private: building and matching in efficient routing trees

(defn build-prefix-map
  "Build a prefix map from a set of handlers, for efficient request routing via prefix-lookup."
  [annotated-handlers]
  (let [flat-entries (for [annotated-handler annotated-handlers]
                       (letk [[handler [:info path method]] annotated-handler]
                         [(concat (match-tokens path) [method])
                          {:handler handler
                           :uri-arg-ks (uri-arg-ks path)}]))
        dups (->> flat-entries (map first)
                  frequencies
                  (keep (fn [[path c]] (when (> c 1) path)))
                  seq)]
    (fnk-schema/assert-iae (empty? dups) "Multiple routes for pattern: %s" (vec dups))
    (doseq [path (map first flat-entries)]
      (fnk-schema/assert-iae (not (some #{+multiple-wildcard+} (drop-last 2 path)))
                             "Path contains non-terminal multiple wildcard: %s" path))
    (map/unflatten flat-entries)))

(s/defn prefix-lookup
  "Recursively looks up the specified path starting at the given node.
   If there is a handler located at the specified path,
    returns the handler and a seq of String path segments matching each uri-arg.
    Multiple-wildcards can only appear above the leaf.

   At each level, the lookup prioritizes literal matches over single-wildcards,
    and single-wildcards over multiple-wildcards.
    The search will backtrack to try all possible matching routes.
    Returns nil if no match is found."
  ([node path-segments request-method]
     (prefix-lookup node path-segments request-method []))
  ([node path-segments request-method uri-args]
     (if-let [[x & xs] (seq path-segments)]
       (or (when-let [subtree (get node x)]
             (prefix-lookup subtree xs request-method uri-args))
           (when-let [subtree (get node +single-wildcard+)]
             (prefix-lookup subtree xs request-method (conj uri-args x)))
           (when-let [subtree (get node +multiple-wildcard+)]
             (prefix-lookup subtree nil request-method (conj uri-args (str/join "/" path-segments)))))
       (when-let [handler (get node request-method)]
         {:uri-args uri-args
          :leaf handler}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(s/defn root-handler
  "Takes a seq of handlers, returns a single handler that routes the request, and processes
    uri arguments to the appropriate input handler based on the path."
  [handlers :- [schemas/AnnotatedHandler]]
  (let [prefix-map (build-prefix-map handlers)]
    (fnk [uri request-method :as request]
      (if-let [found (prefix-lookup prefix-map (split-path uri) request-method)]
        (letk [[uri-args [:leaf handler uri-arg-ks]] found]
          (handler (assoc request :uri-args (zipmap uri-arg-ks uri-args))))
        {:status 404 :body "Not found."}))))
