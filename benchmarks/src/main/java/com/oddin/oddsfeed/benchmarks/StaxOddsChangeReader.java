package com.oddin.oddsfeed.benchmarks;

import static jakarta.xml.bind.DatatypeConverter.parseBoolean;
import static jakarta.xml.bind.DatatypeConverter.parseDouble;
import static jakarta.xml.bind.DatatypeConverter.parseInt;
import static jakarta.xml.bind.DatatypeConverter.parseLong;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChangeMarket;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodScoreType;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodscoresType;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFScoreboard;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A hand-written StAX reader for odds changes into the same generated classes JAXB fills: the
 * alternative the design weighs against JAXB. It exists to be measured, not shipped; the tests hold
 * it to decoding what JAXB decodes. Values are read by XML Schema's rules, as JAXB reads them: a
 * boolean may be {@code 1}, a number may have spaces around it, a double may be {@code INF}.
 */
public final class StaxOddsChangeReader {

    private final XMLInputFactory inputs;

    public StaxOddsChangeReader() {
        inputs = XMLInputFactory.newDefaultFactory();
        inputs.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputs.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputs.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    }

    public OFOddsChange read(byte[] body) throws XMLStreamException {
        XMLStreamReader xml = inputs.createXMLStreamReader(new ByteArrayInputStream(body));
        try {
            xml.nextTag();
            if (!xml.getLocalName().equals("odds_change")) {
                throw new XMLStreamException("not an odds change: " + xml.getLocalName(), xml.getLocation());
            }
            var change = new OFOddsChange();
            for (int i = 0; i < xml.getAttributeCount(); i++) {
                String value = xml.getAttributeValue(i);
                switch (xml.getAttributeLocalName(i)) {
                    case "product" -> change.setProduct(parseInt(value));
                    case "event_id" -> change.setEventId(value);
                    case "timestamp" -> change.setTimestamp(parseLong(value));
                    case "request_id" -> change.setRequestId(parseLong(value));
                    default -> {}
                }
            }
            while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
                switch (xml.getLocalName()) {
                    case "sport_event_status" -> change.setSportEventStatus(status(xml));
                    case "odds" -> change.setOdds(odds(xml));
                    default -> skip(xml);
                }
            }
            return change;
        } finally {
            xml.close();
        }
    }

    private static OFSportEventStatus status(XMLStreamReader xml) throws XMLStreamException {
        var status = new OFSportEventStatus();
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            String value = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "status" -> status.setStatusRaw(parseInt(value));
                case "match_status" -> status.setMatchStatus(parseInt(value));
                case "winner_id" -> status.setWinnerId(value);
                case "home_score" -> status.setHomeScore(parseDouble(value));
                case "away_score" -> status.setAwayScore(parseDouble(value));
                case "scoreboard_available" -> status.setScoreboardAvailable(parseBoolean(value));
                default -> {}
            }
        }
        while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            switch (xml.getLocalName()) {
                case "period_scores" -> status.setPeriodScores(periodScores(xml));
                case "scoreboard" -> status.setScoreboard(scoreboard(xml));
                default -> skip(xml);
            }
        }
        return status;
    }

    private static OFPeriodscoresType periodScores(XMLStreamReader xml) throws XMLStreamException {
        var scores = new OFPeriodscoresType();
        while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            if (!xml.getLocalName().equals("period_score")) {
                skip(xml);
                continue;
            }
            var score = new OFPeriodScoreType();
            for (int i = 0; i < xml.getAttributeCount(); i++) {
                String value = xml.getAttributeValue(i);
                switch (xml.getAttributeLocalName(i)) {
                    case "type" -> score.setType(value);
                    case "number" -> score.setNumber(parseInt(value));
                    case "match_status_code" -> score.setMatchStatusCode(parseInt(value));
                    case "home_score" -> score.setHomeScore(parseDouble(value));
                    case "away_score" -> score.setAwayScore(parseDouble(value));
                    case "home_won_rounds" -> score.setHomeWonRounds(parseInt(value));
                    case "away_won_rounds" -> score.setAwayWonRounds(parseInt(value));
                    case "home_kills" -> score.setHomeKills(parseInt(value));
                    case "away_kills" -> score.setAwayKills(parseInt(value));
                    case "home_goals" -> score.setHomeGoals(parseInt(value));
                    case "away_goals" -> score.setAwayGoals(parseInt(value));
                    case "home_points" -> score.setHomePoints(parseInt(value));
                    case "away_points" -> score.setAwayPoints(parseInt(value));
                    case "home_games" -> score.setHomeGames(parseInt(value));
                    case "away_games" -> score.setAwayGames(parseInt(value));
                    case "home_runs" -> score.setHomeRuns(parseInt(value));
                    case "away_runs" -> score.setAwayRuns(parseInt(value));
                    case "home_wickets_fallen" -> score.setHomeWicketsFallen(parseInt(value));
                    case "away_wickets_fallen" -> score.setAwayWicketsFallen(parseInt(value));
                    case "home_overs_played" -> score.setHomeOversPlayed(parseInt(value));
                    case "away_overs_played" -> score.setAwayOversPlayed(parseInt(value));
                    case "home_balls_played" -> score.setHomeBallsPlayed(parseInt(value));
                    case "away_balls_played" -> score.setAwayBallsPlayed(parseInt(value));
                    case "home_won_coin_toss" -> score.setHomeWonCoinToss(parseBoolean(value));
                    default -> {}
                }
            }
            scores.getPeriodScore().add(score);
            skip(xml);
        }
        return scores;
    }

    private static OFScoreboard scoreboard(XMLStreamReader xml) throws XMLStreamException {
        var board = new OFScoreboard();
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            String value = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "current_ct_team" -> board.setCurrentCTTeam(parseInt(value));
                case "current_def_team" -> board.setCurrentDefenderTeam(parseInt(value));
                case "home_won_rounds" -> board.setHomeWonRounds(parseInt(value));
                case "away_won_rounds" -> board.setAwayWonRounds(parseInt(value));
                case "current_round" -> board.setCurrentRound(parseInt(value));
                case "home_kills" -> board.setHomeKills(parseInt(value));
                case "away_kills" -> board.setAwayKills(parseInt(value));
                case "home_destroyed_turrets" -> board.setHomeDestroyedTurrets(parseInt(value));
                case "away_destroyed_turrets" -> board.setAwayDestroyedTurrets(parseInt(value));
                case "home_destroyed_towers" -> board.setHomeDestroyedTowers(parseInt(value));
                case "away_destroyed_towers" -> board.setAwayDestroyedTowers(parseInt(value));
                case "home_gold" -> board.setHomeGold(parseInt(value));
                case "away_gold" -> board.setAwayGold(parseInt(value));
                case "home_goals" -> board.setHomeGoals(parseInt(value));
                case "away_goals" -> board.setAwayGoals(parseInt(value));
                case "home_points" -> board.setHomePoints(parseInt(value));
                case "away_points" -> board.setAwayPoints(parseInt(value));
                case "home_games" -> board.setHomeGames(parseInt(value));
                case "away_games" -> board.setAwayGames(parseInt(value));
                case "home_runs" -> board.setHomeRuns(parseInt(value));
                case "away_runs" -> board.setAwayRuns(parseInt(value));
                case "home_wickets_fallen" -> board.setHomeWicketsFallen(parseInt(value));
                case "away_wickets_fallen" -> board.setAwayWicketsFallen(parseInt(value));
                case "home_overs_played" -> board.setHomeOversPlayed(parseInt(value));
                case "away_overs_played" -> board.setAwayOversPlayed(parseInt(value));
                case "home_balls_played" -> board.setHomeBallsPlayed(parseInt(value));
                case "away_balls_played" -> board.setAwayBallsPlayed(parseInt(value));
                case "home_batting" -> board.setHomeBatting(parseBoolean(value));
                case "away_batting" -> board.setAwayBatting(parseBoolean(value));
                case "home_won_coin_toss" -> board.setHomeWonCoinToss(parseBoolean(value));
                case "inning" -> board.setInning(parseInt(value));
                case "time" -> board.setTime(parseInt(value));
                case "game_time" -> board.setGameTime(parseInt(value));
                case "elapsed_time" -> board.setElapsedTime(parseInt(value));
                case "remaining_game_time" -> board.setRemainingGameTime(parseInt(value));
                default -> {}
            }
        }
        skip(xml);
        return board;
    }

    private static OFOddsChange.Odds odds(XMLStreamReader xml) throws XMLStreamException {
        var odds = new OFOddsChange.Odds();
        while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            if (!xml.getLocalName().equals("market")) {
                skip(xml);
                continue;
            }
            var market = new OFOddsChangeMarket();
            for (int i = 0; i < xml.getAttributeCount(); i++) {
                String value = xml.getAttributeValue(i);
                switch (xml.getAttributeLocalName(i)) {
                    case "id" -> market.setId(parseInt(value));
                    case "specifiers" -> market.setSpecifiersRaw(value);
                    case "status" -> market.setStatusRaw(parseInt(value));
                    case "favourite" -> market.setFavouriteRaw(parseInt(value));
                    default -> {}
                }
            }
            while (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
                if (!xml.getLocalName().equals("outcome")) {
                    skip(xml);
                    continue;
                }
                var outcome = new OFOddsChangeMarket.OFOutcome();
                for (int i = 0; i < xml.getAttributeCount(); i++) {
                    String value = xml.getAttributeValue(i);
                    switch (xml.getAttributeLocalName(i)) {
                        case "id" -> outcome.setId(value);
                        case "odds" -> outcome.setOdds(parseDouble(value));
                        case "probabilities" -> outcome.setProbabilities(parseDouble(value));
                        case "active" -> outcome.setActiveRaw(parseInt(value));
                        default -> {}
                    }
                }
                market.getOutcome().add(outcome);
                skip(xml);
            }
            odds.getMarket().add(market);
        }
        return odds;
    }

    /** Past the end of the element the reader is at, whatever it holds. */
    private static void skip(XMLStreamReader xml) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }
}
