(ns fnhouse.schemas
  "Defines a schema for HandlerInfo, fnhouse's API description format."
  (:require
   [plumbing.fnk.schema :as fnk-schema]
   [schema.core :as s]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Handlers

(s/defschema KeywordMap
  {s/Keyword s/Any})

(s/defschema Request
  "A Ring-style request, one input to a fnhouse handler"
  {:uri-args KeywordMap
   :query-params KeywordMap
   (s/optional-key :body) s/Any
   s/Any s/Any})

(s/defschema Response
  "A Ring-style response, the output of a fnhouse handler"
  {(s/optional-key :status) s/Int
   :body s/Any
   s/Any s/Any})

(s/defschema Handler
  (s/=> Response Request))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Handler metadata

(s/defschema ^:private Schema
  "A meta-schema that matches Schemas"
  (s/protocol s/Schema))

(s/defschema HandlerInfo
  "A schema for information about a specific HTTP handler."
  {;; HTTP-related info
   :path String
   :method (s/enum :get :head :post :put :delete)

   :description String

   :request {:body (s/maybe Schema)
             :query-params fnk-schema/InputSchema
             :uri-args {s/Keyword Schema}}
   :responses {(s/named s/Int "status code")
               (s/named Schema "response body schema")}

   ;; Additional metadata about the Clojure implementation
   :resources fnk-schema/InputSchema

   :source-map (s/maybe
                {:line s/Int
                 :column s/Int
                 :file String
                 :ns String
                 :name String})

   :annotations (s/named s/Any "user-specified")})

(s/defschema AnnotatedHandler
  "A bundle of a resource-injected fnhouse handler with its HandlerInfo"
  {:info HandlerInfo
   :handler Handler})

(s/defschema API [AnnotatedHandler])