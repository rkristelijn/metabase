(ns metabase.test.data.bigquery-cloud-sdk
  (:require
   [clojure.string :as str]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.driver.bigquery-cloud-sdk :as bigquery]
   [metabase.driver.ddl.interface :as ddl.i]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.registry :as mr])
  (:import
   (com.google.cloud.bigquery
    BigQuery
    BigQuery$DatasetDeleteOption
    BigQuery$DatasetListOption
    BigQuery$DatasetOption
    BigQuery$TableListOption
    BigQuery$TableOption
    Dataset
    DatasetId
    DatasetInfo
    Field
    Field$Mode
    InsertAllRequest
    InsertAllRequest$RowToInsert
    InsertAllResponse
    LegacySQLTypeName
    Schema
    StandardTableDefinition
    TableId
    TableInfo)))

(set! *warn-on-reflection* true)

(sql.tx/add-test-extensions! :bigquery-cloud-sdk)

(defonce ^:private ^{:arglists '(^java.lang.Long [])} ^{:doc "Timestamp to use for unique dataset identifiers. Initially
  this is the UNIX timestamp in milliseconds of when this namespace was loaded; it refreshes every two hours thereafter.
  Datasets with a timestamp older than two hours will get automatically cleaned up."} dataset-timestamp
  (let [timestamp* (atom (System/currentTimeMillis))]
    (fn []
      (when (t/before? (t/instant ^Long @timestamp*)
                       (t/minus (t/instant) (t/hours 2)))
        (reset! timestamp* (System/currentTimeMillis)))
      @timestamp*)))

;;; ----------------------------------------------- Connection Details -----------------------------------------------

(defn normalize-name
  "Returns a normalized name for a test database or table"
  [identifier]
  (str/replace (name identifier) "-" "_"))

(mr/def ::dataset-id
  [:and
   [:string {:min 1, :max 1024}]
   [:re
    {:error/message "Dataset IDs must be alphanumeric (plus underscores)"}
    #"^[\w_]+$"]])

(mu/defn test-dataset-id :- ::dataset-id
  "All databases created during test runs by this JVM instance get a suffix based on the timestamp from when this
  namespace was loaded. This dataset will not be deleted after this test run finishes, since there is no reasonable
  hook to do so (from this test extension namespace), so instead we will rely on each run cleaning up outdated,
  transient datasets via the [[transient-dataset-outdated?]] mechanism."
  ^String [database-name :- :string]
  (let [s (normalize-name database-name)]
    (str "v4_" s "__transient_" (dataset-timestamp))))

(defn- test-db-details []
  (reduce
   (fn [acc env-var]
     (assoc acc env-var (tx/db-test-env-var :bigquery-cloud-sdk env-var)))
   {}
   [:project-id :service-account-json]))

(defn- bigquery
  "Get an instance of a `Bigquery` client."
  ^BigQuery []
  (#'bigquery/database-details->client (test-db-details)))

(defn execute-respond [_ rows]
  (into [] rows))

(defn project-id
  "BigQuery project ID that we're using for tests, either from the env var `MB_BIGQUERY_TEST_PROJECT_ID`, or if that is
  not set, from the BigQuery client instance itself (which ultimately comes from the value embedded in the service
  account JSON)."
  ^String []
  (let [details (test-db-details)
        bq      (bigquery)]
    (or (:project-id details) (.. bq getOptions getProjectId))))

(defmethod tx/dbdef->connection-details :bigquery-cloud-sdk
  [_driver _context {:keys [database-name]}]
  (assoc (test-db-details)
         :dataset-filters-type "inclusion"
         :dataset-filters-patterns (test-dataset-id database-name)
         :include-user-id-and-hash true))

;;; -------------------------------------------------- Loading Data --------------------------------------------------

(mu/defmethod sql.tx/qualified-name-components :bigquery-cloud-sdk
  ([_driver db-name]
   [(test-dataset-id db-name)])

  ([_driver
    db-name    :- :string
    table-name :- :string]
   [(test-dataset-id db-name) table-name])

  ([_driver
    db-name    :- :string
    table-name :- :string
    field-name :- :string]
   [(test-dataset-id db-name) table-name field-name]))

(defmethod ddl.i/format-name :bigquery-cloud-sdk
  [_driver table-or-field-name]
  (str/replace table-or-field-name #"-" "_"))

(mu/defn- create-dataset! [^String dataset-id :- ::dataset-id]
  (.create (bigquery) (DatasetInfo/of (DatasetId/of (project-id) dataset-id)) (u/varargs BigQuery$DatasetOption))
  (log/info (u/format-color 'blue "Created BigQuery dataset `%s.%s`." (project-id) dataset-id)))

(defn- destroy-dataset! [^String dataset-id]
  {:pre [(seq dataset-id)]}
  (.delete (bigquery) dataset-id (u/varargs
                                   BigQuery$DatasetDeleteOption
                                   [(BigQuery$DatasetDeleteOption/deleteContents)]))
  (log/infof "Deleted BigQuery dataset `%s.%s`." (project-id) dataset-id))

(defn execute!
  "Execute arbitrary (presumably DDL) SQL statements against the test project. Waits for statement to complete, throwing
  an Exception if it fails."
  [format-string & args]
  (driver/with-driver :bigquery-cloud-sdk
    (let [sql (apply format format-string args)]
      (log/infof "[BigQuery] %s\n" sql)
      (flush)
      (#'bigquery/execute-bigquery execute-respond (test-db-details) sql [] nil))))

(mu/defn- delete-table!
  [dataset-id :- ::lib.schema.common/non-blank-string
   table-id   :- ::lib.schema.common/non-blank-string]
  (.delete (bigquery) (TableId/of dataset-id table-id))
  (log/error (u/format-color 'red "Deleted table `%s.%s.%s`" (project-id) dataset-id table-id)))

(defn base-type->bigquery-type [base-type]
  (let [types {:type/BigInteger     :INTEGER
               :type/Boolean        :BOOLEAN
               :type/Date           :DATE
               :type/DateTime       :DATETIME
               :type/DateTimeWithTZ :TIMESTAMP
               :type/Decimal        :BIGNUMERIC
               :type/Dictionary     :RECORD
               :type/Float          :FLOAT
               :type/Integer        :INTEGER
               :type/Text           :STRING
               :type/Time           :TIME}]
    (or (get types base-type)
        (some base-type->bigquery-type (parents base-type)))))

;; Fields must contain only letters, numbers, spaces, and underscores, start with a letter or underscore, and be at most 128
;; characters long.
(def ^:private ValidFieldName
  [:re #"^[A-Za-z_](\w| ){0,127}$"])

(mu/defn- valid-field-name :- ValidFieldName
  ^String [field-name]
  field-name)

(defn- field-definitions->Fields [field-definitions]
  (into
   []
   (map (fn [{:keys [field-name base-type nested-fields collection-type]}]
          (let [field-type (or (some-> collection-type base-type->bigquery-type)
                               (base-type->bigquery-type base-type)
                               (let [message (format "Don't know what BigQuery type to use for base type: %s" base-type)]
                                 (log/error (u/format-color 'red message))
                                 (throw (ex-info message {:metabase.util/no-auto-retry? true}))))
                builder (Field/newBuilder
                         (valid-field-name field-name)
                         (LegacySQLTypeName/valueOf (name field-type))
                         ^"[Lcom.google.cloud.bigquery.Field;" (into-array Field (field-definitions->Fields nested-fields)))]
            (cond-> builder
              (isa? :type/Collection base-type) (.setMode Field$Mode/REPEATED)
              :always (.build)))))
   field-definitions))

(mu/defn- create-table!
  [^String dataset-id :- ::lib.schema.common/non-blank-string
   ^String table-id :- ::lib.schema.common/non-blank-string
   field-definitions]
  (u/ignore-exceptions
    (delete-table! dataset-id table-id))
  (let [tbl-id (TableId/of dataset-id table-id)
        schema (Schema/of (u/varargs Field (field-definitions->Fields (cons {:field-name "id"
                                                                             :base-type :type/Integer}
                                                                            field-definitions))))
        tbl    (TableInfo/of tbl-id (StandardTableDefinition/of schema))]
    (.create (bigquery) tbl (u/varargs BigQuery$TableOption)))
  ;; now verify that the Table was created
  (.listTables (bigquery) dataset-id (u/varargs BigQuery$TableListOption))
  (log/info (u/format-color 'blue "Created BigQuery table `%s.%s.%s`." (project-id) dataset-id table-id)))

(defn- table-row-count ^Integer [^String dataset-id, ^String table-id]
  (let [sql (format "SELECT count(*) FROM `%s.%s.%s`" (project-id) dataset-id table-id)]
    (ffirst (#'bigquery/execute-bigquery execute-respond (test-db-details) sql [] nil))))

(defprotocol ^:private Insertable
  (^:private ->insertable [this]
    "Convert a value to an appropriate Google type when inserting a new row."))

(extend-protocol Insertable
  nil
  (->insertable [_] nil)

  Object
  (->insertable [this] this)

  clojure.lang.Keyword
  (->insertable [k]
    (u/qualified-name k))

  java.time.temporal.Temporal
  (->insertable [t]
    ;; BigQuery will barf if you try to specify greater than microsecond precision.
    (u.date/format-sql (t/truncate-to t :micros)))

  java.time.LocalDate
  (->insertable [t]
    (u.date/format-sql t))

  ;; normalize to UTC. BigQuery normalizes it anyway and tends to complain when inserting values that have an offset
  java.time.OffsetDateTime
  (->insertable [t]
    (->insertable (t/local-date-time (t/with-offset-same-instant t (t/zone-offset 0)))))

  ;; for whatever reason the `date time zone-id` syntax that works in SQL doesn't work when loading data
  java.time.ZonedDateTime
  (->insertable [t]
    (->insertable (t/offset-date-time t)))

  ;; normalize to UTC, since BigQuery doesn't support TIME WITH TIME ZONE
  java.time.OffsetTime
  (->insertable [t]
    (->insertable (t/local-time (t/with-offset-same-instant t (t/zone-offset 0))))))

(defn- ->json [row-map]
  (into {} (for [[k v] row-map]
             [(name k) (->insertable v)])))

(defn- rows->request ^InsertAllRequest [^String dataset-id ^String table-id row-maps]
  (let [insert-rows (map (fn [r]
                           (InsertAllRequest$RowToInsert/of (str (get r :id)) (->json r))) row-maps)]
    (InsertAllRequest/of (TableId/of dataset-id table-id) (u/varargs InsertAllRequest$RowToInsert insert-rows))))

(def ^:private max-rows-per-request
  "Max number of rows BigQuery lets us insert at once."
  10000)

(defn- insert-data! [^String dataset-id ^String table-id row-maps]
  {:pre [(seq dataset-id) (seq table-id) (sequential? row-maps) (seq row-maps) (every? map? row-maps)]}
  (doseq [chunk (partition-all max-rows-per-request row-maps)
          :let  [_                           (log/infof "Inserting %d rows like\n%s"
                                                        (count chunk)
                                                        (u/pprint-to-str (first chunk)))
                 req                         (rows->request dataset-id table-id chunk)
                 ^InsertAllResponse response (.insertAll (bigquery) req)]]
    (log/info  (u/format-color 'blue "Sent request to insert %d rows into `%s.%s.%s`"
                               (count (.getRows req))
                               (project-id) dataset-id table-id))
    (when (seq (.getInsertErrors response))
      (log/errorf "Error inserting rows: %s" (u/pprint-to-str (seq (.getInsertErrors response))))
      (throw (ex-info "Error inserting rows"
                      {:errors                       (seq (.getInsertErrors response))
                       :metabase.util/no-auto-retry? true
                       :rows                         row-maps
                       :data                         (.getRows req)}))))
  ;; Wait up to 120 seconds for all the rows to be loaded and become available by BigQuery
  (let [max-wait-seconds   120
        expected-row-count (count row-maps)]
    (log/infof "Waiting for %d rows to be loaded..." expected-row-count)
    (loop [seconds-to-wait-for-load max-wait-seconds]
      (let [actual-row-count (table-row-count dataset-id table-id)]
        (cond
          (= expected-row-count actual-row-count)
          (do
            (log/infof "Loaded %d rows in %d seconds." expected-row-count (- max-wait-seconds seconds-to-wait-for-load))
            :ok)

          (> seconds-to-wait-for-load 0)
          (do (Thread/sleep 1000)
              (log/info ".")
              (recur (dec seconds-to-wait-for-load)))

          :else
          (let [error-message (format "Failed to load table data for `%s.%s.%s`: expected %d rows, loaded %d"
                                      (project-id) dataset-id table-id expected-row-count actual-row-count)]
            (log/error (u/format-color 'red error-message))
            (throw (ex-info error-message {:metabase.util/no-auto-retry? true}))))))))

(defn- tabledef->prepared-rows
  "Convert `table-definition` to a format approprate for passing to `insert-data!`."
  [{:keys [field-definitions rows]}]
  {:pre [(every? map? field-definitions) (sequential? rows) (seq rows)]}
  (let [field-names (map :field-name field-definitions)]
    (for [[i row] (m/indexed rows)]
      (assoc (zipmap field-names row)
             :id (inc i)))))

(defn- load-tabledef! [dataset-id {:keys [table-name field-definitions], :as tabledef}]
  (let [table-name (normalize-name table-name)]
    (create-table! dataset-id table-name field-definitions)
    ;; retry the `insert-data!` step up to 5 times because it seens to fail silently a lot. Since each row is given a
    ;; unique key it shouldn't result in duplicates.
    (loop [num-retries 5]
      (let [^Throwable e (try
                           (insert-data! dataset-id table-name (tabledef->prepared-rows tabledef))
                           nil
                           (catch Throwable e
                             e))]
        (when e
          (if (pos? num-retries)
            (recur (dec num-retries))
            (throw e)))))))

(defn- get-all-datasets
  "Fetch a list of *all* dataset names that currently exist in the BQ test project."
  []
  (for [^Dataset dataset (.iterateAll (.listDatasets (bigquery) (into-array BigQuery$DatasetListOption [])))]
    (.. dataset getDatasetId getDataset)))

(defn- transient-dataset-outdated?
  "Checks whether the given `dataset-id` is a transient dataset that is outdated, and should be deleted.  Note that
  this doesn't need any domain specific knowledge about which transient datasets are
  outdated. The fact that a *created* dataset (i.e. created on BigQuery) is transient has already been encoded by a
  suffix, so we can just look for that here."
  [dataset-id]
  (when-let [[_ ^String ds-timestamp-str] (re-matches #".*__transient_(\d+)$" dataset-id)]
    (t/before? (t/instant (parse-long ds-timestamp-str))
               (t/minus (t/instant) (t/hours 2)))))

(defmethod tx/create-db! :bigquery-cloud-sdk [_ {:keys [database-name table-definitions]} & _]
  {:pre [(seq database-name) (sequential? table-definitions)]}
  ;; clean up outdated datasets
  (doseq [outdated (filter transient-dataset-outdated? (get-all-datasets))]
    (log/info (u/format-color 'blue "Deleting temporary dataset more than two hours old: %s`." outdated))
    (u/ignore-exceptions
      (destroy-dataset! outdated)))
  (let [dataset-id (test-dataset-id database-name)]
    (u/auto-retry 2
      (try
        (log/infof "Creating dataset %s..." (pr-str dataset-id))
       ;; if the dataset failed to load successfully last time around, destroy whatever was loaded so we start
       ;; again from a blank slate
        (u/ignore-exceptions
          (destroy-dataset! dataset-id))
        (create-dataset! dataset-id)
       ;; now create tables and load data.
        (doseq [tabledef table-definitions]
          (load-tabledef! dataset-id tabledef))
        (log/info (u/format-color 'green "Successfully created %s." (pr-str dataset-id)))
        (catch Throwable e
          (log/error (u/format-color 'red  "Failed to load BigQuery dataset %s." (pr-str dataset-id)))
          (log/error (u/pprint-to-str 'red (Throwable->map e)))
          (throw e))))))

(defmethod tx/destroy-db! :bigquery-cloud-sdk
  [_ {:keys [database-name]}]
  (destroy-dataset! (test-dataset-id database-name)))

(defmethod tx/aggregate-column-info :bigquery-cloud-sdk
  ([driver aggregation-type]
   (merge
    ((get-method tx/aggregate-column-info :sql-jdbc/test-extensions) driver aggregation-type)
    (when (#{:count :cum-count} aggregation-type)
      {:base_type :type/Integer})))

  ([driver aggregation-type field]
   (merge
    ((get-method tx/aggregate-column-info :sql-jdbc/test-extensions) driver aggregation-type field)
    ;; BigQuery averages, standard deviations come back as Floats. This might apply to some other ag types as well;
    ;; add them as we come across them.
    (when (#{:avg :stddev} aggregation-type)
      {:base_type :type/Float})
    (when (#{:count :cum-count} aggregation-type)
      {:base_type :type/Integer}))))

(defmethod tx/create-view-of-table! :bigquery-cloud-sdk
  [driver database view-name table-name options]
  (apply execute! (sql.tx/create-view-of-table-sql driver database view-name table-name options)))

(defmethod tx/drop-view! :bigquery-cloud-sdk
  [driver database view-name options]
  (apply execute! (sql.tx/drop-view-sql driver database view-name options)))
