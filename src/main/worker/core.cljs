(ns worker.core
  "Cloudflare Worker entry point with routing and request handlers."
  (:require [reitit.core :as r]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as malli]
            [worker.ring :as ring]
            [worker.bom :as bom]
            [worker.views :as views]))

;; -----------------------------------------------------------------------------
;; Route Handlers
;;
;; Handlers receive Ring-style request maps:
;;   {:request-method :get
;;    :uri            "/path"
;;    :query-params   {:q "value"}
;;    :path-params    {:id "123"}
;;    :body-params    {...}
;;    :headers        {...}}
;;
;; Handlers return response maps (or Promise of response map):
;;   {:status 200
;;    :headers {"content-type" "..."}
;;    :body ...}

(defn- api-hello-handler
  "Returns a simple JSON greeting with timestamp."
  [_request]
  (ring/json-response {:message "Hello from ClojureScript!"
                       :timestamp (.now js/Date)}))

(defn- search-places
  "Search for places matching query in D1 database.
   Returns top matches ordered by importance, including observation station info."
  [^js db query]
  (-> (.prepare db "SELECT p.name, p.slug, p.type, p.state, p.lat, p.lon, p.bom_aac, p.importance,
                           b.obs_wmo, b.obs_name
                    FROM places p
                    LEFT JOIN bom_locations b ON p.bom_aac = b.aac
                    WHERE p.name LIKE ?
                    ORDER BY p.importance DESC, p.name ASC
                    LIMIT 10")
      (.bind (str "%" query "%"))
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- find-place-by-slug
  "Find a place by its slug in D1 database."
  [^js db slug]
  (-> (.prepare db "SELECT p.name, p.slug, p.type, p.state, p.lat, p.lon, p.bom_aac,
                           b.obs_wmo, b.obs_name
                    FROM places p
                    LEFT JOIN bom_locations b ON p.bom_aac = b.aac
                    WHERE p.slug = ?
                    LIMIT 1")
      (.bind slug)
      (.first)
      (.then (fn [^js result]
               (when result
                 (js->clj result :keywordize-keys true))))))

(defn- find-other-places-by-name
  "Find other places with the same name (for 'other matches')."
  [^js db name current-slug]
  (-> (.prepare db "SELECT name, slug, type, state
                    FROM places
                    WHERE name = ? AND slug != ?
                    ORDER BY importance DESC
                    LIMIT 5")
      (.bind name current-slug)
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- get-example-places
  "Get a few example places for the home page."
  [^js db]
  (-> (.prepare db "SELECT name, slug FROM places
                    WHERE name IN ('Melbourne', 'Sydney', 'Brisbane')
                    AND type = 'city'
                    ORDER BY name
                    LIMIT 3")
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- forecast-handler
  "Returns forecast data for matching locations.
   Query param 'q' is validated by Malli coercion.
   Uses D1 database to find matching places and their BOM forecast locations.
   Fetches both forecast and live observation data in parallel."
  [{:keys [parameters ^js env]}]
  (let [{:keys [q]} (:query parameters)
        db (.-PLACES_DB env)]
    (-> (search-places db q)
        (.then (fn [places]
                 (if (seq places)
                   (let [best-match (first places)
                         state (:state best-match)
                         ;; Fetch forecast and observation in parallel
                         forecast-promise (bom/fetch-forecast-by-aac state (:bom_aac best-match))
                         obs-promise (if-let [wmo (:obs_wmo best-match)]
                                       (bom/fetch-observation-by-wmo state wmo)
                                       (js/Promise.resolve nil))]
                     (-> (js/Promise.all #js [forecast-promise obs-promise])
                         (.then (fn [results]
                                  (let [[forecast observation] (js->clj results :keywordize-keys true)]
                                    (ring/json-response
                                     {:query q
                                      :place {:name (:name best-match)
                                              :type (:type best-match)
                                              :state (:state best-match)
                                              :lat (:lat best-match)
                                              :lon (:lon best-match)}
                                      :observation observation
                                      :forecast forecast
                                      :other_matches (mapv #(select-keys % [:name :type :state])
                                                           (rest places))}))))))
                   (ring/json-response {:query q
                                        :error "No matching places found"
                                        :places []})))))))

(defn- not-found-handler
  "Returns 404 for unknown routes."
  [_request]
  (ring/text-response "Not Found" 404))

;; -----------------------------------------------------------------------------
;; SSR Handlers

(defn- home-handler
  "Renders the home page with search autocomplete."
  [{:keys [^js env]}]
  (let [db (.-PLACES_DB env)]
    (-> (get-example-places db)
        (.then (fn [examples]
                 (ring/html-response
                  (views/home-page {:examples examples})))))))

(defn- places-search-handler
  "API endpoint for autocomplete - returns JSON list of matching places."
  [{:keys [parameters ^js env]}]
  (let [{:keys [q]} (:query parameters)
        db (.-PLACES_DB env)]
    (-> (search-places db q)
        (.then (fn [places]
                 (ring/json-response
                  (mapv #(select-keys % [:name :slug :type :state]) places)))))))

(defn- forecast-page-handler
  "Renders the forecast page for a specific location by slug."
  [{:keys [parameters ^js env]}]
  (let [{:keys [slug]} (:path parameters)
        db (.-PLACES_DB env)]
    (-> (find-place-by-slug db slug)
        (.then (fn [place]
                 (if-not place
                   (ring/html-response (views/not-found-page slug) 404)
                   (let [state (:state place)
                         forecast-promise (bom/fetch-forecast-by-aac state (:bom_aac place))
                         obs-promise (if-let [wmo (:obs_wmo place)]
                                       (bom/fetch-observation-by-wmo state wmo)
                                       (js/Promise.resolve nil))
                         others-promise (find-other-places-by-name db (:name place) slug)]
                     (-> (js/Promise.all #js [forecast-promise obs-promise others-promise])
                         (.then (fn [results]
                                  (let [[forecast observation other-matches] (js->clj results :keywordize-keys true)]
                                    (ring/html-response
                                     (views/forecast-page
                                      {:place {:name (:name place)
                                               :type (:type place)
                                               :state (:state place)}
                                       :observation observation
                                       :forecast forecast
                                       :other-matches other-matches})))))))))))))

(def router
  (r/router
   [;; SSR pages
    ["/" {:name ::home
          :handler home-handler}]
    ["/forecast/:slug" {:name ::forecast-page
                        :handler forecast-page-handler
                        :coercion malli/coercion
                        :parameters {:path [:map [:slug :string]]}}]

    ;; API endpoints
    ["/api/hello" {:name ::api-hello
                   :handler api-hello-handler}]
    ["/api/places" {:name ::api-places
                    :handler places-search-handler
                    :coercion malli/coercion
                    :parameters {:query [:map [:q :string]]}}]
    ["/api/forecast" {:name ::api-forecast
                      :handler forecast-handler
                      :coercion malli/coercion
                      :parameters {:query [:map [:q :string]]}}]]
   {:compile coercion/compile-request-coercers}))


(def ^:private handle-request
  "Main request handler - wraps router with Ring adapter."
  (ring/wrap-routes router not-found-handler))

(def handler-obj
  "Export object for Cloudflare Workers with fetch handler."
  #js {:fetch (fn [request env _ctx]
                (handle-request request env))})
