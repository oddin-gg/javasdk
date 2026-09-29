package com.oddin.oddsfeed.benchmarks;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Odds changes shaped like the ones the feed sends for a live match: a status with period scores and
 * a scoreboard, then the markets. No recorded traffic is used - the corpus is generated, the same
 * every time for a size, so a benchmark run can be compared with the last one.
 *
 * <p>Most markets have two or three outcomes, some have many (a correct score has up to twenty), and
 * about half carry specifiers, as live markets do.
 */
public final class Corpus {

    /** Markets in a small, a typical and a large odds change. */
    public static final int SMALL = 20;

    public static final int TYPICAL = 150;
    public static final int LARGE = 500;

    private static final String[] SPECIFIERS = {
        "", "", "map=1", "map=2", "threshold=2.5", "threshold=26.5", "map=1|threshold=10.5", "variant=way:two|way=two"
    };

    private Corpus() {}

    /** An odds change with {@code markets} markets; the same bytes for the same count. */
    public static byte[] oddsChange(int markets) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(markets);
        var xml = new StringBuilder(markets * 400);
        xml.append("<odds_change product=\"2\" event_id=\"od:match:198314\" timestamp=\"1777832981632\">");
        xml.append("<sport_event_status status=\"1\" match_status=\"52\" home_score=\"1\" away_score=\"0\"")
                .append(" scoreboard_available=\"true\">");
        xml.append("<period_scores>")
                .append("<period_score type=\"map\" number=\"1\" match_status_code=\"51\" home_score=\"1\"")
                .append(" away_score=\"0\" home_kills=\"24\" away_kills=\"17\"/>")
                .append("<period_score type=\"map\" number=\"2\" match_status_code=\"52\" home_score=\"0\"")
                .append(" away_score=\"0\" home_kills=\"8\" away_kills=\"11\"/>")
                .append("</period_scores>");
        xml.append("<scoreboard home_kills=\"32\" away_kills=\"28\" home_destroyed_turrets=\"5\"")
                .append(" away_destroyed_turrets=\"3\" home_destroyed_towers=\"6\" away_destroyed_towers=\"4\"")
                .append(" home_gold=\"61250\" away_gold=\"58900\" game_time=\"1874\"/>");
        xml.append("</sport_event_status><odds>");
        for (int m = 0; m < markets; m++) {
            int outcomes = random.nextInt(10) < 8 ? 2 + random.nextInt(2) : 4 + random.nextInt(17);
            String specifiers = SPECIFIERS[random.nextInt(SPECIFIERS.length)];
            int status = random.nextInt(20) == 0 ? -1 : 1;
            xml.append("<market id=\"").append(1 + random.nextInt(400)).append('"');
            if (!specifiers.isEmpty()) {
                xml.append(" specifiers=\"").append(specifiers).append('"');
            }
            xml.append(" status=\"").append(status).append('"');
            if (random.nextInt(4) == 0) {
                xml.append(" favourite=\"1\"");
            }
            xml.append('>');
            for (int o = 1; o <= outcomes; o++) {
                xml.append("<outcome id=\"").append(o).append('"');
                if (status == 1) {
                    xml.append(" odds=\"")
                            .append(String.format(Locale.ROOT, "%.2f", 1.01 + random.nextDouble() * 19))
                            .append("\" probabilities=\"")
                            .append(String.format(Locale.ROOT, "%.4f", random.nextDouble()))
                            .append('"');
                }
                xml.append(" active=\"").append(status == 1 ? 1 : 0).append("\"/>");
            }
            xml.append("</market>");
        }
        xml.append("</odds></odds_change>");
        return xml.toString().getBytes(UTF_8);
    }
}
