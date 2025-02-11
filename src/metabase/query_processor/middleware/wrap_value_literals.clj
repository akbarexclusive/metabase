(ns metabase.query-processor.middleware.wrap-value-literals
  "Middleware that wraps value literals in `value`/`absolute-datetime`/etc. clauses containing relevant type
  information; parses datetime string literals when appropriate."
  (:require [metabase.mbql.schema :as mbql.s]
            [metabase.mbql.util :as mbql.u]
            [metabase.models.field :refer [Field]]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.types :as types]
            [metabase.util.date-2 :as u.date])
  (:import [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

;;; --------------------------------------------------- Type Info ----------------------------------------------------

(defmulti ^:private type-info
  "Get information about database, base, and semantic types for an object. This is passed to along to various
  `->honeysql` method implementations so drivers have the information they need to handle raw values like Strings,
  which may need to be parsed as a certain type."
  {:arglists '([field-clause])}
  mbql.u/dispatch-by-clause-name-or-class)

(defmethod type-info :default [_] nil)

(defmethod type-info Field
  [field]
  (let [field-info (select-keys field [:base_type :effective_type :coercion_strategy :semantic_type :database_type :name])]
    (merge
     field-info
     ;; add in a default unit for this Field so we know to wrap datetime strings in `absolute-datetime` below based on
     ;; its presence. Its unit will get replaced by the`:temporal-unit` in `:field` options in the method below if
     ;; present
     (when (types/temporal-field? field-info)
       {:unit :default}))))

(defmethod type-info :field [[_ id-or-name opts]]
  (merge
   (when (integer? id-or-name)
     (type-info (qp.store/field id-or-name)))
   (when (:temporal-unit opts)
     {:unit (:temporal-unit opts)})
   (when (:base-type opts)
     {:base_type (:base-type opts)})))


;;; ------------------------------------------------- add-type-info --------------------------------------------------

;; TODO -- parsing the temporal string literals should be moved into `auto-parse-filter-values`, it's really a
;; separate transformation from just wrapping the value
(defmulti ^:private add-type-info
  "Wraps value literals in `:value` clauses that includes base type info about the Field they're being compared against
  for easy driver QP implementation. Temporal literals (e.g., ISO-8601 strings) get wrapped in `:time` or
  `:absolute-datetime` instead which includes unit as well; temporal strings get parsed and converted to "
  {:arglists '([x info & {:keys [parse-datetime-strings?]}])}
  (fn [x & _] (class x)))

(defmethod add-type-info nil
  [_ info & _]
  [:value nil info])

(defmethod add-type-info Object
  [this info & _]
  [:value this info])

(defmethod add-type-info LocalDate
  [this info & _]
  [:absolute-datetime this (get info :unit :default)])

(defmethod add-type-info LocalDateTime
  [this info & _]
  [:absolute-datetime this (get info :unit :default)])

(defmethod add-type-info LocalTime
  [this info & _]
  [:time this (get info :unit :default)])

(defmethod add-type-info OffsetDateTime
  [this info & _]
  [:absolute-datetime this (get info :unit :default)])

(defmethod add-type-info OffsetTime
  [this info & _]
  [:time this (get info :unit :default)])

(defmethod add-type-info ZonedDateTime
  [this info & _]
  [:absolute-datetime this (get info :unit :default)])

(defmethod add-type-info String
  [this {:keys [unit], :as info} & {:keys [parse-datetime-strings?]
                                    :or   {parse-datetime-strings? true}}]
  (if-let [temporal-value (when (and unit
                                     parse-datetime-strings?
                                     (string? this))
                            ;; TIMEZONE FIXME - I think this should actually use
                            ;; (qp.timezone/report-timezone-id-if-supported) instead ?
                            (u.date/parse this (qp.timezone/results-timezone-id)))]
    (if (some #(instance? % temporal-value) [LocalTime OffsetTime])
      [:time temporal-value unit]
      [:absolute-datetime temporal-value unit])
    [:value this info]))


;;; -------------------------------------------- wrap-literals-in-clause ---------------------------------------------

(def ^:private raw-value? (complement mbql.u/mbql-clause?))

(defn wrap-value-literals-in-mbql
  "Given a normalized mbql query (important to desugar forms like `[:does-not-contain ...]` -> `[:not [:contains
  ...]]`), walks over the clause and annotates literals with type information.

  eg:

  [:not [:contains [:field 13 {:base_type :type/Text}] \"foo\"]]
  ->
  [:not [:contains [:field 13 {:base_type :type/Text}]
                   [:value \"foo\" {:base_type :type/Text,
                                    :semantic_type nil,
                                    :database_type \"VARCHAR\",
                                    :name \"description\"}]]]"
  [mbql]
  (mbql.u/replace mbql
    [(clause :guard #{:= :!= :< :> :<= :>=}) field (x :guard raw-value?)]
    [clause field (add-type-info x (type-info field))]

    [:datetime-diff (x :guard string?) (y :guard string?) unit]
    [:datetime-diff (add-type-info (u.date/parse x) nil) (add-type-info (u.date/parse y) nil) unit]

    [(clause :guard #{:datetime-add :datetime-subtract :convert-timezone :temporal-extract}) (field :guard string?) & args]
    (into [clause (add-type-info (u.date/parse field) nil)] args)

    [:between field (min-val :guard raw-value?) (max-val :guard raw-value?)]
    [:between
     field
     (add-type-info min-val (type-info field))
     (add-type-info max-val (type-info field))]

    [(clause :guard #{:starts-with :ends-with :contains}) field (s :guard string?) & more]
    (let [s (add-type-info s (type-info field), :parse-datetime-strings? false)]
      (into [clause field s] more))))

(defn unwrap-value-literal
  "Extract value literal from `:value` form or returns form as is if not a `:value` form."
  [maybe-value-form]
  (mbql.u/match-one maybe-value-form
    [:value x & _] x
    _              &match))

(defn ^:private wrap-value-literals-in-mbql-query
  [{:keys [source-query], :as inner-query} options]
  (let [inner-query (cond-> inner-query
                      source-query (update :source-query wrap-value-literals-in-mbql-query options))]
    (wrap-value-literals-in-mbql inner-query)))

(defn wrap-value-literals
  "Middleware that wraps ran value literals in `:value` (for integers, strings, etc.) or `:absolute-datetime` (for
  datetime strings, etc.) clauses which include info about the Field they are being compared to. This is done mostly
  to make it easier for drivers to write implementations that rely on multimethod dispatch (by clause name) -- they
  can dispatch directly off of these clauses."
  [{query-type :type, :as query}]
  (if-not (= query-type :query)
    query
    (mbql.s/validate-query
     (update query :query wrap-value-literals-in-mbql-query nil))))
