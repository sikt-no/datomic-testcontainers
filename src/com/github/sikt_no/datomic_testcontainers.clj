(ns com.github.sikt-no.datomic-testcontainers
  (:require [clj-test-containers.core :as tc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.sikt-no.datomic-testcontainers.tcp-proxy :as tcp-proxy]
            [datomic.api :as d])
  (:import
    (java.time Duration)
    (org.testcontainers.containers GenericContainer)
    (org.testcontainers.images.builder ImageFromDockerfile)))

(def datomic-version-default "1.0.6733")

(comment
  (set! *warn-on-reflection* true))

(defn- with-postgres [{:keys [postgres-password shutdown? network]
                       :or   {network (tc/create-network "postgresql-datomic")}
                       :as   opts} f]
  (log/info "Starting PostgreSQL ...")
  (let [postgres (-> (tc/create {:image-name      "postgres:13.2"
                                 :exposed-ports   [5432]
                                 :env-vars        {"POSTGRES_PASSWORD" postgres-password}
                                 :network         network
                                 :network-aliases ["pgsql"]})
                     (tc/start!))]
    (try
      (log/info "PostgreSQL running at" (:host postgres) (get (:mapped-ports postgres) 5432))
      (when f
        (f (merge opts {:network            network
                        :postgres-password  postgres-password
                        :postgres-container postgres})))
      (finally
        (when shutdown?
          (log/info "Shutting down PostgreSQL")
          (try
            (tc/stop! postgres)
            (catch Throwable t
              (log/warn t "Error during shutdown of PostgreSQL:" (ex-message t)))))))))

(defn- with-tcp-proxy [{:keys [shutdown?]
                        :as   opts}
                       f]
  (let [remote-host (promise)
        remote-port (promise)
        proxy-state (atom {:remote-host remote-host
                           :remote-port remote-port})
        _ (log/info "Starting TCP proxy ...")
        local-port (tcp-proxy/start! proxy-state)
        _ (log/info (str "TCP proxy bound to 127.0.0.1:" local-port))]
    (try
      (when f
        (f (merge {:proxy-port  local-port
                   :remote-host remote-host
                   :remote-port remote-port
                   :proxy-state proxy-state}
                  opts)))
      (finally
        (when shutdown?
          (log/info "Shutting down TCP proxy")
          (try
            (tcp-proxy/stop! proxy-state)
            (catch Throwable t
              (log/warn t "Error during shutdown of TCP proxy:" (ex-message t)))))))))

(defn- with-datomic [{:keys [postgres-password
                             datomic-version
                             delete-on-exit?
                             proxy-port
                             remote-host
                             remote-port
                             network
                             shutdown?]
                      :or   {network (tc/create-network "postgresql-datomic")}
                      :as   opts} f]
  (log/info "Building and starting datomic ...")
  (let [start-time (System/currentTimeMillis)
        docker-opts {:env-vars        {"POSTGRES_PASSWORD"   postgres-password
                                       "PGSQL_HOST"          "pgsql"
                                       "PGSQL_PORT"          "5432"
                                       "DATOMIC_PORT"        (str proxy-port)}
                     :network         network
                     :network-aliases ["datomic"]
                     :exposed-ports   [proxy-port]}
        prefix "no.sikt.datomic-testcontainers/"
        container (try
                    (as-> (ImageFromDockerfile. (str "no.sikt.datomic-testcontainers/datomic:" datomic-version) delete-on-exit?) $
                          (.withBuildArg $ "DATOMIC_VERSION" datomic-version)
                          (.withFileFromClasspath $ "Dockerfile" (str prefix "Dockerfile"))
                          (.withFileFromClasspath $ "init" (str prefix "init"))
                          (.withFileFromClasspath $ "transactor-template.properties" (str prefix "transactor-template.properties"))
                          (GenericContainer. ^ImageFromDockerfile $)
                          (assoc docker-opts :container $)
                          (tc/init $)
                          (tc/start! $))
                    (catch Throwable t
                      (log/error t "Could not start datomic container:" (ex-message t))
                      (throw t)))
        spent-time (- (System/currentTimeMillis) start-time)]
    (log/info "Built and started Datomic in" (str (Duration/ofMillis spent-time)))
    (log/debug "Updating TCP proxy. Host:" (:host container))
    (log/debug "Updating TCP proxy. Port:" (get (:mapped-ports container) proxy-port))
    (deliver remote-host (:host container))
    (deliver remote-port (int (get (:mapped-ports container) proxy-port)))
    (try
      (when f
        (f (assoc opts :datomic-container container)))
      (finally
        (when shutdown?
          (log/info "Shutting down Datomic")
          (try
            (tc/stop! container)
            (catch Throwable t
              (log/warn t "Error during shutdown of Datomic:" (ex-message t)))))))))

(defn- get-env [k]
  (let [v (or (System/getenv k)
              (System/getProperty k))]
    (when (and (string? v) (not-empty (str/trim v)))
      (str/trim v))))

(defonce ^:private sys (atom nil))

(defn- with-all [opts f]
  (with-postgres
    opts
    (fn [opts]
      (with-tcp-proxy
        opts
        (fn [opts]
          (with-datomic
            opts
            (fn [opts]
              (f opts))))))))

(defn get-uri
  ([]
   (get-uri {}))
  ([{:keys [datomic-version
            delete-on-exit?
            db-name
            postgres-password]
     :or   {datomic-version       (or (get-env "DATOMIC_VERSION") datomic-version-default)
            delete-on-exit?       (not (.exists (io/file ".nrepl-port")))
            db-name               "db"
            postgres-password     (or (get-env "POSTGRES_PASSWORD") (str (random-uuid)))}
     :as   opts}]
   (locking sys
     (if (nil? @sys)
       (do
         (log/info "Starting containers")
         (with-all
           (merge opts {:datomic-version       datomic-version
                        :delete-on-exit?       delete-on-exit?
                        :db-name               db-name
                        :postgres-password     postgres-password
                        :shutdown?             false})
           (fn [opts]
             (reset! sys opts))))
       (log/debug "Using cached system")))
   (let [{:keys [postgres-container postgres-password]} @sys]
     (str "datomic:sql://" db-name "?"
          "jdbc:postgresql://"
          (:host postgres-container) ":" (get (:mapped-ports postgres-container) 5432)
          "/postgres?user=postgres&password="
          postgres-password
          "&socketTimeout=30"))))

(defn get-conn
  ([] (get-conn {}))
  ([{:keys [delete?] :as opts}]
   (let [uri (get-uri opts)]
     (when (true? delete?)
       (d/delete-database uri))
     (d/create-database uri)
     (d/connect uri))))

(defn stop! []
  (locking sys
    (when (some? @sys)
      (try
        (when-let [datomic-container (:datomic-container @sys)]
          (try
            (tc/stop! datomic-container)
            (catch Throwable t
              (log/warn t "Error during shutdown of Datomic:" (ex-message t)))))
        (when-let [proxy-state (:proxy-state @sys)]
          (try
            (tcp-proxy/stop! proxy-state)
            (catch Throwable t
              (log/warn t "Error during shutdown of TCP proxy:" (ex-message t)))))
        (when-let [postgres-container (:postgres-container @sys)]
          (try
            (tc/stop! postgres-container)
            (catch Throwable t
              (log/warn t "Error during shutdown of PostgreSQL container:" (ex-message t)))))
        (finally
          (reset! sys nil)))))
  nil)
