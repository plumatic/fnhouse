(ns guesthouse.core-test
  (:use plumbing.core clojure.test guesthouse.core)
  (:require
   [schema.core :as s]
   [clj-http.client :as client]
   [cheshire.core :as cheshire]
   [guesthouse.schemas :as schemas]))

(def +port+
  "A random port on which to host the service"
  6054)

(def +base-url+
  (format "http://localhost:%s/guestbook/" +port+))

(defn http-post [url body]
  (client/post
   (str +base-url+ url)
   {:content-type :json
    :as :json
    :throw-exceptions false
    :body (cheshire/generate-string body)}))

(defn http-get [url & [qps]]
  (client/get
   (str +base-url+ url)
   (assoc-when
    {:as :json
     :throw-exceptions false}
    :query-params qps)))

(defn http-delete [url]
  (client/delete
   (str +base-url+ url)
   {:as :json
    :throw-exceptions false}))

(deftest guesthouse-test
  (let [guestbook (atom {})
        resources {:index (atom 0)
                   :guestbook guestbook}

        john-entry {:name "John Doe"
                    :age 31
                    :lang :clj}
        jane-entry {:name "Jane Doe"
                    :age 30
                    :lang :cljs}
        client-john-entry {:lang "clj"
                           :age 31
                           :index 1
                           :first-name "John"
                           :last-name "Doe"}
        client-jane-entry {:lang "cljs"
                           :age 30
                           :index 2
                           :first-name "Jane"
                           :last-name "Doe"}
        server (start-api resources {:port +port+ :join? false})]
    (try
      (testing "adding new entries"
        (is (= client-john-entry
               (:body (http-post "entries" john-entry))))

        (is (= (assoc john-entry :index 1)
               (safe-get @guestbook 1)))

        (is (= client-jane-entry
               (:body (http-post "entries" jane-entry)))))

      (testing "viewing existing entries"
        (is (= #{client-john-entry client-jane-entry}
               (set (:body (http-get "entries"))))))

      (testing "search for entries by name"
        (is (= [client-john-entry]
               (:body (http-get "search" {:q "John"}))))

        (is (= #{client-john-entry client-jane-entry}
               (set (:body (http-get "search" {:q "Doe"}))))))

      (testing "view an individual entry"
        (is (= client-john-entry
               (:body (http-get "entries/1")))))

      (testing "entry updates are reflected in the resources"
        (let [updated-john-entry (update-in john-entry [:age] inc)]
          (is (= (:body schemas/ack)
                 (:body (http-post "entries/1" updated-john-entry))))

          (is (= updated-john-entry (dissoc (get @guestbook 1) :index)))

          ;; update it back
          (is (= (:body schemas/ack) (:body (http-post "entries/1" john-entry))))))

      (testing "deleting entries"
        (assert (get @guestbook 1))
        (is (= (:body schemas/ack)
               (:body (http-delete "entries/1"))))

        (is (nil? (get @guestbook 1))))

      (testing "accessing non-existant entries"
        (is (= 404 (:status (http-post "entries/1234567" john-entry))))

        (is (= 404 (:status (http-delete "entries/123456")))))

      (finally (.stop server)))))
