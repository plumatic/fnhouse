(ns fnhouse.routes-test
  (:use clojure.test plumbing.core)
  (:require
   [schema.core :as s]
   [schema.test :as schema-test]
   [fnhouse.handlers :as handlers]
   [fnhouse.routes :as routes]))

(defn fake-annotated-handlers [& paths-and-methods]
  (for [[path method] (partition 2 paths-and-methods)]
    {:handler nil
     :info {:path path :method method}}))

(deftest build-prefix-map-test
  (is (= {"a" {"b" {routes/+multiple-wildcard+ {:get {:handler nil :uri-arg-ks [:**]}
                                                :post {:handler nil :uri-arg-ks [:**]}}}
               routes/+single-wildcard+ {:post {:handler nil :uri-arg-ks [:asdfsd]}}}}
         (routes/build-prefix-map
          (fake-annotated-handlers
           "/a/b/:**" :get
           "/a/b/:**" :post
           "/a/:asdfsd/" :post))))
  (testing "duplicate routes"
    (is (thrown? Exception (routes/build-prefix-map
                            (fake-annotated-handlers "/a/:bsdf" :get "/a/:asdfsd/" :get)))))
  (testing "non-final wildcard"
    (is (thrown? Exception (routes/build-prefix-map (fake-annotated-handlers "/a/:**/:bsdf" :get))))))

(deftest prefix-lookup-test
  (let [leaf (fn [x] {:get x})
        result (fn [match-result value]
                 {:uri-args match-result
                  :leaf value})
        node {routes/+single-wildcard+ (leaf 7)
              "a" {routes/+multiple-wildcard+ (leaf 1)
                   "b" {"x" (leaf 2)
                        "w" (leaf 5)}

                   routes/+single-wildcard+
                   {"y" (leaf 3)
                    "z" (leaf 4)}}}
        lookup #(routes/prefix-lookup node % :get)]


    (testing "basic, no wildcards"
      (is (= (result [] 2)
             (lookup ["a" "b" "x"]))))

    (testing "lookup miss"
      (is (nil? (lookup ["absent" "key"]))))

    (testing "single-wildcard"
      (is (= (result ["bingo"] 3)
             (lookup ["a" "bingo" "y"]))))

    (testing "multiple wildcard"
      (is (= (result ["1/2/3"] 1)
             (lookup ["a" "1" "2" "3"]))))

    (testing "match consists of a single-wildcard"
      (is (= (result [""] 1)
             (lookup ["a"]))))

    (testing "prefer literal to wildcard"
      (is (= (result [] 5)
             (lookup ["a" "b" "w"]))))

    (testing "prefer single- to multiple-wildcard"
      (is (= (result ["bingo"] 3)
             (lookup ["a" "bingo" "y"])))
      (is (= (result ["bingo/multiple/y"] 1)
             (lookup ["a" "bingo" "multiple" "y"]))))))

(deftest split-path-test
  (is (= ["a" "b" "c"] (routes/split-path "/a/b//c//"))))

(deftest uri-arg-ks-test
  (is (= [] (routes/uri-arg-ks "a/x/y")))
  (is (= [:a :y] (routes/uri-arg-ks ":a/x/:y"))))

(deftest match-token-test
  (is (= ["a" routes/+single-wildcard+ "c"] (routes/match-tokens "a/:x/c")))
  (is (= ["a" routes/+multiple-wildcard+] (routes/match-tokens "a/:**")))
  (is (= ["y"] (routes/match-tokens "y"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Handlers

(defnk $GET
  "Root handler"
  {:responses {200 s/Any}}
  []
  {:body "You've hit the root"})

(defnk $a$:uri-arg$b$:**$GET
  {:responses {200 s/Any}}
  [[:request
    [:uri-args ** :- String uri-arg :- String]]]
  {:uri-arg uri-arg :wild-card **})

(defnk $x$:a$y$:b$POST
  {:responses {200 s/Any}}
  [[:request
    [:uri-args a :- String b :- String]]]
  {:a a :b b})


(deftest root-handler-test
  (let [svc-fn  (handlers/nss->handlers-fn {"test" 'fnhouse.routes-test})
        handlers (svc-fn {})
        h (routes/root-handler handlers)]

    (is (= {:body "You've hit the root"}
           (h {:request-method :get
               :uri "/test"})))

    (is (= {:uri-arg "1337" :wild-card "wild/card"}
           (h {:request-method :get
               :uri "/test/a/1337/b/wild/card"})))

    (is (= {:uri-arg "1337" :wild-card ""}
           (h {:request-method :get
               :uri "/test/a/1337/b/"})))

    (is (= {:a "a-match" :b "b match"}
           (h {:request-method :post
               :uri "/test/x/a-match/y/b%20match/"})))

    (is (= {:status 404 :body "Not found."}
           (h {:request-method :get
               :uri "this/does/not/exist"})))))

(use-fixtures :once schema-test/validate-schemas)