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
 * three in four carry specifiers.
 */
public final class Corpus {

    /** Markets in a small, a typical and a large odds change. */
    public static final int SMALL = 20;

    public static final int TYPICAL = 150;
    public static final int LARGE = 500;

    /** The market ids an odds change draws from: 1 up to and including this. */
    public static final int MARKET_IDS = 400;

    private static final String[] SPECIFIERS = {
        "", "", "map=1", "map=2", "threshold=2.5", "threshold=26.5", "map=1|threshold=10.5", "variant=way:two|way=two"
    };

    /** The market ids of the variant markets: the ones above the corpus's, which the catalogs describe as variants. */
    private static final int FIRST_VARIANT_MARKET = MARKET_IDS + 1;

    private static final int VARIANT_MARKET_IDS = 200;

    private Corpus() {}

    /**
     * An odds change with {@code markets} markets and, after them, {@code variants} player-prop
     * markets of dynamic outcomes, each with a variant of its own: {@code unique} (a match number)
     * and the market's place make the variant, so no two matches share one, as player props of
     * different matches do not. The corpus's markets are those of {@link #oddsChange(int)}, but for
     * the one specifier that names a variant of another kind, which the generated market list has no
     * row for: every market's name can be read from the generated REST documents. The same bytes for
     * the same arguments.
     */
    public static byte[] oddsChange(int markets, int variants, long unique) {
        // the corpus's own variant specifier is a variant the list has no row for, so its market's name
        // cannot be read: the generated list has the dynamic variants' rows only. The specifier it
        // goes with stays.
        var base = new String(oddsChange(markets), UTF_8).replace("variant=way:two|way=two", "way=two");
        var xml = new StringBuilder(base.length() + variants * 250);
        for (int k = 0; k < variants; k++) {
            int id = FIRST_VARIANT_MARKET + k % VARIANT_MARKET_IDS;
            xml.append("<market id=\"")
                    .append(id)
                    .append("\" specifiers=\"variant=od:dynamic_outcomes:")
                    .append(id)
                    .append(':')
                    .append(unique)
                    .append(':')
                    .append(k)
                    .append("\" status=\"1\">");
            for (int o = 1; o <= 3; o++) {
                xml.append("<outcome id=\"od:player:")
                        .append(unique * 100 + o)
                        .append("\" odds=\"1.85\" probabilities=\"0.5000\" active=\"1\"/>");
            }
            xml.append("</market>");
        }
        int end = base.lastIndexOf("</odds>");
        return (base.substring(0, end) + xml + base.substring(end)).getBytes(UTF_8);
    }

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
            xml.append("<market id=\"").append(1 + random.nextInt(MARKET_IDS)).append('"');
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
