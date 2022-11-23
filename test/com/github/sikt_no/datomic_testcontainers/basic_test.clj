(ns com.github.sikt-no.datomic-testcontainers.basic-test
  (:require [clojure.test :refer [deftest is]]
            [com.github.sikt-no.datomic-testcontainers :as dtc]
            [datomic.api :as d]))

(deftest demo
  (let [conn (dtc/get-conn {:db-name "demo"})
        _ @(d/transact conn [#:db{:ident :e/id, :cardinality :db.cardinality/one, :valueType :db.type/string, :unique :db.unique/identity}
                             #:db{:ident :e/info, :cardinality :db.cardinality/one, :valueType :db.type/string}])
        {:keys [db-after]} @(d/transact conn [{:e/id "1" :e/info "Hello world!"}])]
    (is (= #:e{:id "1" :info "Hello world!"}
           (d/pull db-after [:e/id :e/info] [:e/id "1"])))))
