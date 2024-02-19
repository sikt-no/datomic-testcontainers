# datomic-testcontainers

Run a Datomic on-premise Pro transactor as a container in your tests/REPL.

Uses [clj-test-containers](https://github.com/javahippie/clj-test-containers) / [testcontainers](https://www.testcontainers.org/).

## Installation

```
com.github.sikt-no/datomic-testcontainers {:git/tag "0.1.5" :git/sha "09238a6"}
```

## Prerequisites

You will need a [Docker-API compatible container runtime](https://www.testcontainers.org/supported_docker_environment/).

## 1-minute example

```clojure
(require '[com.github.sikt-no.datomic-testcontainers :as dtc])

(def my-conn (dtc/get-conn))
;2022-11-23T10:41:00.682Z INFO - Starting containers
;2022-11-23T10:41:00.707Z INFO - Starting PostgreSQL ...
; ...
; 2022-11-23T10:41:16.891Z INFO - Built and started Datomic in PT13.431S
; ...
; 2022-11-23T10:41:21.059Z INFO - {:event :peer/cache-connection, :protocol :sql, :db-name "db", :system-root "jdbc:postgresql://localhost:49240/postgres", :db-id "db-3fb9f35a-2483-4d49-9d9f-df9255f2259f", :pid 261899, :tid 21}
=> #'my-conn

; You can also get a DB uri:
(dtc/get-uri)
=> "datomic:sql://db?jdbc:postgresql://localhost:49240/postgres?user=postgres&password=...&socketTimeout=30"

; Get a different DB:
(dtc/get-uri {:db-name "my-db"})
=> "datomic:sql://my-db?jdbc:postgresql://localhost:49240/postgres?user=postgres&password=...&socketTimeout=30"

; `dtc/get-uri` and `dtc/get-conn` take the same (optional) argument.

; dtc/get-conn also allows you to delete a database before a
; connection is returned, which may be convenient when writing tests:
(deftest my-test
  (let [conn (dtc/get-conn {:db-name "my-test" :delete? true})]
    ; Do stuff with a clean db
    ))

; Should you need to stop everything manually, you can do:
(do
  ; Make sure connections are closed first:
  (datomic.api/release my-conn)
  (datomic.api/release (dtc/get-conn {:db-name "my-db"}))
  (dtc/stop!))

; PS: testcontainers will remove started containers upon JVM exit.
```

### All configuration options (and their defaults)

```clojure
(defn- get-env [k]
  (let [v (or (System/getenv k)
              (System/getProperty k))]
    (when (and (string? v) (not-empty (str/trim v)))
      (str/trim v))))

{datomic-version       (or (get-env "DATOMIC_VERSION") "1.0.7075")
 
 ; Remove container build cache on exit:
 delete-on-exit?       (not (.exists (io/file ".nrepl-port")))
 
 ; Only for get-conn:
 ; delete database before returning connection
 delete?               false
 
 db-name               "db"
 postgres-password     (or (get-env "POSTGRES_PASSWORD") (str (random-uuid)))
}
```

## Why?
Why only test or develop on an in-memory transactor when you will be 
using a remote transactor in actual production?

There are some [subtle differences between an in-memory transactor and a remote transactor](https://github.com/ivarref/gen-fn#note-fressian-serialization-and-deserialization).

## Related libraries and resources

If you liked this library, you may also like:

* [clj-test-containers](https://github.com/javahippie/clj-test-containers): A lightweight wrapper around the [Testcontainers Java library](https://www.testcontainers.org/).
* [clojure-graph-resources](https://github.com/simongray/clojure-graph-resources#datalog): A collection of Clojure graphql resources, including Datalog.
* [conformity](https://github.com/avescodes/conformity): A Clojure/Datomic library for idempotently transacting norms into your database – be they schema, data, or otherwise.
* [datomic-schema](https://github.com/ivarref/datomic-schema): Simplified writing of Datomic schemas (works with conformity).
* [double-trouble](https://github.com/ivarref/double-trouble):  Handle duplicate Datomic transactions with ease.
* [gen-fn](https://github.com/ivarref/gen-fn): Generate Datomic function literals from regular Clojure namespaces.
* [rewriting-history](https://github.com/ivarref/rewriting-history): A library to rewrite Datomic history.
* [yoltq](https://github.com/ivarref/yoltq): An opinionated Datomic queue for building (more) reliable systems.

## Making a new release

Go to [https://github.com/sikt-no/datomic-testcontainers/actions/workflows/release.yml](https://github.com/sikt-no/clj-jwt/actions/workflows/release.yml)
and press `Run workflow`.

## License

Copyright © 2022 Sikt - Norwegian Agency for Shared Services in Education and Research

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
