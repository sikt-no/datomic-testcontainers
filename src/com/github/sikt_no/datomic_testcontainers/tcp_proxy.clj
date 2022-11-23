(ns com.github.sikt-no.datomic-testcontainers.tcp-proxy
  (:require [clojure.tools.logging :as log])
  (:import
    (clojure.lang Atom)
    (java.io BufferedInputStream BufferedOutputStream Closeable IOException InputStream OutputStream)
    (java.net InetSocketAddress ServerSocket Socket SocketTimeoutException)))

(comment
  (set! *warn-on-reflection* true))

(defn- close [^Closeable s]
  (when (and s (instance? Closeable s))
    (try
      (.close s)
      (catch IOException _
        nil))))

(defn- ports-str [^Socket sock]
  (str (.getLocalPort sock) "->" (.getPort sock)))

(defn- add-socket [sock typ state]
  (-> state
      (update-in [:socks typ] (fnil conj #{}) sock)))

(defn- del-socket [sock typ state]
  (-> state
      (update-in [:socks typ] (fnil disj #{}) sock)))

(defn- running? [state]
  (when state
    (:running? @state)))

(defmacro new-thread [id state typ sock f]
  `(let [state# ~state]
     (future
       (let [org-name# (.getName (Thread/currentThread))]
         (try
           (.setName (Thread/currentThread) (str "ThreadGroup-" ~id "-" (name ~typ)))
           (swap! state# (fn [old-state#] (update old-state# :threads (fnil conj #{}) (Thread/currentThread))))
           (let [sock# ~sock
                 f# ~f]
             (try
               (swap! state# (partial add-socket sock# ~typ))
               (f# sock#)
               (finally
                 (close sock#)
                 (swap! state# (partial del-socket sock# ~typ)))))
           (catch Throwable t#
             (log/error "Unhandled exception:" (ex-message t#))
             (swap! state# (fn [old-state#] (update old-state# :unhandled-exceptions (fnil conj #{}) t#))))
           (finally
             (log/info "Thread group" ~id ~typ "exiting")
             (swap! state# (fn [old-state#] (update old-state# :threads (fnil disj #{}) (Thread/currentThread))))
             (.setName (Thread/currentThread) org-name#)))))))

(defn- forward-byte! [state ^OutputStream out rd from]
  (let [w (try
            (.write out ^int rd)
            (.flush out)
            1
            (catch Exception e
              (when (running? state)
                (log/warn "Exception while writing" from "to socket:" (ex-message e)))
              -1))]
    (if (= 1 w)
      true
      nil)))

(defn- pump-byte! [^Atom state id typ ^InputStream inp out ^Socket src ^Socket dst]
  (let [rd (try
             (.read inp)
             (catch Throwable e
               (when (running? state)
                 (log/warn "Thread group" id typ "src" (ports-str src) "=>" (ports-str dst) "Exception while reading socket:" (ex-message e) "of type" (.getClass e)))
               -1))]
    (if (= -1 rd)
      nil
      (forward-byte! state out rd typ))))

(defn- pump! [id typ state ^Socket src ^Socket dst]
  (try
    (with-open [inp (BufferedInputStream. (.getInputStream src))
                out (BufferedOutputStream. (.getOutputStream dst))]
      (loop []
        (when (and (running? state) (not (.isClosed src)) (not (.isClosed dst)))
          (when (pump-byte! state id typ inp out src dst)
            (recur)))))
    (catch Exception e
      (if (running? state)
        (throw e)
        nil))))

(defn- deref-or-throw [derefable timeout-ms error-message]
  (let [res (deref derefable timeout-ms ::timeout)]
    (if (= res ::timeout)
      (do
        (log/error "timeout waiting for deref:" error-message)
        (throw (ex-info (str "timeout waiting for deref: " error-message) {:derefable derefable})))
      res)))

(defn- handle-connection! [id state ^Socket incoming]
  (let [{:keys [remote-host remote-port connection-timeout]
         :or   {connection-timeout 10000}} @state
        remote-host (deref-or-throw remote-host 10000 "Could not get remote-host")
        remote-port (deref-or-throw remote-port 10000 "Could not get remote-port")
        remote (try
                 (doto (Socket.)
                   (.connect (InetSocketAddress. ^String remote-host ^int remote-port) connection-timeout))
                 (catch SocketTimeoutException ste
                   (log/error "Timeout connection to" (str remote-host ":" remote-port))
                   (throw ste)))]
    (log/info "Thread group" id "proxying new incoming connection from" (ports-str incoming) "=>" (ports-str remote))
    (new-thread id state :send remote (fn [_] (pump! id :send state incoming remote)))
    (pump! id :recv state remote incoming)))

(defn- accept [state ^ServerSocket server]
  (try
    (.accept server)
    (catch Throwable e
      (when (running? state)
        (log/error "Error during .accept:" (ex-message e)))
      nil)))

(defn stop! [state]
  (swap! state assoc :running? false)
  (while (not-empty (:threads @state))
    (doseq [sock (get-in @state [:socks :server])]
      (close sock))
    (doseq [sock (get-in @state [:socks :recv])]
      (close sock))
    (doseq [sock (get-in @state [:socks :send])]
      (close sock))
    (Thread/sleep 10)))

(defn start! [state]
  (stop! state)
  (swap! state assoc
         :unhandled-exceptions #{}
         :running? true
         :id 0
         :handler-id 0
         :handlers {})
  (let [{:keys [bind port]
         :or   {bind "127.0.0.1"
                port 0}} @state
        throwable (promise)]
    (log/info "Starting TCP proxy")
    (let [local-port (promise)]
      (new-thread
        0
        state
        :server
        (try
          (let [ss (doto
                     (ServerSocket.)
                     (.setReuseAddress true)
                     (.bind (InetSocketAddress. ^String bind ^int port)))]
            (deliver local-port (.getLocalPort ss))
            ss)
          (catch Throwable e
            (deliver throwable e)
            (throw e)))
        (fn [^ServerSocket server]
          (deliver throwable nil)
          (while (running? state)
            (when-let [sock (accept state server)]
              (let [id (:id (swap! state update :id (fnil inc 0)))]
                (new-thread id state :recv sock (fn [sock] (handle-connection! id state sock))))))))
      (if-let [e @throwable]
        (throw e)
        (deref-or-throw local-port 10000 "Timeout waiting for local server to open")))))

(comment
  (def st (atom {:remote-host "localhost"
                 :remote-port 5432
                 :port        54321})))
