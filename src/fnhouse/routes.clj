(ns fnhouse.routes
  "A simple and efficient library for routing an API of fnhouse
   handlers (see fnhouse.handlers).  The sole entry point to the ns
   is the 'root-handler' fn.

   Paths can be concrete (simple strings to be matched exactly), or
   can contain one or more uri arguments specified with colons.

   For example, the path /a/b/c only matches the same literal URI, but
   the path /a/:b/c/:d can match any path with non-empty segments that
   don't contain slashes for :b and :c.  I.e., it can match
   /a/123/c/asdf but not /a/123/c/ or /a/b1/b2/c/d.  The segments
   matching uri arguments are inserted into the request as :uri-args,
   url-decoded for example {:b \"123\" :d \"asd f\"} for /a/123/c/as%20df.

   A path can also contain a single trailing 'wildcard' uri-arg, which
   can match any number of trailing segments in the uri.  For example,
   /a/:** can match /a, /a/b, or /a/b/c/d.  The wildcard match is
   included in the :uri-args in the request, e.g. {:** \"b/c/d\"},
   but is not url-decoded.

   Routing is performed with an efficient hierarchical algorithm,
   whose runtime is independent of the number of handler for exact
   matches, and can be much better than a linear traversal of all methods
   in almost every situation.

   If multiple handlers can match a URI, the precedence rules are
   specified hierarchically over segments. At each level, the lookup
   prioritizes literal matches over single-wildcards, and
   single-wildcards over multiple-wildcards.  The search will
   backtrack to try all possible matching routes."
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
  "Split a path into a sequence of segment tokens"
  [path :- String]
  (filter seq (str/split path #"/")))

(s/defn uri-arg-ks :- [s/Keyword]
  "Parse out the uri arg ks from a declared handler path"
  [^String path]
  (keep (fn [^String segment]
          (when (.startsWith segment ":")
            (keyword (subs segment 1))))
        (split-path path)))

(defn url-decode
  "Returns an UTF-8 URL encoded version of the given string."
  [^String encoded]
  (java.net.URLDecoder/decode encoded "UTF-8"))

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
  "Recursively looks up the most specific handler matching the request path and method."
  ([node path-segments request-method]
     (prefix-lookup node path-segments request-method []))
  ([node path-segments request-method uri-args]
     (or (if-let [[x & xs] (seq path-segments)]
           (or (when-let [subtree (get node x)]
                 (prefix-lookup subtree xs request-method uri-args))
               (when-let [subtree (get node +single-wildcard+)]
                 (prefix-lookup subtree xs request-method (conj uri-args (url-decode x)))))
           (when-let [handler (get node request-method)]
             {:uri-args uri-args
              :leaf handler}))
         (when-let [subtree (get node +multiple-wildcard+)]
           (prefix-lookup subtree nil request-method (conj uri-args (str/join "/" path-segments)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(s/defn root-handler
  "Takes a seq of handlers, returns a single handler that routes the request to the
   appropriate handler while binding :uri-args in the request to the appropriate
   path segments."
  [handlers :- [schemas/AnnotatedHandler]]
  (let [prefix-map (build-prefix-map handlers)]
    (fnk [uri request-method :as request]
      (if-let [found (prefix-lookup prefix-map (split-path uri) request-method)]
        (letk [[uri-args [:leaf handler uri-arg-ks]] found]
          (handler (assoc request :uri-args (zipmap uri-arg-ks uri-args))))
        {:status 404 :body "Not found."}))))
