(ns fnhouse.docs-test
  (:use plumbing.core clojure.test)
  (:require
   [schema.test :as schema-test]
   [fnhouse.docs :as docs]
   [fnhouse.handlers :as handlers]
   fnhouse.routes-test))

(deftest docs-smoke-test
  (is (docs/all-docs
       (map #(safe-get % :info)
            ((handlers/nss->handlers-fn
              {"test" 'fnhouse.routes-test})
             {})))))

(use-fixtures :once schema-test/validate-schemas)