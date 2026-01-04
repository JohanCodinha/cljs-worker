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

(defn- find-obs-stations-for-aac
  "Find observation stations for a BOM AAC code, ordered by rank (distance)."
  [^js db aac]
  (-> (.prepare db "SELECT obs_wmo, obs_name, obs_lat, obs_lon, distance_km, rank
                    FROM bom_obs_stations
                    WHERE aac = ?
                    ORDER BY rank ASC
                    LIMIT 5")
      (.bind aac)
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

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

(defn- search-stations
  "Search for observation stations matching query."
  [^js db query]
  (-> (.prepare db "SELECT DISTINCT obs_wmo, obs_name,
                           (SELECT state FROM bom_locations WHERE aac = bom_obs_stations.aac LIMIT 1) as state
                    FROM bom_obs_stations
                    WHERE obs_name LIKE ?
                    ORDER BY obs_name
                    LIMIT 10")
      (.bind (str "%" query "%"))
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- get-all-stations
  "Get all unique observation stations with coordinates for map display."
  [^js db]
  (-> (.prepare db "SELECT DISTINCT obs_wmo, obs_name, obs_lat, obs_lon,
                           (SELECT state FROM bom_locations WHERE aac = bom_obs_stations.aac LIMIT 1) as state
                    FROM bom_obs_stations
                    WHERE obs_lat IS NOT NULL AND obs_lon IS NOT NULL
                    ORDER BY obs_name")
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- get-stations-in-bounds
  "Get stations within geographic bounds for map display."
  [^js db min-lon min-lat max-lon max-lat]
  (-> (.prepare db "SELECT DISTINCT obs_wmo, obs_name, obs_lat, obs_lon,
                           (SELECT state FROM bom_locations WHERE aac = bom_obs_stations.aac LIMIT 1) as state
                    FROM bom_obs_stations
                    WHERE obs_lat IS NOT NULL AND obs_lon IS NOT NULL
                      AND obs_lon >= ? AND obs_lon <= ?
                      AND obs_lat >= ? AND obs_lat <= ?
                    ORDER BY obs_name")
      (.bind min-lon max-lon min-lat max-lat)
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- get-station-by-wmo
  "Get station details by WMO ID."
  [^js db wmo]
  (-> (.prepare db "SELECT obs_wmo, obs_name, obs_lat, obs_lon,
                           (SELECT state FROM bom_locations WHERE aac = bom_obs_stations.aac LIMIT 1) as state
                    FROM bom_obs_stations
                    WHERE obs_wmo = ?
                    LIMIT 1")
      (.bind wmo)
      (.first)
      (.then (fn [^js result]
               (when result
                 (js->clj result :keywordize-keys true))))))

(defn- forecast-handler
  "Returns forecast data for matching locations.
   Query param 'q' is validated by Malli coercion.
   Uses D1 database to find matching places and their BOM forecast locations.
   Fetches observations from multiple stations for better data coverage."
  [{:keys [parameters ^js env]}]
  (let [{:keys [q]} (:query parameters)
        db (.-PLACES_DB env)]
    (-> (search-places db q)
        (.then (fn [places]
                 (if (seq places)
                   (let [best-match (first places)
                         state (:state best-match)
                         forecast-promise (bom/fetch-forecast-by-aac state (:bom_aac best-match))
                         stations-promise (find-obs-stations-for-aac db (:bom_aac best-match))]
                     (-> (js/Promise.all #js [forecast-promise stations-promise])
                         (.then (fn [results]
                                  (let [[forecast stations] (js->clj results :keywordize-keys true)]
                                    (-> (bom/fetch-observations-multi state stations)
                                        (.then (fn [observation]
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
                                                                        (rest places))})))))))))
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
  "Renders the home page with search autocomplete and capitals weather map."
  [{:keys [^js env]}]
  (let [db (.-PLACES_DB env)]
    (-> (js/Promise.all #js [(get-example-places db)
                              (bom/fetch-capitals-weather)])
        (.then (fn [results]
                 (let [[examples capitals-weather] (js->clj results :keywordize-keys true)]
                   (ring/html-response
                    (views/home-page {:examples examples
                                      :capitals-weather capitals-weather}))))))))

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
  "Renders the forecast page for a specific location by slug.
   Uses multi-station observation fetching for better data coverage."
  [{:keys [parameters ^js env]}]
  (let [{:keys [slug]} (:path parameters)
        db (.-PLACES_DB env)]
    (-> (find-place-by-slug db slug)
        (.then (fn [place]
                 (if-not place
                   (ring/html-response (views/not-found-page slug) 404)
                   (let [state (:state place)
                         forecast-promise (bom/fetch-forecast-by-aac state (:bom_aac place))
                         stations-promise (find-obs-stations-for-aac db (:bom_aac place))
                         others-promise (find-other-places-by-name db (:name place) slug)]
                     ;; First get stations, then fetch observations from all of them
                     (-> (js/Promise.all #js [forecast-promise stations-promise others-promise])
                         (.then (fn [results]
                                  (let [[forecast stations other-matches] (js->clj results :keywordize-keys true)]
                                    ;; Now fetch multi-station observations
                                    (-> (bom/fetch-observations-multi state stations)
                                        (.then (fn [observation]
                                                 (ring/html-response
                                                  (views/forecast-page
                                                   {:place {:name (:name place)
                                                            :type (:type place)
                                                            :state (:state place)}
                                                    :observation observation
                                                    :forecast forecast
                                                    :other-matches other-matches}))))))))))))))))

(defn- stations-search-handler
  "API endpoint for station autocomplete - returns JSON list of matching stations."
  [{:keys [parameters ^js env]}]
  (let [{:keys [q]} (:query parameters)
        db (.-PLACES_DB env)]
    (-> (search-stations db q)
        (.then (fn [stations]
                 (ring/json-response
                  (mapv #(select-keys % [:obs_wmo :obs_name :state]) stations)))))))

(defn- stations-geojson-handler
  "API endpoint returning all stations as GeoJSON for map display."
  [{:keys [^js env]}]
  (let [db (.-PLACES_DB env)]
    (-> (get-all-stations db)
        (.then (fn [stations]
                 (ring/json-response
                  {:type "FeatureCollection"
                   :features (mapv (fn [{:keys [obs_wmo obs_name obs_lat obs_lon state]}]
                                     {:type "Feature"
                                      :geometry {:type "Point"
                                                 :coordinates [obs_lon obs_lat]}
                                      :properties {:wmo obs_wmo
                                                   :name obs_name
                                                   :state state}})
                                   stations)}))))))

(defn- stations-temps-handler
  "API endpoint returning current temperatures for stations in bounds.
   Query params: bounds=minLon,minLat,maxLon,maxLat"
  [{:keys [parameters ^js env]}]
  (let [{:keys [bounds]} (:query parameters)
        db (.-PLACES_DB env)
        [min-lon min-lat max-lon max-lat] (mapv js/parseFloat (.split bounds ","))]
    (-> (get-stations-in-bounds db min-lon min-lat max-lon max-lat)
        (.then (fn [stations]
                 (-> (bom/fetch-current-temps stations)
                     (.then (fn [temps]
                              (ring/json-response temps)))))))))

(defn- stations-page-handler
  "Renders the stations list page with search autocomplete."
  [_request]
  (ring/html-response
   (views/stations-list-page {})))

(defn- station-page-handler
  "Renders the station detail page with current conditions and temperature graph."
  [{:keys [parameters ^js env]}]
  (let [{:keys [wmo]} (:path parameters)
        db (.-PLACES_DB env)]
    (-> (get-station-by-wmo db wmo)
        (.then
         (fn [station]
           (if-not station
             (ring/html-response (views/not-found-page (str "Station " wmo)) 404)
             (let [state (:state station)]
               (-> (bom/fetch-observation-history-by-wmo state wmo)
                   (.then
                    (fn [history]
                      (let [current (first history)]
                        (ring/html-response
                         (views/station-page
                          {:station station
                           :observation (when current
                                          (assoc current :history history))})))))))))))))

(def router
  (r/router
   [;; SSR pages
    ["/" {:name ::home
          :handler home-handler}]
    ["/forecast/:slug" {:name ::forecast-page
                        :handler forecast-page-handler
                        :coercion malli/coercion
                        :parameters {:path [:map [:slug :string]]}}]
    ["/stations" {:name ::stations-page
                  :handler stations-page-handler}]
    ["/station/:wmo" {:name ::station-page
                      :handler station-page-handler
                      :coercion malli/coercion
                      :parameters {:path [:map [:wmo :string]]}}]

    ;; API endpoints
    ["/api/hello" {:name ::api-hello
                   :handler api-hello-handler}]
    ["/api/places" {:name ::api-places
                    :handler places-search-handler
                    :coercion malli/coercion
                    :parameters {:query [:map [:q :string]]}}]
    ["/api/stations" {:name ::api-stations
                      :handler stations-search-handler
                      :coercion malli/coercion
                      :parameters {:query [:map [:q :string]]}}]
    ["/api/stations/geojson" {:name ::api-stations-geojson
                              :handler stations-geojson-handler}]
    ["/api/stations/temps" {:name ::api-stations-temps
                            :handler stations-temps-handler
                            :coercion malli/coercion
                            :parameters {:query [:map [:bounds :string]]}}]
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
