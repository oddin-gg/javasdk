package com.oddin.oddsfeed.benchmarks;

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
 * it to decoding what JAXB decodes.
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
            var change = new OFOddsChange();
            for (int i = 0; i < xml.getAttributeCount(); i++) {
                String value = xml.getAttributeValue(i);
                switch (xml.getAttributeLocalName(i)) {
                    case "product" -> change.setProduct(Integer.parseInt(value));
                    case "event_id" -> change.setEventId(value);
                    case "timestamp" -> change.setTimestamp(Long.parseLong(value));
                    case "request_id" -> change.setRequestId(Long.parseLong(value));
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
                case "status" -> status.setStatusRaw(Integer.valueOf(value));
                case "match_status" -> status.setMatchStatus(Integer.parseInt(value));
                case "winner_id" -> status.setWinnerId(value);
                case "home_score" -> status.setHomeScore(Double.valueOf(value));
                case "away_score" -> status.setAwayScore(Double.valueOf(value));
                case "scoreboard_available" -> status.setScoreboardAvailable(Boolean.valueOf(value));
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
                    case "number" -> score.setNumber(Integer.parseInt(value));
                    case "match_status_code" -> score.setMatchStatusCode(Integer.parseInt(value));
                    case "home_score" -> score.setHomeScore(Double.parseDouble(value));
                    case "away_score" -> score.setAwayScore(Double.parseDouble(value));
                    case "home_won_rounds" -> score.setHomeWonRounds(Integer.valueOf(value));
                    case "away_won_rounds" -> score.setAwayWonRounds(Integer.valueOf(value));
                    case "home_kills" -> score.setHomeKills(Integer.valueOf(value));
                    case "away_kills" -> score.setAwayKills(Integer.valueOf(value));
                    case "home_goals" -> score.setHomeGoals(Integer.valueOf(value));
                    case "away_goals" -> score.setAwayGoals(Integer.valueOf(value));
                    case "home_points" -> score.setHomePoints(Integer.valueOf(value));
                    case "away_points" -> score.setAwayPoints(Integer.valueOf(value));
                    case "home_games" -> score.setHomeGames(Integer.valueOf(value));
                    case "away_games" -> score.setAwayGames(Integer.valueOf(value));
                    case "home_runs" -> score.setHomeRuns(Integer.valueOf(value));
                    case "away_runs" -> score.setAwayRuns(Integer.valueOf(value));
                    case "home_wickets_fallen" -> score.setHomeWicketsFallen(Integer.valueOf(value));
                    case "away_wickets_fallen" -> score.setAwayWicketsFallen(Integer.valueOf(value));
                    case "home_overs_played" -> score.setHomeOversPlayed(Integer.valueOf(value));
                    case "away_overs_played" -> score.setAwayOversPlayed(Integer.valueOf(value));
                    case "home_balls_played" -> score.setHomeBallsPlayed(Integer.valueOf(value));
                    case "away_balls_played" -> score.setAwayBallsPlayed(Integer.valueOf(value));
                    case "home_won_coin_toss" -> score.setHomeWonCoinToss(Boolean.valueOf(value));
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
                case "current_ct_team" -> board.setCurrentCTTeam(Integer.valueOf(value));
                case "current_def_team" -> board.setCurrentDefenderTeam(Integer.valueOf(value));
                case "home_won_rounds" -> board.setHomeWonRounds(Integer.valueOf(value));
                case "away_won_rounds" -> board.setAwayWonRounds(Integer.valueOf(value));
                case "current_round" -> board.setCurrentRound(Integer.valueOf(value));
                case "home_kills" -> board.setHomeKills(Integer.valueOf(value));
                case "away_kills" -> board.setAwayKills(Integer.valueOf(value));
                case "home_destroyed_turrets" -> board.setHomeDestroyedTurrets(Integer.valueOf(value));
                case "away_destroyed_turrets" -> board.setAwayDestroyedTurrets(Integer.valueOf(value));
                case "home_destroyed_towers" -> board.setHomeDestroyedTowers(Integer.valueOf(value));
                case "away_destroyed_towers" -> board.setAwayDestroyedTowers(Integer.valueOf(value));
                case "home_gold" -> board.setHomeGold(Integer.valueOf(value));
                case "away_gold" -> board.setAwayGold(Integer.valueOf(value));
                case "home_goals" -> board.setHomeGoals(Integer.valueOf(value));
                case "away_goals" -> board.setAwayGoals(Integer.valueOf(value));
                case "home_points" -> board.setHomePoints(Integer.valueOf(value));
                case "away_points" -> board.setAwayPoints(Integer.valueOf(value));
                case "home_games" -> board.setHomeGames(Integer.valueOf(value));
                case "away_games" -> board.setAwayGames(Integer.valueOf(value));
                case "home_runs" -> board.setHomeRuns(Integer.valueOf(value));
                case "away_runs" -> board.setAwayRuns(Integer.valueOf(value));
                case "home_wickets_fallen" -> board.setHomeWicketsFallen(Integer.valueOf(value));
                case "away_wickets_fallen" -> board.setAwayWicketsFallen(Integer.valueOf(value));
                case "home_overs_played" -> board.setHomeOversPlayed(Integer.valueOf(value));
                case "away_overs_played" -> board.setAwayOversPlayed(Integer.valueOf(value));
                case "home_balls_played" -> board.setHomeBallsPlayed(Integer.valueOf(value));
                case "away_balls_played" -> board.setAwayBallsPlayed(Integer.valueOf(value));
                case "home_batting" -> board.setHomeBatting(Boolean.valueOf(value));
                case "away_batting" -> board.setAwayBatting(Boolean.valueOf(value));
                case "home_won_coin_toss" -> board.setHomeWonCoinToss(Boolean.valueOf(value));
                case "inning" -> board.setInning(Integer.valueOf(value));
                case "time" -> board.setTime(Integer.valueOf(value));
                case "game_time" -> board.setGameTime(Integer.valueOf(value));
                case "elapsed_time" -> board.setElapsedTime(Integer.valueOf(value));
                case "remaining_game_time" -> board.setRemainingGameTime(Integer.valueOf(value));
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
                    case "id" -> market.setId(Integer.parseInt(value));
                    case "specifiers" -> market.setSpecifiersRaw(value);
                    case "status" -> market.setStatusRaw(Integer.valueOf(value));
                    case "favourite" -> market.setFavouriteRaw(Integer.valueOf(value));
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
                        case "odds" -> outcome.setOdds(Double.valueOf(value));
                        case "probabilities" -> outcome.setProbabilities(Double.valueOf(value));
                        case "active" -> outcome.setActiveRaw(Integer.valueOf(value));
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
