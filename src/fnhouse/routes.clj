(ns fnhouse.routes
  "TODO"
  (:use plumbing.core)
  (:require
   [clojure.string :as str]
   [schema.core :as s]
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
  [^String path]
  (map (fn [^String segment]
         (cond
          (= ":**" segment) +multiple-wildcard+
          (.startsWith segment ":") +single-wildcard+
          :else segment))
       (split-path path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private: building and matching in efficient routing trees

(defn build-prefix-map [annotated-handlers]
  (map/unflatten ;; TODO: assert-distinct, and multi-wildcard only in final position
   (for [annotated-handler annotated-handlers]
     (letk [[handler [:info path method]] annotated-handler]
       [(concat (match-tokens path) [method])
        {:handler handler
         :uri-arg-ks (uri-arg-ks path)}]))))

(s/defn prefix-lookup
  "Recursively looks up the specified path starting at the given node.
   If there is a handler located at the specified path,
    returns the handler and the matching path segments, grouping the multiple segments
    that match a multiple-wildcard into a single seq.
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
