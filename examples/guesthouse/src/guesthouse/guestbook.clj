(ns guesthouse.guestbook
  "A set of handlers for adding, viewing, searching for,
   and deleting guestbook entries"
  (:use plumbing.core)
  (:require
   [schema.core :as s]
   [clojure.string :as str]
   [guesthouse.schemas :as schemas]))

(set! *warn-on-reflection* true)

(defnk $entries$POST
  "Add a new entry to the guestbook"
  {:responses {200 schemas/ClientEntry}}
  [[:request body :- schemas/EntryData]
   [:resources guestbook index]]
  (let [entry-id (swap! index inc)
        indexed-entry (assoc body :index entry-id)]
    (swap! guestbook assoc entry-id indexed-entry)
    {:body indexed-entry}))

(defnk $entries$GET
  "View the current entries in the guestbook"
  {:responses {200 [schemas/ClientEntry]}}
  [[:resources guestbook]]
  {:body (vals @guestbook)})

(defnk $search$GET
  "Find enties by name"
  {:responses {200 [schemas/ClientEntry]}}
  [[:request [:query-params q :- String]]
   [:resources guestbook]]
  {:body
   (filter
    (fnk [name :- String]
      (.contains (str/lower-case name) (str/lower-case q)))
    (vals @guestbook))})

(defnk $entries$:entry-id$GET
  "Get the entry at the given id"
  {:responses {200 schemas/ClientEntry}}
  [[:request [:uri-args entry-id :- Long]]
   [:resources guestbook]]
  {:body (safe-get @guestbook entry-id)})

(defnk $entries$:entry-id$POST
  "Update the entry at the given id"
  {:responses {200 schemas/Ack
               404 schemas/Missing}}
  [[:request
    [:uri-args entry-id :- Long]
    body :- schemas/EntryData]
   [:resources guestbook]]
  (if-not (get @guestbook entry-id)
    (schemas/not-found (format "Entry %s not found" entry-id))
    (do (swap! guestbook assoc entry-id (assoc body :index entry-id))
        schemas/ack)))

(defnk $entries$:entry-id$DELETE
  "Delete the entry at the given id"
  {:responses {200 schemas/Ack
               404 schemas/Missing}}
  [[:request
    [:uri-args entry-id :- Long]]
   [:resources guestbook]]
  (let [[old new] (swap-pair! guestbook dissoc entry-id)]
    (if-not (get old entry-id)
      (schemas/not-found (format "Entry %s not found" entry-id))
      schemas/ack)))

(set! *warn-on-reflection* false)
