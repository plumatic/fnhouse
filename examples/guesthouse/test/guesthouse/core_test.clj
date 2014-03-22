(ns guesthouse.core-test
  (:use plumbing.core clojure.test guesthouse.core)
  (:require
   [schema.core :as s]
   [clj-http.client :as client]
   [cheshire.core :as cheshire]
   [guesthouse.schemas :as schemas]))


;; TODO: split out supporting functions

;; TODO: add `testing` around the tests
(deftest guesthouse-test
  (let [guestbook (atom {})
        resources {:index (atom 0)
                   :guestbook guestbook}
        server (start-api {:resources resources})
        base-url "http://localhost:6054/guestbook/"
        do-post (fn [url body]
                  (client/post
                   (str base-url url)
                   {:content-type :json
                    :as :json
                    :throw-exceptions false
                    :body (cheshire/generate-string body)}))
        do-get (fn [url & [qps]]
                 (client/get
                  (str base-url url)
                  (assoc-when
                   {:as :json}
                   :query-params qps)))

        do-delete (fn [url]
                    (client/delete
                     (str base-url url)
                     {:as :json
                      :throw-exceptions false}))]
    (try ;; with-open
      (let [john-entry {:name "John Doe"
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
                               :last-name "Doe"}]
        (is (= client-john-entry
               (:body (do-post "entries" john-entry))))

        (is (= (assoc john-entry :index 1)
               (safe-get @guestbook 1)))

        (is (= client-jane-entry
               (:body (do-post "entries" jane-entry))))

        (is (= #{client-john-entry client-jane-entry}
               (set (:body (do-get "entries")))))

        (is (= [client-john-entry]
               (:body (do-get "search" {:q "John"}))))

        (is (= #{client-john-entry client-jane-entry}
               (set (:body (do-get "search" {:q "Doe"})))))

        (is (= client-john-entry
               (:body (do-get "entries/1"))))

        (testing "update entry"
          (let [updated-john-entry (update-in john-entry [:age] inc)]
            (is (= (:body schemas/ack)
                   (:body (do-post "entries/1" updated-john-entry))))

            (is (= updated-john-entry (dissoc (get @guestbook 1) :index)))

            ;; update it back
            (is (= (:body schemas/ack) (:body (do-post "entries/1" john-entry)))))

          (is (= 404 (:status (do-post "entries/1234567" john-entry)))))


        (testing "delete"
          (assert (get @guestbook 1))
          (is (= (:body schemas/ack)
                 (:body (do-delete "entries/1"))))

          (is (nil? (get @guestbook 1)))

          (is (= 404 (:status (do-delete "entries/123456"))))))

      (finally (.stop server)))))
