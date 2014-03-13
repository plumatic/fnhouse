(ns fnhouse.handlers
  "Utilities for turning a set of fns into handlers.
   A fn is a handler iff it is a fn with methods in metadata.
   TODO

"
  (:use plumbing.core)
  (:require
   [clojure.string :as str]
   [schema.core :as s]
   [plumbing.fnk.pfnk :as pfnk]
   [plumbing.fnk.schema :as fnk-schema]
   [fnhouse.schemas :as schemas]
   [fnhouse.routes :as routes])
  (:import [clojure.lang Symbol Var]))


(def ^:dynamic ^String *path-separator*
  "The string to be used as a path separator in fnhouse fn names."
  "$")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private schemas

(s/defschema Resources
  "A map of external resoures to be injected into a fnhouse handler"
  schemas/KeywordMap)

(s/defschema AnnotatedProtoHandler
  "A bundle of a raw fnhouse handler with its HandlerInfo"
  {:info schemas/HandlerInfo
   :proto-handler (s/=> schemas/Response
                        {:request schemas/Request
                         :resources Resources})})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Extracting handler info from a fnhouse handler var

(s/defn ^:private path-and-method
  "Extract the path and method from the var name, or :path & :method
   overrides in metadata."
  [var :- Var]
  (let [var-name (-> var meta (safe-get :name) name)
        last-idx (.lastIndexOf var-name *path-separator*)]
    (merge
     {:path (-> var-name (subs 0 last-idx) (str/replace *path-separator* "/"))
      :method (-> var-name (subs (inc last-idx)) str/lower-case keyword)}
     (select-keys (meta var) [:path :method]))))

(s/defn ^:private source-map
  [var :- Var]
  (-> (meta var)
      (select-keys [:line :column :file :ns :name])
      (update-in [:name] name)
      (update-in [:ns] str)))

(defnk ^:private source-map->str [ns name file line]
  (format "%s/%s (%s:%s)" ns name file line))

(s/defn var->handler-info :- schemas/HandlerInfo
  "Extract the handler info for the function referred to by the specified var.
   Path and method override can be specified in metadata. TODO"
  [path-prefix :- String
   var :- Var
   extra-info-fn]
  (letk [[method path] (path-and-method var)
         [{doc ""} {responses {}}] (meta var)
         [{resources {}} {request {}}] (pfnk/input-schema @var)
         [{uri-args {}} {body nil} {query-params {}}] request]
    (let [source-map (source-map var)
          explicit-uri-args (dissoc uri-args s/Keyword)
          full-path (str path-prefix path)
          declared-args (set (routes/uri-arg-ks full-path)) ;; TODO: assert distinct
          undeclared-args (remove declared-args (keys explicit-uri-args))
          info {:path full-path
                :method method
                :description doc
                :request {:query-params query-params
                          :body body
                          :uri-args (merge
                                     (map-from-keys (constantly String) declared-args)
                                     explicit-uri-args)}
                :responses responses

                :resources resources
                :source-map source-map
                :annotations (extra-info-fn var)}]
      (when-let [error (s/check schemas/HandlerInfo info)]
        (throw (IllegalArgumentException. (format "%s in %s" error (source-map->str source-map)))))
      (fnk-schema/assert-iae
       (empty? undeclared-args)
       "Undeclared args %s in %s" (vec undeclared-args) (source-map->str source-map))
      (fnk-schema/assert-iae
       (or (not (boolean body)) (boolean (#{:post :put} method)))
       "Body only allowed in post or put method in %s" (source-map->str source-map))
      (fnk-schema/assert-iae
       (every? #{:resources :request s/Keyword} (keys (pfnk/input-schema @var)))
       "Disallowed non- :request or :resources bindings in %s: %s"
       (source-map->str source-map) (keys (pfnk/input-schema @var)))
      info)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Extracting ProtoHandlers and injecting resources to make them Handlers

(s/defn ^:private curry-resources :- (s/=> [schemas/AnnotatedHandler] Resources)
  "Take a sequence of AnnotatedProtoHandlers and return a function from resources
   to a set of normal AnnotatedHandlers with appropriate resources injected."
  [proto-handlers :- [AnnotatedProtoHandler]]
  (pfnk/fn->fnk
   (fn [resources]
     (for [proto-handler proto-handlers]
       (letk [[proto-handler info] proto-handler]
         {:info info
          :handler (pfnk/fn->fnk
                    (fn [request] (proto-handler {:request request :resources resources}))
                    (update-in (pfnk/io-schemata proto-handler) [0] :request {}))})))
   [(->> proto-handlers
         (map #(:resources (pfnk/input-schema (:proto-handler %)) {}))
         (reduce fnk-schema/union-input-schemata {}))
    schemas/API]))

(s/defn ^:private fnhouse-handler? [var :- Var]
  (and (fn? @var) (:responses (meta var))))

(s/defn ns->handler-fns :- [AnnotatedProtoHandler]
  "Take a path prefix and namespace, return a seq of all the functions
   can be converted to handlers in the namespace along with their handler info."
  [path-prefix :- String
   ns-sym :- Symbol
   extra-info-fn]
  (let [path-prefix (if (seq path-prefix) (str "/" path-prefix) "")]
    (for [var (vals (ns-interns ns-sym))
          :when (fnhouse-handler? var)]
      {:info (var->handler-info path-prefix var extra-info-fn)
       :proto-handler (pfnk/fn->fnk (fn redefable [m] (@var m))
                                    (pfnk/io-schemata @var))})))

(s/defn nss->handlers-fn :- (s/=> schemas/API Resources)
  "TODO.  path-prefix should be un-slashed."
  [prefix->ns-sym :- {(s/named String "path prefix")
                      (s/named Symbol "namespace")}
   & [extra-info-fn :- (s/=> s/Any Var)]]
  (->> prefix->ns-sym
       (mapcat (fn [[prefix ns-sym]] (ns->handler-fns prefix ns-sym (or extra-info-fn (constantly nil)))))
       curry-resources))
