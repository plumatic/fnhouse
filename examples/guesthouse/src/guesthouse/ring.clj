(ns guesthouse.ring
  "A standard set of commonly used ring middleware"
  (:use plumbing.core)
  (:require
   [clojure.string :as str]
   [ring.middleware.json :as json]
   [ring.middleware.params :as params]))

(defn wrap-exception [f]
  (fn [request]
    (try (f request)
         (catch Exception e
           (.printStackTrace e)
           {:status 500
            :body "Exception caught"}))))

(defn keywordize-middleware [handler]
  (fn [req]
    (handler
     (update-in req [:query-params] keywordize-map))))

(defn ring-middleware [handler]
  (-> handler
      keywordize-middleware
      (json/wrap-json-body {:keywords? true})
      params/wrap-params
      json/wrap-json-response
      wrap-exception))
