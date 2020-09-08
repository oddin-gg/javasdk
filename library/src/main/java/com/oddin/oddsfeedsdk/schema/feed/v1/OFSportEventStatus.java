//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:57:50 PM CET 
//


package com.oddin.oddsfeedsdk.schema.feed.v1;

import java.math.BigDecimal;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for sportEventStatus complex type.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType name="sportEventStatus">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="scoreboard" type="{}scoreboardType" minOccurs="0"/>
 *         &lt;element name="period_scores" type="{}periodscoresType" minOccurs="0"/>
 *         &lt;element name="results" type="{}resultsType" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="status" use="required" type="{}eventStatus" />
 *       &lt;attribute name="match_status" use="required" type="{http://www.w3.org/2001/XMLSchema}int" />
 *       &lt;attribute name="home_score" type="{http://www.w3.org/2001/XMLSchema}decimal" />
 *       &lt;attribute name="away_score" type="{http://www.w3.org/2001/XMLSchema}decimal" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "sportEventStatus", propOrder = {
        "periodScores",
        "results",
        "scoreboard"
})
public class OFSportEventStatus {

    @XmlElement(name = "scoreboard")
    protected OFScoreboard scoreboard;
    @XmlElement(name = "period_scores")
    protected OFPeriodscoresType periodScores;
    protected OFResultsType results;
    @XmlAttribute(name = "status", required = true)
    protected OFEventStatus status;
    @XmlAttribute(name = "match_status", required = true)
    protected int matchStatus;
    @XmlAttribute(name = "home_score")
    protected Double homeScore;
    @XmlAttribute(name = "away_score")
    protected Double awayScore;

    @XmlAttribute(name = "scoreboard_available")
    protected boolean scoreboardAvailable;

    public OFScoreboard getScoreboard() {
        return scoreboard;
    }

    public void setScoreboard(OFScoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }


    /**
     * Gets the value of the periodScores property.
     *
     * @return possible object is
     * {@link OFPeriodscoresType }
     */
    public OFPeriodscoresType getPeriodScores() {
        return periodScores;
    }

    /**
     * Sets the value of the periodScores property.
     *
     * @param value allowed object is
     *              {@link OFPeriodscoresType }
     */
    public void setPeriodScores(OFPeriodscoresType value) {
        this.periodScores = value;
    }

    /**
     * Gets the value of the results property.
     *
     * @return possible object is
     * {@link OFResultsType }
     */
    public OFResultsType getResults() {
        return results;
    }

    /**
     * Sets the value of the results property.
     *
     * @param value allowed object is
     *              {@link OFResultsType }
     */
    public void setResults(OFResultsType value) {
        this.results = value;
    }

    /**
     * Gets the value of the status property.
     */
    public OFEventStatus getStatus() {
        return status;
    }

    /**
     * Sets the value of the status property.
     */
    public void setStatus(OFEventStatus value) {
        this.status = value;
    }

    /**
     * Gets the value of the matchStatus property.
     */
    public int getMatchStatus() {
        return matchStatus;
    }

    /**
     * Sets the value of the matchStatus property.
     */
    public void setMatchStatus(int value) {
        this.matchStatus = value;
    }

    /**
     * Gets the value of the homeScore property.
     *
     * @return possible object is
     * {@link BigDecimal }
     */
    public Double getHomeScore() {
        return homeScore;
    }

    /**
     * Sets the value of the homeScore property.
     *
     * @param value allowed object is
     *              {@link BigDecimal }
     */
    public void setHomeScore(Double value) {
        this.homeScore = value;
    }

    /**
     * Gets the value of the awayScore property.
     *
     * @return possible object is
     * {@link BigDecimal }
     */
    public Double getAwayScore() {
        return awayScore;
    }

    /**
     * Sets the value of the awayScore property.
     *
     * @param value allowed object is
     *              {@link BigDecimal }
     */
    public void setAwayScore(Double value) {
        this.awayScore = value;
    }


    public boolean isScoreboardAvailable() {
        return scoreboardAvailable;
    }

    public void setScoreboardAvailable(boolean scoreboardAvailable) {
        this.scoreboardAvailable = scoreboardAvailable;
    }

}
