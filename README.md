# ClojureScript on Cloudflare Workers

A technical demo exploring ClojureScript compilation to Cloudflare Workers. The domain application is an Australian weather API that combines place search with BOM forecasts and live observations.

## Key Technical Points

- **Shadow-CLJS** with `:esm` target compiles to ES modules for Workers
- **Reitit** for routing, with a thin Ring-style adapter (`worker.ring`)
- **D1 Database** for place search with pre-computed weather station mappings
- **Streaming SAX parsing** for efficient XML processing of BOM feeds
- **Advanced compilation** keeps bundle size minimal

## Ring Adapter

The `worker.ring` namespace bridges Cloudflare's Fetch API to Ring conventions:

```clojure
;; Handlers receive Ring-style request maps
(defn my-handler [{:keys [parameters]}]
  (let [{:keys [id]} (:path parameters)]
    {:status 200
     :body {:id id}}))

;; Routes use Reitit with Malli coercion
(def router
  (r/router
    [["/api/items/:id" {:handler item-handler
                        :coercion malli/coercion
                        :parameters {:path [:map [:id :int]]}}]]
    {:compile coercion/compile-request-coercers}))
```

Routes with `:parameters` schemas get automatic validation:
- Invalid params return 400 with `{:error "Invalid parameters" :in :query-params :issues [...]}`
- Coerced params available under `:parameters` key in request

## API

### GET /api/forecast?q={query}

Search for a place and get weather data. Returns the best match with 7-day forecast and live observations.

```bash
curl "http://localhost:8787/api/forecast?q=Melbourne"
```

**Response:**
```json
{
  "query": "Melbourne",
  "place": {
    "name": "Melbourne",
    "type": "city",
    "state": "VIC",
    "lat": -37.814,
    "lon": 144.963
  },
  "observation": {
    "station": "MELBOURNE (OLYMPIC PARK)",
    "observation_time": "2026-01-01T12:00:00+11:00",
    "temp_c": 18.5,
    "feels_like_c": 14.9,
    "humidity": 66,
    "wind_dir": "SSW",
    "wind_speed_kmh": 22,
    "gust_speed_kmh": 32,
    "rain_24hr_mm": 0
  },
  "forecast": {
    "location": "Melbourne",
    "aac": "VIC_PT042",
    "periods": [
      {
        "start_time": "2026-01-01T10:00:00+11:00",
        "end_time": "2026-01-02T00:00:00+11:00",
        "forecast": "Mostly sunny.",
        "icon": "sunny",
        "min_temp": null,
        "max_temp": 22,
        "rain_chance": "0%"
      }
    ]
  },
  "other_matches": [...],
  "refreshed_at": "2026-01-01T10:30:00.000Z"
}
```

The `refreshed_at` field indicates when the places database was last updated.

**Icon values:** `sunny`, `clear`, `partly-cloudy`, `cloudy`, `hazy`, `light-rain`, `windy`, `fog`, `showers`, `rain`, `dusty`, `frost`, `snow`, `storm`, `light-showers`, `heavy-showers`, `cyclone`

## Server-Side Rendering with Replicant

This project uses [Replicant](https://github.com/cjohansen/replicant) for server-side HTML rendering from Hiccup.

### Raw/Unescaped Content

By default, Replicant escapes text content (e.g., `"` becomes `&quot;`). To embed raw content like JSON, use the `:innerHTML` attribute:

```clojure
;; Text content is escaped (quotes become &quot;)
[:script (js/JSON.stringify data)]
;; => <script>{&quot;key&quot;: &quot;value&quot;}</script>

;; :innerHTML bypasses escaping
[:script {:type "application/json" :id "my-data"
          :innerHTML (js/JSON.stringify data)}]
;; => <script type="application/json" id="my-data">{"key": "value"}</script>
```

**Key points:**
- `:innerHTML` works like React's `dangerouslySetInnerHTML`
- Child elements are ignored when `:innerHTML` is set
- Can be combined with other attributes (`:id`, `:class`, etc.)

**Source:** Found in `replicant/src/replicant/string.cljc` lines 132-133 and tested in `replicant/test/replicant/string_test.cljc` lines 185-197.

## Project Structure

```
src/main/worker/
├── core.cljs   # Entry point, route definitions, API handlers
├── ring.cljs   # Ring adapter for Cloudflare (includes D1 bindings)
├── bom.cljs    # BOM weather data fetching with streaming SAX parsing
├── views.cljs  # Server-side rendered HTML views
└── client.cljs # Client-side JavaScript (autocomplete, maps)
```

## Development

```bash
npm install
npm run dev
```

This starts both Shadow-CLJS (watching/compiling) and Wrangler (local Workers runtime) concurrently. The app runs at `http://localhost:8787`.

Individual scripts:
- `npm run watch` — Shadow-CLJS only
- `npm run wrangler` — Wrangler only

## Production

```bash
npm run release   # Optimized build
npm run deploy    # Deploy to Cloudflare
```

## Configuration

- `shadow-cljs.edn` — ClojureScript build config
- `wrangler.toml` — Cloudflare Workers config

## Places Database (D1)

Australian places database for geocoding, sourced from OpenStreetMap. Each place has pre-computed mappings to the nearest BOM forecast location and observation station for instant weather lookups.

### Data Sources

**IDM00013.dbf** - BOM Forecast Locations (~684 locations)
- DBF file from the Bureau of Meteorology containing official forecast locations
- Streamed directly from: `ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00013.dbf`

**ID*60920.xml** - BOM Observation Stations (~600 stations)
- Live weather observation feeds from weather stations across Australia
- Streamed from: `http://reg.bom.gov.au/fwo/IDV60920.xml` (one per state)

**australia.osm.pbf** - OpenStreetMap Places (~100,000 places)
- Cities, towns, suburbs, landmarks, and points of interest
- Downloaded automatically from: `https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf`

**How They're Combined**

The `scripts/process_pbf.clj` script:
1. Downloads the OSM PBF file if not present locally
2. Checks if data sources have changed since last run (via D1 metadata)
3. Streams BOM forecast locations directly from FTP
4. Fetches observation station metadata from all 8 state feeds
5. Computes nearest observation station for each forecast location
6. Extracts interesting places from the OSM PBF file
7. Computes nearest BOM forecast location for each OSM place
8. Generates `data/places.sql` with all pre-computed mappings
9. Records metadata (sequence numbers, timestamps) for change detection

This enables instant weather lookups—search for any place and get both the 7-day forecast and live observations without runtime distance calculations.

### Quick Start

```bash
# 1. Process data to SQL (requires: brew install osmium-tool)
# Downloads PBF if missing, streams BOM data, checks for changes, generates SQL
cd scripts && clj -M process_pbf.clj config.edn && cd ..

# 2. Seed local D1 database
wrangler d1 execute places-db --local --file data/places.sql

# 3. Verify
wrangler d1 execute places-db --local \
  --command "SELECT name, state, bom_aac, obs_wmo FROM bom_locations WHERE name = 'Melbourne';"
```

**Re-running the script:**
The script automatically checks if data sources have changed. If nothing has changed, it exits early without regenerating the SQL. Use `:force? true` in config.edn to force regeneration.

### Database Stats

| Metric | Value |
|--------|-------|
| Total places | ~102,000 |
| BOM forecast locations | 684 |
| Observation stations | ~600 |
| Database size | ~25 MB |

### Remote Deployment

```bash
# Create D1 database (first time only)
wrangler d1 create places-db

# Update wrangler.toml with the returned database_id, then:
wrangler d1 execute places-db --remote --file data/places.sql
```

### Data Attribution

- Place data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licensed under ODbL
- Weather data © [Bureau of Meteorology](http://www.bom.gov.au)
