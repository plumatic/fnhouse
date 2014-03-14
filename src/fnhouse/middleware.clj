(ns fnhouse.middleware
  "Middleware for coercing and schema-validating inputs and outputs of the API."
  (:use plumbing.core)
  (:require
   [schema.coerce :as coerce]
   [schema.core :as s]
   [schema.utils :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private

(defn coercing-walker
  "Take a context key and walker, and produce a function that walks a datum and returns a
   successful walk or throws an error for a walk that fails validation."
  [context schema custom-matcher normal-matchers]
  (with-local-vars [request-ref ::missing] ;; used to pass request through to custom coercers.
    (let [walker (->> (cons
                       (fn [schema] (when-let [c (custom-matcher schema)] #(c @request-ref %)))
                       normal-matchers)
                      vec
                      coerce/first-matcher
                      (coerce/coercer schema))]
      (fn [request data]
        (let [res (with-bindings {request-ref request} (walker data))]
          (if-let [error (utils/error-val res)]
            (throw (ex-info
                    (format "Request: [%s]<BR>==> Error: [%s]<BR>==> Context: [%s]"
                            (pr-str (select-keys request [:uri :query-string :body]))
                            (pr-str error)
                            context)
                    {:type :schema-error
                     :error error}))
            res))))))

(defn request-walker
  "Given resources from the service and handler metadata, compile a
   function for coercing and validating inputs (uri-args,
   query-params, and body).  Coercion is extensible by defining an
   implementation of 'coercer' above for your function."
  [input-coercer handler-info]
  (let [request-walkers (for-map [[k coercer] {:uri-args coerce/string-coercion-matcher
                                               :query-params coerce/string-coercion-matcher
                                               :body coerce/json-coercion-matcher}
                                  :let [schema (safe-get-in handler-info [:request k])]
                                  :when schema]
                          k
                          (coercing-walker k schema input-coercer [coercer]))]
    (fn [request]
      (reduce-kv
       (fn [request request-key walker]
         (update-in request [request-key] (partial walker request)))
       request
       request-walkers))))

(defn response-walker
  "Given resources from the service (determined by keys asked for in v2 middleware)
   and handler metadata, compile a function for coercing and validating responses from
   this API method.  Coercion is extensible by defining an implementation of
   'coercer' above for your function."
  [output-coercer handler-info]
  (let [response-walkers (map-vals (fn [s] (coercing-walker :response s output-coercer nil))
                                   (safe-get handler-info :responses))]
    (fn [request response]
      (let [walker (safe-get response-walkers (response :status 200))]
        (update-in response [:body] (partial walker request))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn coercion-middleware
  "Coerce and validate inputs and outputs.  Use walkers to simultaneously coerce and validate
   inputs in a generous way (i.e., 1.0 in body will be cast to 1 and validate against a long
   schema), and outputs will be clientized to match the output schemas."
  [input-coercer output-coercer]
  (fnk [info :as annotated-handler]
    (let [request-walker (request-walker input-coercer info)
          response-walker (response-walker output-coercer info)]
      (update-in
       annotated-handler [:handler]
       (fn [handler]
         (fn [request]
           (let [walked-request (request-walker request)]
             (->> walked-request
                  handler
                  (response-walker walked-request)))))))))
