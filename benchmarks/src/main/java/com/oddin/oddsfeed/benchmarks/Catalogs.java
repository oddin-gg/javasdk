package com.oddin.oddsfeed.benchmarks;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The REST documents a client that starts with nothing cached reads: the market descriptions and the
 * sports, a summary for each match it is sent a message for, and a profile for each competitor of
 * those matches. Nothing is recorded - the documents are generated, the same bytes every time for the
 * same input, so a run can be compared with the last one, and they are valid against the vendored
 * REST schema.
 *
 * <p><b>The sizes are above production's.</b> The production API lists fewer market types and sports
 * than {@link #MARKET_DESCRIPTIONS} and {@link #SPORTS}; they stay above as headroom, and the corpus's
 * market ids need 400 descriptions anyway. {@link #COMPETITORS_PER_MATCH} is the two
 * sides of a match. Every size is a parameter, so a new figure replaces them in one place.
 *
 * <p>Market ids run from 1 up, so every id {@link Corpus} puts in an odds change ({@code 1} to
 * {@link Corpus#MARKET_IDS}) has a description as long as there are that many. Most markets have two
 * or three outcomes, some up to twenty. Markets above the corpus's ids are the ones with a variant: a
 * third of them describe the players of one match, as the API does for a market with dynamic
 * outcomes. A variant's own description, which the API serves from an endpoint per variant, is
 * {@link #variantDescription}.
 *
 * <p>Ids of one kind are distinct. A match's competitors are derived from the match id and their
 * profile from the competitor id, so a scenario answers a request by reading the id from the path.
 */
public final class Catalogs {

    /** Market descriptions of one language: above production's, and at least the corpus's 400 ids. */
    public static final int MARKET_DESCRIPTIONS = 600;

    /** Sports in the sports list: above production's, as headroom. */
    public static final int SPORTS = 250;

    /** The competitors of a match: its two sides. */
    public static final int COMPETITORS_PER_MATCH = 2;

    private static final long COMPETITOR_BASE = 100_000;
    private static final int TOURNAMENTS = 400;
    private static final int PLAYERS_PER_COMPETITOR = 5;

    /** The shapes of specifiers the SDK reads: none, a map, a threshold, both, and a variant way. */
    private static final String[][][] SPECIFIERS = {
        {},
        {},
        {{"map", "integer"}},
        {{"threshold", "decimal"}},
        {{"map", "integer"}, {"threshold", "decimal"}},
        {{"variant", "variable_text"}, {"way", "variable_text"}},
    };

    private Catalogs() {}

    /**
     * The market descriptions of one language: {@code markets} markets with ids 1 to {@code markets},
     * a name each, their outcomes and specifiers.
     */
    public static byte[] marketDescriptions(int markets) {
        require(markets >= 0, "markets");
        RandomGenerator random = random(1, markets);
        var xml = new StringBuilder(markets * 700 + 100);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<market_descriptions response_code=\"OK\">\n");
        for (int id = 1; id <= markets; id++) {
            boolean variant = id > Corpus.MARKET_IDS && random.nextInt(3) == 0;
            xml.append("  <market id=\"").append(id).append("\" name=\"Market ").append(id);
            xml.append("\" groups=\"all|group_").append(id % 7).append('"');
            if (variant) {
                xml.append(" variant=\"od:dynamic_outcomes:")
                        .append(id)
                        .append("\" includes_outcomes_of_type=\"od:player\" outcome_type=\"player\"");
            }
            xml.append(">\n    <outcomes>\n");
            int outcomes = random.nextInt(10) < 8 ? 2 + random.nextInt(2) : 4 + random.nextInt(17);
            for (int o = 1; o <= outcomes; o++) {
                xml.append("      <outcome id=\"");
                if (variant) {
                    xml.append("od:player:").append(id * 100L + o);
                } else {
                    xml.append(o);
                }
                xml.append("\" name=\"")
                        .append(variant ? "Player " : "Outcome ")
                        .append(o);
                xml.append("\"/>\n");
            }
            xml.append("    </outcomes>\n");
            String[][] specifiers = SPECIFIERS[random.nextInt(SPECIFIERS.length)];
            if (variant) {
                specifiers = new String[][] {{"variant", "variable_text"}};
            }
            if (specifiers.length > 0) {
                xml.append("    <specifiers>\n");
                for (String[] specifier : specifiers) {
                    xml.append("      <specifier name=\"")
                            .append(specifier[0])
                            .append("\" type=\"")
                            .append(specifier[1])
                            .append("\"/>\n");
                }
                xml.append("    </specifiers>\n");
            }
            xml.append("  </market>\n");
        }
        xml.append("</market_descriptions>\n");
        return xml.toString().getBytes(UTF_8);
    }

    /**
     * The description of one variant of a market, as the API answers for the variant's own endpoint:
     * the market with that variant and the players of the match it belongs to, one outcome each. The
     * same bytes for the same market and variant; a variant of another match has other players.
     */
    public static byte[] variantDescription(int marketId, String variant) {
        require(marketId > 0, "marketId");
        require(!variant.isEmpty(), "variant");
        RandomGenerator random = random(2, marketId * 31 + variant.hashCode());
        int players = 6 + random.nextInt(15);
        var xml = new StringBuilder(players * 100 + 400);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<market_descriptions response_code=\"OK\">\n");
        xml.append("  <market id=\"")
                .append(marketId)
                .append("\" name=\"Market ")
                .append(marketId);
        xml.append("\" groups=\"all|group_")
                .append(marketId % 7)
                .append("\" variant=\"")
                .append(variant);
        xml.append("\" includes_outcomes_of_type=\"od:player\" outcome_type=\"player\">\n    <outcomes>\n");
        for (int p = 1; p <= players; p++) {
            xml.append("      <outcome id=\"od:player:")
                    .append(Math.abs(variant.hashCode() % 1_000_000) * 100L + p)
                    .append("\" name=\"Player ")
                    .append(p)
                    .append("\"/>\n");
        }
        xml.append("    </outcomes>\n    <specifiers>\n")
                .append("      <specifier name=\"variant\" type=\"variable_text\"/>\n")
                .append("    </specifiers>\n  </market>\n</market_descriptions>\n");
        return xml.toString().getBytes(UTF_8);
    }

    /** The sports list: {@code sports} sports with ids 1 to {@code sports}. */
    public static byte[] sports(int sports) {
        require(sports >= 0, "sports");
        var xml = new StringBuilder(sports * 100 + 100);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<sports generated_at=\"2026-08-26T12:00:00\">\n");
        for (int id = 1; id <= sports; id++) {
            xml.append("  <sport id=\"od:sport:")
                    .append(id)
                    .append("\" name=\"Sport ")
                    .append(id)
                    .append("\" abbreviation=\"S")
                    .append(id)
                    .append("\" icon_path=\"/icons/sport_")
                    .append(id)
                    .append(".svg\"/>\n");
        }
        xml.append("</sports>\n");
        return xml.toString().getBytes(UTF_8);
    }

    /** The summary of a match with the defaults: {@link #COMPETITORS_PER_MATCH} and {@link #SPORTS}. */
    public static byte[] matchSummary(long matchId) {
        return matchSummary(matchId, COMPETITORS_PER_MATCH, SPORTS);
    }

    /**
     * The summary of a match: its tournament and sport, {@code competitors} competitors whose ids
     * {@link #competitorId} gives, and a status. The sport is one of the {@code sports} of the sports
     * list, chosen by the match id.
     */
    public static byte[] matchSummary(long matchId, int competitors, int sports) {
        require(matchId > 0, "matchId");
        require(competitors >= 0, "competitors");
        require(sports > 0, "sports");
        long tournament = 1 + matchId % TOURNAMENTS;
        long sport = sportOf(tournament, sports);
        var xml = new StringBuilder(1500 + competitors * 150);
        xml.append(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<match_summary generated_at=\"2026-08-26T12:00:00\">\n");
        xml.append("  <sport_event id=\"od:match:").append(matchId);
        xml.append("\" name=\"Match ").append(matchId);
        xml.append("\" scheduled=\"2026-08-26T18:00:00\" status=\"live\">\n");
        xml.append("    <tournament id=\"od:tournament:").append(tournament);
        xml.append("\" name=\"Tournament ").append(tournament);
        xml.append("\" abbreviation=\"T").append(tournament).append("\" risk_tier=\"1\"");
        xml.append(" scheduled=\"2026-08-26T18:00:00\" scheduled_end=\"2026-08-27T18:00:00\">\n");
        xml.append("      <sport id=\"od:sport:").append(sport);
        xml.append("\" name=\"Sport ").append(sport);
        xml.append("\" abbreviation=\"S").append(sport).append("\"/>\n    </tournament>\n");
        xml.append("    <competitors>\n");
        for (int slot = 0; slot < competitors; slot++) {
            long id = competitorId(matchId, slot, competitors);
            xml.append("      <competitor id=\"od:competitor:").append(id);
            xml.append("\" name=\"Team ").append(id);
            xml.append("\" abbreviation=\"C").append(id).append("\" underage=\"-1\"");
            if (slot < 2) {
                xml.append(" qualifier=\"").append(slot == 0 ? "home" : "away").append('"');
            }
            xml.append("/>\n");
        }
        xml.append("    </competitors>\n  </sport_event>\n");
        xml.append("  <sport_event_status home_score=\"1\" away_score=\"0\" status=\"live\"")
                .append(" scoreboard_available=\"false\" match_status_code=\"52\">\n")
                .append("    <period_scores>\n")
                .append("      <period_score type=\"map\" number=\"1\" match_status_code=\"51\"")
                .append(" home_score=\"1\" away_score=\"0\"/>\n")
                .append("    </period_scores>\n  </sport_event_status>\n");
        xml.append("</match_summary>\n");
        return xml.toString().getBytes(UTF_8);
    }

    /**
     * The id of a match's competitor in slot {@code slot} (0 up to {@code competitors - 1}): distinct
     * for every match and slot, and the same for the same arguments.
     */
    public static long competitorId(long matchId, int slot, int competitors) {
        require(matchId > 0, "matchId");
        require(slot >= 0 && slot < competitors, "slot");
        return COMPETITOR_BASE + matchId * competitors + slot;
    }

    /** The profile of a competitor with the default {@link #SPORTS}. */
    public static byte[] competitorProfile(long competitorId) {
        return competitorProfile(competitorId, SPORTS);
    }

    /** The profile of a competitor: the competitor, its sport (by its id, of {@code sports}) and its players. */
    public static byte[] competitorProfile(long competitorId, int sports) {
        require(competitorId > 0, "competitorId");
        require(sports > 0, "sports");
        long sport = sportOf(competitorId, sports);
        var xml = new StringBuilder(1200);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<competitor_profile>\n");
        xml.append("  <competitor id=\"od:competitor:").append(competitorId);
        xml.append("\" name=\"Team ").append(competitorId);
        xml.append("\" abbreviation=\"C").append(competitorId).append("\" underage=\"-1\"");
        xml.append(" country=\"Czechia\" country_code=\"CZE\" virtual=\"false\"");
        xml.append(" icon_path=\"/icons/team_").append(competitorId).append(".svg\">\n");
        xml.append("    <sport id=\"od:sport:").append(sport);
        xml.append("\" name=\"Sport ").append(sport);
        xml.append("\" abbreviation=\"S").append(sport).append("\"/>\n  </competitor>\n  <players>\n");
        for (int p = 1; p <= PLAYERS_PER_COMPETITOR; p++) {
            long player = competitorId * 100 + p;
            xml.append("    <player id=\"od:player:").append(player);
            xml.append("\" name=\"Player ").append(player);
            xml.append("\" sport=\"od:sport:").append(sport);
            xml.append("\" full_name=\"Player ").append(player).append(" Full\" underage=\"-1\"/>\n");
        }
        xml.append("  </players>\n</competitor_profile>\n");
        return xml.toString().getBytes(UTF_8);
    }

    private static long sportOf(long id, int sports) {
        return 1 + id % sports;
    }

    private static RandomGenerator random(int document, int size) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(document * 1_000_003L + size);
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(what + " is out of range");
        }
    }
}
