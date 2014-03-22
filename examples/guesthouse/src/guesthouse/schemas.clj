(ns guesthouse.schemas
  "Schemas and constructors that are used in the guesthouse"
  (:require [schema.core :as s]))

(s/defschema EntryData
  "Schema for guestbook entry"
  {:name String
   :age  Long
   :lang (s/enum :clj :cljs)})

(s/defschema Entry
  "A guestbook entry with an index"
  (assoc EntryData :index Long))

(s/defschema ClientEntry
  "Schema for a client representation of an entry"
  (-> Entry
      (dissoc :name)
      (assoc :first-name String)
      (assoc :last-name String)))

(s/defschema Ack
  "Simple acknowledgement for successful requests"
  {:message (s/eq "OK")})

(def ack
  {:status 200
   :body {:message "OK"}})

(s/defschema Missing
  {:message String})

(s/defn not-found
  [message :- String]
  {:status 404
   :body {:message message}})
