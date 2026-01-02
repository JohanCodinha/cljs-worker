(ns worker.client
  "Client-side JavaScript for search autocomplete.
   Runs in the browser, fetches suggestions from /api/places.")

(def ^:private debounce-delay 200)
(def ^:private min-query-length 2)

(defonce ^:private state
  (atom {:timeout-id nil
         :active-index -1
         :places []}))

(defn- escape-html
  "Escape HTML special characters"
  [s]
  (-> (str s)
      (.replace (js/RegExp. "&" "g") "&amp;")
      (.replace (js/RegExp. "<" "g") "&lt;")
      (.replace (js/RegExp. ">" "g") "&gt;")))

(defn- render-suggestion
  "Render a single suggestion item"
  [{:keys [name type state slug]} index active?]
  (str "<div class=\"suggestion" (when active? " active") "\" "
       "data-slug=\"" (escape-html slug) "\" "
       "data-index=\"" index "\">"
       "<span class=\"suggestion-name\">" (escape-html name) "</span>"
       "<span class=\"suggestion-meta\">" (escape-html (or type "")) ", " (escape-html state) "</span>"
       "</div>"))

(defn- render-suggestions
  "Render all suggestions"
  [places active-index]
  (if (empty? places)
    ""
    (->> places
         (map-indexed (fn [idx place]
                        (render-suggestion place idx (= idx active-index))))
         (apply str))))

(defn- show-suggestions
  "Display suggestions dropdown"
  [^js suggestions-el places]
  (swap! state assoc :places places :active-index -1)
  (set! (.-innerHTML suggestions-el) (render-suggestions places -1))
  (.add (.-classList suggestions-el) "active"))

(defn- hide-suggestions
  "Hide suggestions dropdown"
  [^js suggestions-el]
  (swap! state assoc :places [] :active-index -1)
  (.remove (.-classList suggestions-el) "active"))

(defn- navigate-to
  "Navigate to forecast page for selected place"
  [slug]
  (set! (.-location js/window) (str "/forecast/" slug)))

(defn- fetch-places
  "Fetch place suggestions from API"
  [query ^js suggestions-el]
  (-> (js/fetch (str "/api/places?q=" (js/encodeURIComponent query)))
      (.then #(.json %))
      (.then (fn [data]
               (let [places (js->clj data :keywordize-keys true)]
                 (if (seq places)
                   (show-suggestions suggestions-el places)
                   (hide-suggestions suggestions-el)))))
      (.catch (fn [_err]
                (hide-suggestions suggestions-el)))))

(defn- update-active
  "Update active suggestion highlighting"
  [^js suggestions-el new-index]
  (let [places (:places @state)
        clamped-index (cond
                        (neg? new-index) (dec (count places))
                        (>= new-index (count places)) 0
                        :else new-index)]
    (swap! state assoc :active-index clamped-index)
    (set! (.-innerHTML suggestions-el) (render-suggestions places clamped-index))))

(defn- on-input
  "Handle input event with debouncing"
  [^js input-el ^js suggestions-el]
  ;; Clear existing timeout
  (when-let [tid (:timeout-id @state)]
    (js/clearTimeout tid))

  (let [query (.-value input-el)]
    (if (< (count query) min-query-length)
      (hide-suggestions suggestions-el)
      ;; Debounce the API call
      (let [tid (js/setTimeout
                 #(fetch-places query suggestions-el)
                 debounce-delay)]
        (swap! state assoc :timeout-id tid)))))

(defn- on-keydown
  "Handle keyboard navigation"
  [^js e ^js suggestions-el]
  (let [key (.-key e)
        places (:places @state)
        active-index (:active-index @state)]
    (when (seq places)
      (case key
        "ArrowDown" (do (.preventDefault e)
                        (update-active suggestions-el (inc active-index)))
        "ArrowUp" (do (.preventDefault e)
                      (update-active suggestions-el (dec active-index)))
        "Enter" (do (.preventDefault e)
                    (when (>= active-index 0)
                      (let [place (nth places active-index)]
                        (navigate-to (:slug place)))))
        "Escape" (hide-suggestions suggestions-el)
        nil))))

(defn- on-suggestion-click
  "Handle click on suggestion"
  [^js e]
  (when-let [suggestion (.closest (.-target e) ".suggestion")]
    (when-let [slug (.getAttribute suggestion "data-slug")]
      (navigate-to slug))))

(defn- on-document-click
  "Hide suggestions when clicking outside"
  [^js e ^js search-box-el ^js suggestions-el]
  (when-not (.contains search-box-el (.-target e))
    (hide-suggestions suggestions-el)))

(defn init
  "Initialize autocomplete functionality"
  []
  (when-let [input-el (.getElementById js/document "search")]
    (let [suggestions-el (.getElementById js/document "suggestions")
          search-box-el (.-parentElement input-el)]
      ;; Input handler
      (.addEventListener input-el "input"
                         #(on-input input-el suggestions-el))

      ;; Keyboard navigation
      (.addEventListener input-el "keydown"
                         #(on-keydown % suggestions-el))

      ;; Click on suggestion
      (.addEventListener suggestions-el "click" on-suggestion-click)

      ;; Click outside to close
      (.addEventListener js/document "click"
                         #(on-document-click % search-box-el suggestions-el))

      ;; Focus input on load
      (.focus input-el))))
