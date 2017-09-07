(ns pepa.db
  (:require [com.stuartsierra.component :as component]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [pepa.bus :as bus]
            [pepa.log :as log])
  (:import com.mchange.v2.c3p0.ComboPooledDataSource
           org.postgresql.util.PGobject))

(defrecord Database [config datasource]
  component/Lifecycle
  (start [component]
    (log/info "Starting database")
    (let [spec (:db config)
          cpds (doto (ComboPooledDataSource.)
                 (.setDriverClass "org.postgresql.Driver")
                 (.setJdbcUrl (.toASCIIString (java.net.URI. "jdbc:postgresql"
                                                             nil
                                                             (:host spec)
                                                             (:port spec)
                                                             (str "/" (:dbname spec))
                                                             nil
                                                             nil)))
                 (.setUser (:user spec))
                 (.setPassword (:password spec))
                 ;; TODO: Make configurable
                 (.setMaxIdleTimeExcessConnections (* 30 60))
                 (.setMaxIdleTime (* 3 60 60))
                 (.setCheckoutTimeout (* 5 1000)))]
      (assoc component :datasource cpds)))

  (stop [component]
    (log/info "Stopping database")
    (when datasource
      (log/info "Closing DB connection")
      (.close datasource))
    (assoc component :datasource nil)))

(defmethod clojure.core/print-method Database
  [db ^java.io.Writer writer]
  (.write writer (str "#<DatabaseConnection>")))

(defn make-component []
  (map->Database {}))

(def insert! jdbc/insert!)
(def update! jdbc/update!)
(def delete! jdbc/delete!)
(def query jdbc/query)

(def insert-multi! jdbc/insert-multi!)

(defmacro with-transaction [[conn db] & body]
  `(jdbc/with-db-transaction [~conn ~db]
     ~@body))

(defn placeholders [coll]
  (apply str (interpose ", " (map (constantly "?") coll))))

(defn sql+placeholders [sql-format-str coll]
  (assert (seq coll))
  (vec (cons (format sql-format-str (placeholders coll)) coll)))

(def advisory-lock-prefix 1952539)

(def advisory-locks
  [:files/new
   :pages/new
   :documents/new

   :files/updated
   :pages/updated
   :documents/updated

   :inbox/added
   :inbox/removed])

(defn make-advisory-lock-query-fn [lock-fn]
  (let [statement (format "SELECT %s(?::int, ?::int)" lock-fn)]
    (fn [db lock-name]
      (let [idx (.indexOf advisory-locks lock-name)]
        (assert (not= -1 idx) (str "Need to have an advisory-lock for topicN " lock-name))
        (query db [statement
                   advisory-lock-prefix
                   idx])))))

(def advisory-xact-lock!
  (make-advisory-lock-query-fn "pg_advisory_xact_lock"))

(def advisory-xact-lock-shared!
  (make-advisory-lock-query-fn "pg_advisory_xact_lock_shared"))

(defn notify!
  ([db topic data]
   (advisory-xact-lock! db topic)
   (bus/notify! (:bus db) topic data))
  ([db topic]
   (notify! db topic nil)))

;;; Extend namespaced keywords to map to PostgreSQL enums

(extend-type clojure.lang.Keyword
  jdbc/ISQLValue
  (sql-value [kw]
    (let [ns (-> (namespace kw)
                 (s/replace "-" "_"))
          name (name kw)]
      (doto (PGobject.)
        (.setType ns)
        (.setValue name)))))

(def +schema-enums+
  "A set of all PostgreSQL enums in schema.sql. Used to convert
  enum-values back into Clojure keywords."
  #{"processing_status" "entity"})

(extend-type String
  jdbc/IResultSetReadColumn
  (result-set-read-column [val rsmeta idx]
    (let [type (.getColumnTypeName rsmeta idx)]
      (if (contains? +schema-enums+ type)
        (keyword (s/replace type "_" "-")
                 val)
        val))))
