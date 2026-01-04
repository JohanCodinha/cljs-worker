# BOM Data Feeds Research

Research into Bureau of Meteorology (BOM) free data feeds for weather observations and forecasts.

## Data Sources

All feeds are available via **anonymous FTP** at `ftp://ftp.bom.gov.au/anon/gen/fwo/` with no sign-up required.

### Bulk Observation XML (ID*60920)

Single XML file per state containing current observations for all weather stations.

| State | Product ID | Stations | File Size |
|-------|------------|----------|-----------|
| NSW/ACT | IDN60920 | 191 | ~580 KB |
| QLD | IDQ60920 | 155 | ~470 KB |
| WA | IDW60920 | 144 | ~440 KB |
| VIC | IDV60920 | 100 | ~300 KB |
| SA | IDS60920 | 78 | ~240 KB |
| TAS | IDT60920 | 67 | ~200 KB |
| NT | IDD60920 | 56 | ~170 KB |
| **Total** | | **791** | **~2.4 MB** |

**Update frequency**: ~10 minutes
**Delete time**: 24 hours (files remain on FTP)

#### Data fields per station

- `air_temperature` - Current temperature (°C)
- `apparent_temp` - Feels-like temperature (°C)
- `dew_point` - Dew point (°C)
- `rel-humidity` - Relative humidity (%)
- `wind_dir` / `wind_dir_deg` - Wind direction
- `wind_spd_kmh` - Wind speed (km/h)
- `wind_gust_spd` - Wind gust (km/h)
- `rainfall` - Rainfall since 9am (mm)
- `rainfall_24hr` - Last 24 hours rainfall (mm)
- `msl_pres` - Mean sea level pressure (hPa)
- `vis_km` - Visibility (km)
- `cloud` / `cloud_oktas` - Cloud cover
- `maximum_air_temperature` - Running max temp (°C)
- `minimum_air_temperature` - Running min temp (°C)

Plus station metadata: WMO ID, BOM ID, name, lat/lon, timezone, forecast district.

### Précis Forecast XML (Short Form)

All forecast locations per state in a single XML file.

| State | Product ID |
|-------|------------|
| NSW | IDN11060 |
| NT | IDD10207 |
| QLD | IDQ11295 |
| SA | IDS10044 |
| TAS | IDT16710 |
| VIC | IDV10753 |
| WA | IDW14199 |

**Update frequency**: ~6 hours
**Delete time**: 24 hours

### City Forecast XML (Detailed)

Major metropolitan centres and tourist locations.

| State | Product ID |
|-------|------------|
| NSW | IDN11050 |
| NT | IDD10198 |
| QLD | IDQ10605 |
| SA | IDS10037 |
| TAS | IDT13630 |
| VIC | IDV10751 |
| WA | IDW12400 |

**Update frequency**: ~6 hours
**Delete time**: 24 hours

## API Call Estimates

### Recommended Approach (Bulk XML)

| Feed | Files | Refresh Rate | Calls/Day |
|------|-------|--------------|-----------|
| Observations | 7 | Every 10 min | 1,008 |
| Forecasts | 7 | Every 6 hours | 28 |
| **Total** | **14** | | **~1,036** |

### Comparison with Individual Station Approach

| Approach | Calls/Day | Reduction |
|----------|-----------|-----------|
| Individual station JSON (30 min) | ~29,000 | - |
| **Bulk state XML (10 min)** | **~1,100** | **96%** |

### Data Transfer

| Feed | Size per refresh | Refreshes/day | Daily Transfer |
|------|------------------|---------------|----------------|
| Observations (7 states) | ~2.4 MB | 144 | ~350 MB |
| Forecasts (7 states) | ~3 MB | 4 | ~12 MB |
| **Total** | | | **~360 MB/day** |

## Storage Estimates (Historical Caching)

If storing all observations and forecasts historically:

### Per Day

| Data Type | Calculation | Size |
|-----------|-------------|------|
| Observations | 791 stations × 144 readings × 175 bytes | ~20 MB |
| Forecasts | 684 locations × 7 days × 500 bytes × 4 | ~10 MB |
| **Total** | | **~30 MB/day** |

### Projections

| Period | Storage |
|--------|---------|
| Daily | ~30 MB |
| Monthly | ~900 MB |
| Yearly | **~11 GB** |

### Database Row Counts (per year)

| Table | Rows/Day | Rows/Year |
|-------|----------|-----------|
| Observation readings | 791 × 144 = 113,904 | ~41.6 million |
| Forecast entries | 684 × 7 × 4 = 19,152 | ~7 million |

## Trade-offs

| Feature | Bulk XML | Individual JSON |
|---------|----------|-----------------|
| API calls | 7 per refresh | 791 per refresh |
| History | Current only | 72-hour history |
| Update frequency | ~10 min | ~30 min |
| Data freshness | Better | Good |

## Terms of Use

From BOM's official documentation:

> All products available via the anonymous FTP service are subject to the default terms of the Bureau's copyright notice: you may download, use and copy that material for personal use, or use within your organisation but you may not supply that material to any other person or use it for any commercial purpose.

> Users intending to publish Bureau data should do so as Registered Users.

**Important notes:**
- BOM does not guarantee FTP availability for anonymous users
- Anonymous users cannot be notified of service changes
- Registered User status required for commercial use

## References

- [Weather Data Services](https://www.bom.gov.au/catalogue/data-feeds.shtml)
- [FTP Public Products](https://www.bom.gov.au/catalogue/anon-ftp.shtml)
- [Anonymous FTP Hints](https://www.bom.gov.au/catalogue/anon-ftp-hints.shtml)
- FTP Server: `ftp://ftp.bom.gov.au/anon/gen/fwo/`
