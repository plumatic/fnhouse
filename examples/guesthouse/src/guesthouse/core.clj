(ns guesthouse.core
  (:use plumbing.core)
  (:require
   [clojure.string :as str]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.json :as json]
   [ring.middleware.params :as params]
   [fnhouse.handlers :as handlers]
   [fnhouse.middleware :as middleware]
   [fnhouse.routes :as routes]
   [guesthouse.guestbook :as guestbook]
   [guesthouse.schemas :as schemas]))

(set! *warn-on-reflection* true)

;; TODO: move ring/jetty stuff into its own file

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

(def entry-coercer
  (fn [schema]
    (when (= schema schemas/ClientEntry)
      (fn [request x]
        (let [[first last] (str/split (:name x) #" ")]
          (-> x
              (dissoc :name)
              (assoc :first-name first
                     :last-name last)))))))

(defn custom-coercion-middleware [handler]
  (middleware/coercion-middleware
   handler
   (constantly nil)
   entry-coercer))

(defn run-jetty [options handler]
  (jetty/run-jetty handler options))


;; TODO: make defn, options required
(defnk start-api
  [resources
   {options
    {:port 6054
     :join? false}}]
  (->> resources
       ((handlers/nss->handlers-fn {"guestbook" 'guesthouse.guestbook}))
       (map custom-coercion-middleware)
       routes/root-handler
       ring-middleware
       (run-jetty options)))
