# Which endpoint writes which cached field

The write rule of NEXT.md (section 4, Caches and loaders) gives every field of every cached entity
one authoritative endpoint. An authoritative response replaces the fields it is authoritative for,
and clears the ones it omits only when the endpoint always sends them when they exist, localized or
shared, which is what the "always serialised" column says; a field it may leave out is kept when it
does, as 0.0.x kept a competitor's country, a player's full name and a tournament's abbreviation. A
response from any other endpoint only fills what is absent and was never written by the
authoritative one. The fields the feed owns (the match status section) are the exception: they are
not in the entity caches but in the live state, with the feed watermarks. This is the table the
entity caches (tickets 17 to 19) are built from, with what 0.0.x did, and the questions still open.

Schema: the vendored oddsfeedschema (`vendor/oddsfeedschema/SOURCE`); XSD paths are relative to
`vendor/oddsfeedschema/schema/`. The 0.0.x code is `library/src/main/kotlin/com/oddin/oddsfeedsdk/cache/entity/*.kt`
on the `release/0.x` line. The 1.0 getters are in `odds-feed/src/main/java/com/oddin/oddsfeedsdk/api/entities/sportevent/`.

## Endpoint key

| key | endpoint | root type | called by SDK? |
|---|---|---|---|
| SUM | `/sports/{lang}/sport_events/{id}/summary` | rest/match_summary.xsd:6-13 | yes |
| FIX | `/sports/{lang}/sport_events/{id}/fixture` | rest/fixtures_fixture.xsd:8-25 (`fixture` extends `sportEvent`, :18) | yes |
| SCH | `/schedules/pre/schedule?start&limit`, `/schedules/live/schedule`, `/schedules/{date}/schedule` | rest/schedule.xsd:5-11 | yes |
| CP | `/sports/{lang}/competitors/{id}/profile` | rest/competitor.xsd:7-19 | yes |
| PP | `/sports/{lang}/players/{id}/profile` | rest/player.xsd:5-11 | yes |
| TI | `/sports/{lang}/tournaments/{id}/info` | rest/tournament_info.xsd:5-12 | yes |
| ST | `/sports/{lang}/sports/{sportId}/tournaments` | rest/sport_tournaments.xsd:6-19 | yes |
| SL | `/sports/{lang}/sports` | rest/sports.xsd:5-11 | yes |
| FCH | `/sports/{lang}/fixtures/changes` | rest/fixture_changes.xsd:5-16 | yes; carries only `sport_event_id` + `update_time` (:14-15), **no cached field** |
| MSD | `/descriptions/{lang}/match_status` | rest/match_status.xsd:5-17 | yes (catalog) |
| TS | tournament schedule | rest/tournament_schedule.xsd:6-19 | **no** – neither 0.0.x `ApiClient.kt` nor 1.0 `internal/rest/ApiClient.java` calls it; 0.0.x side-load branches for it are dead |
| TL | tournaments list | rest/tournaments.xsd:5-11 | **no** – same as TS |
| OC | feed `odds_change` | feed/odds_change.xsd:7 (`sport_event_status`, minOccurs=0) | feed |
| FXC | feed `fixture_change` | feed/fixture_change.xsd:4-9 (only `messageAttributes` + `change_type`) | feed; carries **no cached field**, 0.0.x uses it only to invalidate match, tournament and fixture caches (`Cache.kt:32-38`, `CacheManager.kt:68-76`) |

Element paths used in the tables:

- **sport event element** `SE`: SUM `match_summary/sport_event` (rest/match_summary.xsd:9, minOccurs=1); FIX `fixtures_fixture/fixture` (rest/fixtures_fixture.xsd:11); SCH `schedule/sport_event` (rest/schedule.xsd:8); TS `tournament_schedule/sport_events/sport_event` (rest/tournament_schedule.xsd:10,17). Type `sportEvent` = rest/types/sport_event.xsd:7-16.
- **tournament element** `TE`: `SE/tournament` (rest/types/sport_event.xsd:9, **minOccurs=0**) in SUM/FIX/SCH/TS; TI `tournament_info/tournament` (rest/tournament_info.xsd:8, `tournamentExtended`); ST `sport_tournaments/tournaments/tournament` (rest/sport_tournaments.xsd:10,17, plain `tournament`); TS `tournament_schedule/tournament` (rest/tournament_schedule.xsd:9); TL `tournaments/tournament` (rest/tournaments.xsd:8). Type = rest/types/tournament.xsd:7-18.
- **team element** `TM`: `SE/competitors/competitor` (rest/types/sport_event.xsd:10,20, `teamCompetitor`) in SUM/FIX/SCH/TS; CP `competitor_profile/competitor` (rest/competitor.xsd:10, `teamExtended`, minOccurs=1); TI `tournament_info/tournament/competitors/competitor` (rest/types/tournament.xsd:28,37) **and** `tournament_info/competitors/competitor` (rest/tournament_info.xsd:9); TL/TS via `tournamentExtended/competitors` (rest/types/tournament.xsd:28). Type `team` = rest/types/team.xsd:6-14.
- **sport element** `SP`: `TE/sport` (rest/types/tournament.xsd:10, required) wherever a TE appears; ST `sport_tournaments/sport` (rest/sport_tournaments.xsd:9); SL `sports/sport` (rest/sports.xsd:8, `sportExtended`); CP `competitor/sport` (rest/types/team.xsd:20, minOccurs=0). Type = rest/types/sport.xsd:5-9.
- **player element** `PL`: PP `player_profile/player` (rest/player.xsd:8, minOccurs=1); CP `competitor_profile/players/player` (rest/competitor.xsd:11,14). Type = rest/types/player.xsd:6-13.
- **status element**: REST `match_summary/sport_event_status` (rest/match_summary.xsd:10, minOccurs=1), type rest/types/sport_event_status.xsd:14-35; feed `odds_change/sport_event_status` (feed/odds_change.xsd:7), type feed/types.xsd:78-90. `period_score` = common/period_score.xsd:15-50, `scoreboard` = common/scoreboard.xsd:17-61.

"Always serialised" means: does the authoritative endpoint's XSD force the field to appear whenever the value exists, so omission can be read as "cleared". `required` = yes; `optional`/`minOccurs=0` = not provable from the XSD.

## Match (sport event) – `Match`, `Competition`, `SportEvent`

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| id | `SportEvent.getId` | no | `SE@id` (rest/types/sport_event.xsd:36, no `use` = optional) SUM, FIX, SCH, TS | key | – | key (XSD optional, uncertain) | key |
| refId | `SportEvent.getRefId` (deprecated) | no | **none** in v1.1.0 (no `ref_id` in any XSD); 0.0.x read it from its own bindings (`RASportEvent.java:57`) | – | REST | – | MatchCache, set on insert only (`MatchCache.kt:171`) |
| name | `SportEvent.getName(Locale)` | **yes** | `SE@name` (rest/types/sport_event.xsd:37, optional) SUM, FIX, SCH, TS | SUM in that locale | REST | no (optional; every fixture has it) | MatchCache: SUM (load) and FIX, SCH, TS (side-load), all overwrite `name[locale]` (`MatchCache.kt:192`); a side-load also sets the loaded-locale mark (`loadedLocales = name.keys`, :222-223) so SUM is never fetched for that locale |
| sportId | `SportEvent.getSportId` | no | `SE/tournament/sport@id` (rest/types/sport_event.xsd:9 minOccurs=0 → rest/types/tournament.xsd:10 → rest/types/sport.xsd:6) SUM, FIX, SCH, TS | SUM | REST | no (tournament minOccurs=0) | MatchCache: every write overwrites; NPE when `tournament` absent (`MatchCache.kt:175,185`) |
| scheduledTime | `SportEvent.getScheduledTime` | no | `SE@scheduled` (rest/types/sport_event.xsd:39, optional) SUM, FIX, SCH, TS | SUM | REST | no | MatchCache: every write overwrites incl. null (:183) |
| scheduledEndTime | `SportEvent.getScheduledEndTime` | no | `SE@scheduled_end` (rest/types/sport_event.xsd:40, optional) SUM, FIX, SCH, TS | SUM | REST | no | MatchCache: every write overwrites incl. null (:184) |
| liveOddsAvailability | `SportEvent.getLiveOddsAvailability` | no | `SE@liveodds` (rest/types/sport_event.xsd:14, optional) SUM, FIX, SCH, TS | SUM | REST | no | MatchCache: every write; `fromApiEvent(null)` = AVAILABLE, so an omitted attribute flips to AVAILABLE (:187, `LiveOddsAvailability.java` `fromApiEvent`) |
| competitors (ids + order) | `Competition.getCompetitors` | no | `SE/competitors/competitor@id` (rest/types/sport_event.xsd:10 minOccurs=0, :20; rest/types/team.xsd:7) SUM, FIX, SCH, TS | SUM | REST | no (element minOccurs=0) | MatchCache: every write replaces the list, absent element → empty list (:151,182) |
| qualifier | `TeamCompetitor.getQualifier` | no | `SE/competitors/competitor@qualifier` (rest/types/team.xsd:30, optional) SUM, FIX, SCH, TS | SUM | REST | no | MatchCache: stored with the id in `CompetitorID` (:151,203-206) |
| home/away competitor | `Match.getHomeCompetitor`, `getAwayCompetitor` | no | derived: 0.0.x competitors[0]/[1] if sportFormat=CLASSIC and exactly 2 (`MatchCache.kt:297-327`), **positional, not by qualifier**; 1.0 by qualifier (question 10) | (derived) | REST | – | derived |
| tournament id | `Match.getTournament` | no | `SE/tournament@id` (rest/types/sport_event.xsd:9; rest/types/tournament.xsd:12) SUM, FIX, SCH, TS | SUM | REST | no (tournament minOccurs=0) | MatchCache: every write overwrites; NPE when absent (:176,186) |
| extraInfo | `Match.getExtraInfo` | no | `SE/extra_info/info@key,@value` (rest/types/sport_event.xsd:11 minOccurs=0, :24-33) SUM, FIX, SCH, TS | SUM | REST | no (minOccurs=0) | MatchCache: every write overwrites incl. null (:179,189) |
| sportFormat | `Match.getSportFormat` | no | derived from `extra_info` key `sport_format` (`MatchCache.kt:38,153-166`); absent → CLASSIC, unknown value → UNKNOWN | (derived from extraInfo) | REST | – | MatchCache, every write |
| status | `Match.getStatus`, `Competition.getStatus` | – | see Match status | SUM | FEED/REST | – | MatchStatusCache |
| fixture | `Match.getFixture` | – | see Fixture | FIX | REST | – | FixtureCache |
| (not exposed) `SE@status`, `@type`, `@start_time_tbd` | – | – | rest/types/sport_event.xsd:15,38,41 | – | – | – | not read (SE@status could fill match-status `status`) |

## Fixture – `Fixture`, `TvChannel`

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| startTime | `Fixture.getStartTime` | no | `fixture@start_time` (rest/fixtures_fixture.xsd:22, optional) FIX only | FIX | REST | no | FixtureCache: FIX, fetched once (only when entry absent) in `locales.first()` (`FixtureCache.kt:41-49,69-85`) |
| extraInfo | `Fixture.getExtraInfo` | no | `fixture/extra_info` (inherited, rest/types/sport_event.xsd:11, minOccurs=0); the same element is also in SUM, SCH, TS but feeds `Match.getExtraInfo` there | FIX | REST | no | FixtureCache: FIX only (:79) |
| tvChannels | `Fixture.getTvChannels`; `TvChannel.getName/getStreamUrl/getLanguage` | unclear (0.0.x: no) | `fixture/tv_channels/tv_channel` (rest/fixtures_fixture.xsd:20 minOccurs=0, :35; `@name` required :40, `@language` optional :41, `@stream_url` **optional** :43, `@start_time` :42 not exposed) FIX only | FIX | REST | no (element minOccurs=0) | FixtureCache: FIX only (:80); `TvChannelImpl.streamUrl` is non-null, so a channel without `stream_url` throws outside the try and fails the whole fixture load |

## Competitor – `Competitor`, `TeamCompetitor`

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| id | `getId` | no | `TM@id` (rest/types/team.xsd:7, required) | key | – | key | key |
| refId | `getRefId` (deprecated) | no | **none** in v1.1.0; 0.0.x binding `RATeam.java:51` | – | REST | – | CompetitorCache, insert only (`CompetitorCache.kt:179`, `val`) |
| name | `getNames`, `getName(Locale)` | **yes** | `TM@name` (rest/types/team.xsd:8, required): CP, SUM, FIX, SCH, TI (both paths), TS, TL | CP in that locale | REST | **yes** | CompetitorCache: CP only; overwrite `name[locale]` (:190) |
| abbreviation | `getAbbreviations`, `getAbbreviation(Locale)` | **yes** | `TM@abbreviation` (rest/types/team.xsd:9, required; fixtures send `""` when none) same endpoints as name | CP in that locale | REST | **yes** | CompetitorCache: CP; written when non-null, never removed (:191-193) |
| country | `getCountries`, `getCountry(Locale)` | **yes** | `TM@country` (rest/types/team.xsd:11, optional) same endpoints as name | CP in that locale | REST | no | CompetitorCache: CP; written when non-null, **never cleared** (:195-197) |
| countryCode | `getCountryCode` | no | `TM@country_code` (rest/types/team.xsd:12, optional) same endpoints | CP | REST | no | CompetitorCache: CP; overwritten incl. null (:187) |
| virtual | `getVirtual` | no | `TM@virtual` (rest/types/team.xsd:13, optional) same endpoints | CP | REST | no | CompetitorCache: CP; overwritten incl. null (:186) |
| underage | `getUnderage` (1.0); 0.x also `getUnderageStatus` (derived, `Competitor.kt:16-19` on release/0.x) | no | `TM@underage` (rest/types/team.xsd:10, required) same endpoints | CP | REST | **yes** | CompetitorCache: CP, **insert only** (`val`, :182,262) – never refreshed until expiry |
| iconPath | `getIconPath` | no | `teamExtended@icon_path` (rest/types/team.xsd:22, optional) **CP only** | CP | REST | no | CompetitorCache: CP, **insert only** (`val`, :183,263) |
| players (ids) | `getPlayers` | no | `competitor_profile/players/player@id` (rest/competitor.xsd:11 element required, :14 player minOccurs=0 → `<players/>` when empty) **CP only** | CP | REST | **yes** (container required) | CompetitorCache: only `loadAndCacheItem` → `fetchCompetitorProfileWithPlayers` clears and replaces (:199-207); the side-load path uses `fetchCompetitorProfile` → `RATeamExtended` and never touches players; `getPlayers()` refetches on **every** call while the list is empty (:329-332) |
| (not exposed) competitor sport | – | – | `teamExtended/sport` (rest/types/team.xsd:20) CP | – | – | – | not read |

0.0.x side-load for competitors: on every SUM, FIX, SCH, TI, TS response it fans out one CP fetch per competitor id in the response locale (`CompetitorCache.kt:76-87,212-254`); it never uses the embedded team attributes. TI side-load reads the **top-level** `tournament_info/competitors` (:81), while TournamentCache reads `tournament_info/tournament/competitors` (`TournamentCache.kt:182-189`).

## Player – `Player`

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| id | `getId` | no | `PL@id` (rest/types/player.xsd:7, required) PP, CP | key | – | key | key |
| name | `getNames`, `getName(Locale)` | **yes** | `PL@name` (rest/types/player.xsd:8, required) PP, CP | PP in that locale | REST | **yes** | PlayerCache: PP, overwrite (`PlayerCache.kt:140`) |
| fullName | `getFullNames`, `getFullName(Locale)` | **yes** | `PL@full_name` (rest/types/player.xsd:10, optional) PP, CP | PP in that locale | REST | no | PlayerCache: PP, written when non-null, **never cleared** (:141-143) |
| sportID | `getSportIDs`, `getSportID(Locale)` | shaped per locale, value locale-independent | `PL@sport` (rest/types/player.xsd:9, required) PP, CP | PP | REST | **yes** | PlayerCache: PP, overwrite per locale (:144) |
| underage | **not in 1.0 `Player`**; release/0.x `Player.underage: UnderageStatus?` (`Player.kt:14`) | no | `PL@underage` (rest/types/player.xsd:12, optional) PP, CP | PP | REST | no | PlayerCache: PP, written when present, else keeps previous (:145) |

0.0.x side-load for players: on every CP response it fans out one PP fetch per player (`PlayerCache.kt:72-79,150-192`); embedded CP player attributes are never used.

## Tournament – `Tournament`, `LongTermEvent`, `SportEvent`

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| id | `getId` | no | `TE@id` (rest/types/tournament.xsd:12, required) | key | – | key | key |
| refId | `getRefId` (deprecated) | no | **none** in v1.1.0; 0.0.x binding `RATournament.java:63` | – | REST | – | TournamentCache, insert only (`TournamentCache.kt:162`) |
| name | `getName(Locale)` | **yes** | `TE@name` (rest/types/tournament.xsd:13, required) TI, ST, SUM, FIX, SCH, TS, TL | TI in that locale | REST | **yes** | TournamentCache: TI (load) + FIX, SUM, SCH, ST, TS, TL (side-load, overwrite, :179); side-load sets the loaded-locale mark (`loadedLocales = name.keys`, :213-214) |
| abbreviation | `getAbbreviation(Locale)` | **yes** | `TE@abbreviation` (rest/types/tournament.xsd:17, optional; fixtures send `""`) same endpoints | TI in that locale | REST | no | TournamentCache: every write, `ConcurrentHashMap.put(locale, null)` throws NPE when absent → the whole write is lost (:180) |
| sportId / sport | `getSportId`, `LongTermEvent.getSport` | no | `TE/sport@id` (rest/types/tournament.xsd:10, required element) same endpoints | TI | REST | **yes** | TournamentCache: every write overwrites (:165,173) |
| scheduledTime | `getScheduledTime` | no | `TE@scheduled` (rest/types/tournament.xsd:15, optional) same endpoints | TI | REST | no | TournamentCache: every write overwrites incl. null (:166,174) |
| scheduledEndTime | `getScheduledEndTime` | no | `TE@scheduled_end` (rest/types/tournament.xsd:16, optional) same endpoints | TI | REST | no | same (:167,175) |
| startDate | `getStartDate` | no | `TE/tournament_length@start_date` (rest/types/tournament.xsd:9 minOccurs=0, :20 optional) same endpoints | TI | REST | no | TournamentCache: every write overwrites incl. null, so an SCH/SUM without `tournament_length` erases it (:163,171) |
| endDate | `getEndDate` | no | `TE/tournament_length@end_date` (rest/types/tournament.xsd:21) same endpoints | TI | REST | no | same (:164,172) |
| riskTier | `getRiskTier` | no | `TE@risk_tier` (rest/types/tournament.xsd:14, required) same endpoints | TI | REST | **yes** | TournamentCache: every write overwrites (:168,176) |
| competitors (ids) | `getCompetitors` | no | `tournamentExtended/competitors/competitor@id` (rest/types/tournament.xsd:28 minOccurs=0, :37) in TI, TS, TL; **also** `tournament_info/competitors` (rest/tournament_info.xsd:9, minOccurs=0) | TI | REST | no (both minOccurs=0) | TournamentCache: TI `tournament/competitors` only, **union-add, never removed**, only when non-empty (:182-189); when null, `getTournamentCompetitors` fetches TI on every call (:106-111) |
| liveOddsAvailability | `getLiveOddsAvailability` | no | none (tournament type has no `liveodds`) | – | – | – | constant NOT_AVAILABLE (:243-244) |
| (not exposed) icon_path | – | – | `tournamentExtended@icon_path` (rest/types/tournament.xsd:30) | – | – | – | not read |

## Sport – `Sport`, `SportSummary`

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| id | `getId` | no | `SP@id` (rest/types/sport.xsd:6, required) | key | – | key | key |
| refId | `getRefId` (deprecated) | no | **none** in v1.1.0; 0.0.x binding `RASport.java:42` | – | REST | – | SportDataCache: every write overwrites, incl. null from TI side-load (`SportDataCache.kt:193`) |
| name | `getNames`, `getName(Locale)` | **yes** | `SP@name` (rest/types/sport.xsd:7, required) SL, ST, CP, and TE/sport in TI, ST, SUM, FIX, SCH, TS, TL | SL in that locale | REST (catalog) | **yes** | SportDataCache: SL (load) + TI, TS (side-load, :74-77) overwrite (:191); side-load sets the per-sport loaded mark (`name.keys`, :213-214) |
| abbreviation | `getAbbreviation(Locale)` | **yes** | `SP@abbreviation` (rest/types/sport.xsd:8, required) same endpoints as name | SL in that locale | REST (catalog) | **yes** | SportDataCache: same as name (:192) |
| iconPath | `getIconPath(Locale)` | API per locale; 0.0.x stores one value | `sportExtended@icon_path` (rest/types/sport_extended.xsd:8, required; `""` when none) **SL only** | SL | REST (catalog) | **yes** | SportDataCache: SL, **but** the TI side-load overwrites it with null (`RASport.iconPath` absent there, :194) and then satisfies the loaded mark, so SL never repairs it |
| tournaments (ids) | `Sport.getTournaments` | no | `sport_tournaments/tournaments/tournament@id` (rest/sport_tournaments.xsd:10 minOccurs=0, :17) ST; single ids also from TI/TS `TE/sport` | ST | REST | no (minOccurs=0) | SportDataCache: ST (`getSportTournaments`, union-add, :118-139) **plus** TI/TS side-load adds one id (:153); ST is fetched only while the set is null (:257-260), so one TI side-load freezes a partial list |

SportDataCache has no expiry and one global loaded-locale set for `getSports` (`SportDataCache.kt:42-47`).

## Match status (sport event status) – `MatchStatus`, `CompetitionStatus`, `PeriodScore`, `Scoreboard`

REST path `SUM/sport_event_status` = rest/types/sport_event_status.xsd; feed path `OC/sport_event_status` = feed/types.xsd:78-90. No other endpoint or feed message carries these fields (SE@status in SUM/FIX/SCH is a string status of the event, not read).

In 1.0 the fields whose owner is **FEED** are not entity-cache fields. They are kept in the live state (`internal/cache/LiveState`), one record per match next to its feed watermarks: an `odds_change` writes them unless it is older than its producer's watermark, and a summary writes them only while no producer has written within the match status age, 20 minutes. So the "authoritative" column names the REST source that writes them while the feed is quiet, not an entity cache's owner. A write says per field what it carries, what it clears and, by leaving it out, what it keeps; which of these an omission means is question 6. The match status description (MSD) and the winner stay with REST.

| field | public getter(s) | localized | carried by (endpoints / feed) | authoritative | owner | always serialised | 0.0.x writer |
|---|---|---|---|---|---|---|---|
| status | `CompetitionStatus.getStatus` | no | REST `@status` string (rest/types/sport_event_status.xsd:19, required); OC `@status` int (feed/types.xsd:84, required) | SUM | **FEED** | **yes** | MatchStatusCache: OC (null → Unknown, `MatchStatusCache.kt:180`); SUM only if `generated_at` newer than stored watermark (:226-241, :247) |
| matchStatusId | `getMatchStatusId` | no | REST `@match_status_code` (rest/types/sport_event_status.xsd:20, **optional**); OC `@match_status` (feed/types.xsd:85, required) | SUM | **FEED** | no in SUM (yes in feed) | OC (:182), SUM (:249), overwrite incl. null |
| matchStatus description | `getMatchStatus()`, `getMatchStatus(Locale)` | **yes** | derived: matchStatusId → MSD `match_status@id,@description` (rest/match_status.xsd:8,15-16) | MSD (catalog) | REST (catalog) | – | `LocalizedStaticDataCache` (`MatchStatusCache.kt:466-473`) |
| homeScore | `getHomeScore` | no | REST `@home_score` (rest/types/sport_event_status.xsd:22, optional); OC `@home_score` (feed/types.xsd:87, optional) | SUM | **FEED** | no | OC and SUM: absent keeps previous, else 0.0 (:185,251) |
| awayScore | `getAwayScore` | no | REST `@away_score` (:23, optional); OC (feed/types.xsd:88, optional) | SUM | **FEED** | no | same (:186,252) |
| periodScores | `getPeriodScores`; `PeriodScore.*` | no | `period_scores/period_score` REST (rest/types/sport_event_status.xsd:16 minOccurs=0, :39); OC (feed/types.xsd:80 minOccurs=0, :98); attrs common/period_score.xsd:16-20 required (type, number, match_status_code, home/away score), :22-49 optional per sport | SUM | **FEED** | no (element minOccurs=0) | OC and SUM replace wholesale; **absent element → empty list** (:181,248), unlike scores which are kept |
| scoreboard – score part | `getScoreboard`; `Scoreboard` rounds/kills/turrets/towers/gold/goals/points/games/runs/wickets/overs/balls/batting/coin toss/inning/current_ct_team/current_def_team | no | `scoreboard` REST (rest/types/sport_event_status.xsd:17, minOccurs=0); OC (feed/types.xsd:81, minOccurs=0); attrs common/scoreboard.xsd:18-55, all optional, one sport subset per message | SUM | **FEED** | no | OC and SUM: replaced when element present, kept when absent (:189,255) |
| scoreboard – match clock | `Scoreboard.getTime`, `getGameTime`, `getElapsedTime`, `getRemainingGameTime` | no | same element, common/scoreboard.xsd:57-60 (optional) | SUM | **FEED** (match clock) | no | as scoreboard |
| isScoreboardAvailable | `isScoreboardAvailable` | no | REST `@scoreboard_available` (rest/types/sport_event_status.xsd:24, optional); OC (feed/types.xsd:89, optional) | SUM | FEED? (see Q5) | no | OC and SUM overwrite; bindings use primitive `boolean`, so absent = false |
| winnerId | `CompetitionStatus.getWinnerId` | no | REST `@winner_id` (rest/types/sport_event_status.xsd:21, optional); **OC `@winner_id` (feed/types.xsd:86, optional)**, e.g. test/fixtures/feed/odds_change/odds_change_closed_with_winner.xml | SUM | REST (per design; see Q4) | no, but omission = "no winner" is the design's intended clear | SUM only; newer snapshot replaces incl. null, older snapshot still merges a non-null winner (:235-239,246); feed copies the previous one (:177); the 0.0.x binding `OFSportEventStatus.java` has **no** `winner_id` |
| properties | `CompetitionStatus.getProperties` | no | none | – | – | – | never written (always empty map, :190,256) |
| (not exposed / legacy) | – | – | `status_code`, `aggregate_*` (rest/types/sport_event_status.xsd:31-34) | – | – | – | not read |

0.0.x MatchStatusCache fetches SUM in `Locale.ENGLISH` only (`MatchStatusCache.kt:119`), side-loads every SUM response of any locale (:82-99), and orders REST against feed by comparing `generated_at` with the feed `timestamp` (:202-241). The design forbids that comparison (NEXT.md "Ownership and ordering").

## Open questions and ambiguities

1. **`ref_id` is gone from schema v1.1.0.** No REST XSD declares `ref_id` for sport event, team, tournament or sport. 0.0.x read it from its own bindings (`RASportEvent.java:57`, `RATeam.java:51`, `RATournament.java:63`, `RASport.java:42`). The 1.0 `getRefId()` Javadoc says "the feed never sends this value". Suggestion: no authoritative endpoint; always null. **Settled by ticket 20:** always null, and reading it loads nothing.
2. **Match versus fixture.** FIX is a `sportEvent` extension (rest/fixtures_fixture.xsd:18). It describes the match itself as fully as SUM does. The table makes SUM authoritative for match fields and FIX for `Fixture` fields. That gives two `extraInfo` fields, one on `Match` and one on `Fixture`, from the same element in two responses, and they can disagree. This needs a decision. **Settled by ticket 17:** both are kept, as in 0.0.x: the match has the extra info of its summary, the fixture its own.
3. **Tournament competitor list: which element?** TI has `tournament/competitors` (rest/types/tournament.xsd:28) and top-level `competitors` (rest/tournament_info.xsd:9). 0.0.x TournamentCache reads the first and CompetitorCache the second. The fixture `tournament_info.xml` has neither. We need to find out from the server which one it fills.
4. **Winner on the feed.** feed/types.xsd:86 declares `winner_id` and a feed fixture sends it. 0.0.x drops it because its binding lacks the attribute, and its comment says "the feed never carries a winner". The design puts the winner under REST ownership. Should OC fill-only write it, or is SUM the only writer? **Settled by ticket 17:** SUM only, as in 0.0.x; the feed's `winner_id` is not written.
5. **Owner of `isScoreboardAvailable` and `matchStatusId`.** The design names "live status, scores, period scores, match clock". The table treats both fields as FEED because they travel with the live status. Scoreboard fields other than the clock are treated as "scores". Please confirm. **Settled by ticket 17:** both are in the live state with the other feed-owned fields.
6. **Omission of feed-owned fields in SUM.** Scores, period scores, scoreboard and `match_status_code` are all optional in the REST XSD. So omission cannot safely mean "cleared". 0.0.x keeps scores but empties period scores on omission, which is inconsistent. `status` is the only required one. A live-state write can express either per field (kept when not put, or cleared), so this is a decision for ticket 17, not a limit of the cache. **Settled by ticket 17:** a field left out keeps its value, from the feed and from SUM alike; that includes period scores, which 0.0.x emptied.
7. **`sport_event_status` minOccurs=1 in SUM** (rest/match_summary.xsd:10), but 0.0.x guards against a summary without it ("A scheduled event may carry no status yet", `MatchStatusCache.kt:92-93`). Either the XSD or that guard is wrong. **Settled by ticket 17:** a summary without it writes the match and leaves the live state alone.
8. **`SE@id`, `SE@name` and `SE/tournament` are optional** in the XSD (rest/types/sport_event.xsd:9,36-37). Omission-clear for a match's sport/tournament id cannot be proven. 0.0.x throws a NullPointerException when `tournament` is absent. **Settled by ticket 17:** a missing tournament leaves the sport and tournament ids out instead of failing; an id that is not a URN is left out too.
9. **`liveodds` omission.** `fromApiEvent(null)` returns AVAILABLE. A response that omits the attribute therefore reads as "available" (the SUM fixture has no `liveodds`). Should omission mean unknown/keep instead? **Ticket 17:** the cache keeps the attribute as sent; the façade (ticket 20) reads its absence as 0.0.x did. **Settled by ticket 20:** absent reads as `AVAILABLE`, as `fromApiEvent(null)` did in 0.0.x; the Go SDK reads it as not available.
10. **`sport_format` values.** The FIX and SCH fixtures carry `sport_format="esports"`. `SportFormat` knows only classic/race/unknown, so these matches get UNKNOWN and `getHomeCompetitor`/`getAwayCompetitor` return null. Home/away is also positional, not taken from `qualifier`. **Settled by ticket 20:** `esports` still reads as `UNKNOWN`, but home and away are the competitors qualified `home` and `away`, whatever their order, for any format but a race - as the Go SDK takes them, which also leaves them unset for its unknown formats. A race, or a match without one of each, has neither, and returns null without failing (KD-20 in `system-tests/KNOWN-DIFFERENCES.md`).
11. **Player underage.** release/0.x exposes `Player.underage` and `Competitor.underageStatus`. The 1.0 `Player` and `Competitor` interfaces do not, although the schema has `player@underage` (rest/types/player.xsd:12). There is a compatibility gap on the 1.0 side.
12. **Localized or not.** `Player.getSportIDs` is per-locale in shape but locale-independent in value. `SportSummary.getIconPath(Locale)` is per-locale in the API, but 0.0.x stores one value. TV channel name and language may be localized, but 0.0.x fetches FIX once, in the first locale. Each needs a decision. **Settled by ticket 20:** `getSportIDs` has the one value for every locale whose profile is loaded; `getIconPath(Locale)` loads the sport list in that locale and reads the one value; the fixture stays loaded once, in the default locale (ticket 17).
13. **`TvChannel.getStreamUrl()` is non-null, but `stream_url` is optional** (rest/fixtures_fixture.xsd:43). **Settled by ticket 17:** a channel without one has an empty stream URL; 0.0.x failed the whole fixture.
14. **Tournament `abbreviation` is optional** (rest/types/tournament.xsd:17). In 0.0.x a missing one loses the whole tournament write (ConcurrentHashMap null put). Fixtures send `""`, so the server probably always sends it, but the XSD does not guarantee it.
15. **TS and TL are never called** by either SDK, so their 0.0.x side-load branches are dead. Should they be dropped from the table, or kept as future fill-only sources?
16. **Sports as a catalog.** The design lists catalogs (refresh-after-write, provenance) separately from entity caches. Sport name, abbreviation and icon from SL fit the catalog model. Its `tournaments` list from ST is closer to an entity field. Which cache owns it?
