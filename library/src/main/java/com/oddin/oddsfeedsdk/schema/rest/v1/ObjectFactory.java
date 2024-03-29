//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:55:52 PM CET 
//


package com.oddin.oddsfeedsdk.schema.rest.v1;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFScoreboard;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlRegistry;
import javax.xml.namespace.QName;


/**
 * This object contains factory methods for each
 * Java content interface and Java element interface
 * generated in the gg.oddin.schemas.v1 package.
 * <p>An ObjectFactory allows you to programatically
 * construct new instances of the Java representation
 * for XML content. The Java representation of XML
 * content can consist of schema derived interfaces
 * and classes representing the binding of schema
 * type definitions, element declarations and model
 * groups.  Factory methods for each of these are
 * provided in this class.
 */
@XmlRegistry
public class ObjectFactory {

    private final static QName _BookmakerDetails_QNAME = new QName("http://schemas.oddin.gg/v1", "bookmaker_details");
    private final static QName _TournamentInfo_QNAME = new QName("http://schemas.oddin.gg/v1", "tournament_info");
    private final static QName _Sports_QNAME = new QName("http://schemas.oddin.gg/v1", "sports");
    private final static QName _SportTournaments_QNAME = new QName("http://schemas.oddin.gg/v1", "sport_tournaments");
    private final static QName _Tournaments_QNAME = new QName("http://schemas.oddin.gg/v1", "tournaments");
    private final static QName _CompetitorProfile_QNAME = new QName("http://schemas.oddin.gg/v1", "competitor_profile");
    private final static QName _PlayerProfile_QNAME = new QName("http://schemas.oddin.gg/v1", "player_profile");
    private final static QName _FixturesFixture_QNAME = new QName("http://schemas.oddin.gg/v1", "fixtures_fixture");
    private final static QName _FixtureChanges_QNAME = new QName("http://schemas.oddin.gg/v1", "fixture_changes");
    private final static QName _MatchSummary_QNAME = new QName("http://schemas.oddin.gg/v1", "match_summary");
    private final static QName _TournamentSchedule_QNAME = new QName("http://schemas.oddin.gg/v1", "tournament_schedule");
    private final static QName _Schedule_QNAME = new QName("http://schemas.oddin.gg/v1", "schedule");
    private final static QName _TournamentLength_QNAME = new QName("http://schemas.oddin.gg/v1", "tournament_length");
    private final static QName _VoidReasonsVoidReasonParam_QNAME = new QName("http://schemas.oddin.gg/v1", "param");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: gg.oddin.schemas.v1
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link RAScheduleEndpoint }
     */
    public RAScheduleEndpoint createScheduleEndpoint() {
        return new RAScheduleEndpoint();
    }

    /**
     * Create an instance of {@link RADescSpecifiers }
     */
    public RADescSpecifiers createDescSpecifiers() {
        return new RADescSpecifiers();
    }

    /**
     * Create an instance of {@link RADescSpecifiers.Specifier }
     */
    public RADescSpecifiers.Specifier createDescSpecifiersSpecifier() {
        return new RADescSpecifiers.Specifier();
    }

    /**
     * Create an instance of {@link RAOutcomeDescription }
     */
    public RAOutcomeDescription createOutcomeDescription() {
        return new RAOutcomeDescription();
    }

    /**
     * Create an instance of {@link RADatetournaments }
     */
    public RADatetournaments createDatetournaments() {
        return new RADatetournaments();
    }

    /**
     * Create an instance of {@link RAMatchStatusDescriptions }
     */
    public RAMatchStatusDescriptions createMatchStatusDescriptions() {
        return new RAMatchStatusDescriptions();
    }

    /**
     * Create an instance of {@link RAMatchStatusDescription }
     */
    public RAMatchStatusDescription createMatchStatusDescription() {
        return new RAMatchStatusDescription();
    }

    /**
     * Create an instance of {@link RAMarketDescriptions }
     */
    public RAMarketDescriptions createMarketDescriptions() {
        return new RAMarketDescriptions();
    }

    /**
     * Create an instance of {@link RAMarketDescription }
     */
    public RAMarketDescription createMarketDescription() {
        return new RAMarketDescription();
    }

    /**
     * Create an instance of {@link RASportsEndpoint }
     */
    public RASportsEndpoint createSportsEndpoint() {
        return new RASportsEndpoint();
    }

    /**
     * Create an instance of {@link RASportTournaments }
     */
    public RASportTournaments createSportTournaments() {
        return new RASportTournaments();
    }

    /**
     * Create an instance of {@link RAMatchSummaryEndpoint }
     */
    public RAMatchSummaryEndpoint createMatchSummaryEndpoint() {
        return new RAMatchSummaryEndpoint();
    }

    /**
     * Create an instance of {@link RACompetitorProfileEndpoint }
     */
    public RACompetitorProfileEndpoint createCompetitorProfileEndpoint() {
        return new RACompetitorProfileEndpoint();
    }

    /**
     * Create an instance of {@link RAPlayerProfileEndpoint }
     */
    public RAPlayerProfileEndpoint createPlayerProfileEndpoint() {
        return new RAPlayerProfileEndpoint();
    }

    /**
     * Create an instance of {@link RAFixturesEndpoint }
     */
    public RAFixturesEndpoint createFixturesEndpoint() {
        return new RAFixturesEndpoint();
    }

    /**
     * Create an instance of {@link RAFixtureChangesEndpoint }
     */
    public RAFixtureChangesEndpoint createFixtureChangesEndpoint() {
        return new RAFixtureChangesEndpoint();
    }

    /**
     * Create an instance of {@link RAProducers }
     */
    public RAProducers createProducers() {
        return new RAProducers();
    }

    /**
     * Create an instance of {@link RAProducer }
     */
    public RAProducer createProducer() {
        return new RAProducer();
    }

    /**
     * Create an instance of {@link RABookmakerDetail }
     */
    public RABookmakerDetail createBookmakerDetail() {
        return new RABookmakerDetail();
    }

    /**
     * Create an instance of {@link RATournamentInfo }
     */
    public RATournamentInfo createTournamentInfo() {
        return new RATournamentInfo();
    }

    /**
     * Create an instance of {@link RATournamentSchedule }
     */
    public RATournamentSchedule createTournamentSchedule() {
        return new RATournamentSchedule();
    }

    /**
     * Create an instance of {@link RATournaments }
     */
    public RATournaments createTournaments() {
        return new RATournaments();
    }

    /**
     * Create an instance of {@link RATeamCompetitor }
     */
    public RATeamCompetitor createTeamCompetitor() {
        return new RATeamCompetitor();
    }

    /**
     * Create an instance of {@link RASportEvent }
     */
    public RASportEvent createSportEvent() {
        return new RASportEvent();
    }

    /**
     * Create an instance of {@link RATournament }
     */
    public RATournament createTournament() {
        return new RATournament();
    }

    /**
     * Create an instance of {@link RACompetitors }
     */
    public RACompetitors createCompetitors() {
        return new RACompetitors();
    }

    /**
     * Create an instance of {@link RATournamentLength }
     */
    public RATournamentLength createTournamentLength() {
        return new RATournamentLength();
    }

    /**
     * Create an instance of {@link RATournamentExtended }
     */
    public RATournamentExtended createTournamentExtended() {
        return new RATournamentExtended();
    }

    /**
     * Create an instance of {@link RAInfo }
     */
    public RAInfo createInfo() {
        return new RAInfo();
    }

    /**
     * Create an instance of {@link RASportEvents }
     */
    public RASportEvents createSportEvents() {
        return new RASportEvents();
    }

    /**
     * Create an instance of {@link RATeam }
     */
    public RATeam createTeam() {
        return new RATeam();
    }

    /**
     * Create an instance of {@link RAFixture }
     */
    public RAFixture createFixture() {
        return new RAFixture();
    }

    /**
     * Create an instance of {@link RASportEventCompetitors }
     */
    public RASportEventCompetitors createSportEventCompetitors() {
        return new RASportEventCompetitors();
    }

    /**
     * Create an instance of {@link RAFixtureChange }
     */
    public RAFixtureChange createFixtureChange() {
        return new RAFixtureChange();
    }

    /**
     * Create an instance of {@link RATeamExtended }
     */
    public RATeamExtended createTeamExtended() {
        return new RATeamExtended();
    }

    /**
     * Create an instance of {@link RAPlayerProfileEndpoint.Player }
     */
    public RAPlayerProfileEndpoint.Player createPlayerProfilePlayer() {
        return new RAPlayerProfileEndpoint.Player();
    }

    /**
     * Create an instance of {@link RASport }
     */
    public RASport createSport() {
        return new RASport();
    }

    /**
     * Create an instance of {@link RADelayedInfo }
     */
    public RADelayedInfo createDelayedInfo() {
        return new RADelayedInfo();
    }

    /**
     * Create an instance of {@link RAExtraInfo }
     */
    public RAExtraInfo createExtraInfo() {
        return new RAExtraInfo();
    }

    /**
     * Create an instance of {@link RATvChannels }
     */
    public RATvChannels createTvChannels() {
        return new RATvChannels();
    }

    /**
     * Create an instance of {@link RATvChannel }
     */
    public RATvChannel createTvChannel() {
        return new RATvChannel();
    }

    /**
     * Create an instance of {@link RAError }
     */
    public RAError createError() {
        return new RAError();
    }

    /**
     * Create an instance of {@link RAErrorMessage }
     */
    public RAErrorMessage createErrorMessage() {
        return new RAErrorMessage();
    }

    /**
     * Create an instance of {@link RASportEventStatus }
     */
    public RASportEventStatus createSportEventStatus() {
        return new RASportEventStatus();
    }

    /**
     * Create an instance of {@link OFScoreboard }
     *
     */
    public RAScoreboard createScoreboard() {
        return new RAScoreboard();
    }

    /**
     * Create an instance of {@link RAErrorAction }
     */
    public RAErrorAction createErrorAction() {
        return new RAErrorAction();
    }

    /**
     * Create an instance of {@link RAOutcomeDescription.Outcome }
     */
    public RAOutcomeDescription.Outcome createOutcomeDescriptionOutcome() {
        return new RAOutcomeDescription.Outcome();
    }

    /**
     * Create an instance of {@link RAReplaySetContent }
     */
    public RAReplaySetContent createReplaySetContent() {
        return new RAReplaySetContent();
    }

    /**
     * Create an instance of {@link RAReplayEvent }
     */
    public RAReplayEvent createReplayEvent() {
        return new RAReplayEvent();
    }

    /**
     * Create an instance of {@link RAPeriodScore }
     */
    public RAPeriodScore createPeriodScore() {
        return new RAPeriodScore();
    }

    /**
     * Create an instance of {@link RAPeriodScoreBase }
     */
    public RAPeriodScoreBase createPeriodScoreBase() {
        return new RAPeriodScoreBase();
    }

    /**
     * Create an instance of {@link RAPeriodScores }
     */
    public RAPeriodScores createPeriodScores() {
        return new RAPeriodScores();
    }

    /**
     * Create an instance of {@link RAMarketVoidReasons }
     *
     */
    public RAMarketVoidReasons createMarketVoidReasons() {
        return new RAMarketVoidReasons();
    }

    /**
     * Create an instance of {@link RAMarketVoidReasons.VoidReason }
     *
     */
    public RAMarketVoidReasons.VoidReason createMarketVoidReasonsVoidReason() {
        return new RAMarketVoidReasons.VoidReason();
    }

    /**
     * Create an instance of {@link RAMarketVoidReasons.VoidReason.Param }
     *
     */
    public RAMarketVoidReasons.VoidReason.Param createMarketVoidReasonsVoidReasonParam() {
        return new RAMarketVoidReasons.VoidReason.Param();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RAMarketVoidReasons.VoidReason.Param }{@code >}}
     *
     */
    @XmlElementDecl(namespace = "", name = "param", scope = RAMarketVoidReasons.VoidReason.class)
    public JAXBElement<RAMarketVoidReasons.VoidReason.Param> createMarketVoidReasonsVoidReasonParam(RAMarketVoidReasons.VoidReason.Param value) {
        return new JAXBElement<RAMarketVoidReasons.VoidReason.Param>(_VoidReasonsVoidReasonParam_QNAME, RAMarketVoidReasons.VoidReason.Param.class, RAMarketVoidReasons.VoidReason.class, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RABookmakerDetail }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "bookmaker_details")
    public JAXBElement<RABookmakerDetail> createBookmakerDetails(RABookmakerDetail value) {
        return new JAXBElement<RABookmakerDetail>(_BookmakerDetails_QNAME, RABookmakerDetail.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RATournamentInfo }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "tournament_info")
    public JAXBElement<RATournamentInfo> createTournamentInfo(RATournamentInfo value) {
        return new JAXBElement<RATournamentInfo>(_TournamentInfo_QNAME, RATournamentInfo.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RASportsEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "sports")
    public JAXBElement<RASportsEndpoint> createSports(RASportsEndpoint value) {
        return new JAXBElement<RASportsEndpoint>(_Sports_QNAME, RASportsEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RASportTournaments }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "sport_tournaments")
    public JAXBElement<RASportTournaments> createSportTournaments(RASportTournaments value) {
        return new JAXBElement<RASportTournaments>(_SportTournaments_QNAME, RASportTournaments.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RADatetournaments }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "tournaments")
    public JAXBElement<RADatetournaments> createTournaments(RADatetournaments value) {
        return new JAXBElement<RADatetournaments>(_Tournaments_QNAME, RADatetournaments.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RACompetitorProfileEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "competitor_profile")
    public JAXBElement<RACompetitorProfileEndpoint> createCompetitorProfile(RACompetitorProfileEndpoint value) {
        return new JAXBElement<RACompetitorProfileEndpoint>(_CompetitorProfile_QNAME, RACompetitorProfileEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RAPlayerProfileEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "player_profile")
    public JAXBElement<RAPlayerProfileEndpoint> createPlayerProfile(RAPlayerProfileEndpoint value) {
        return new JAXBElement<RAPlayerProfileEndpoint>(_PlayerProfile_QNAME, RAPlayerProfileEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RAFixturesEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "fixtures_fixture")
    public JAXBElement<RAFixturesEndpoint> createFixturesFixture(RAFixturesEndpoint value) {
        return new JAXBElement<RAFixturesEndpoint>(_FixturesFixture_QNAME, RAFixturesEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RAFixtureChangesEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "fixture_changes")
    public JAXBElement<RAFixtureChangesEndpoint> createFixtureChanges(RAFixtureChangesEndpoint value) {
        return new JAXBElement<RAFixtureChangesEndpoint>(_FixtureChanges_QNAME, RAFixtureChangesEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RAMatchSummaryEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "match_summary")
    public JAXBElement<RAMatchSummaryEndpoint> createMatchSummary(RAMatchSummaryEndpoint value) {
        return new JAXBElement<RAMatchSummaryEndpoint>(_MatchSummary_QNAME, RAMatchSummaryEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RATournamentSchedule }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "tournament_schedule")
    public JAXBElement<RATournamentSchedule> createTournamentSchedule(RATournamentSchedule value) {
        return new JAXBElement<RATournamentSchedule>(_TournamentSchedule_QNAME, RATournamentSchedule.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RAScheduleEndpoint }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "schedule")
    public JAXBElement<RAScheduleEndpoint> createSchedule(RAScheduleEndpoint value) {
        return new JAXBElement<RAScheduleEndpoint>(_Schedule_QNAME, RAScheduleEndpoint.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RATournamentLength }{@code >}}
     */
    @XmlElementDecl(namespace = "http://schemas.oddin.gg/v1", name = "tournament_length")
    public JAXBElement<RATournamentLength> createTournamentLength(RATournamentLength value) {
        return new JAXBElement<RATournamentLength>(_TournamentLength_QNAME, RATournamentLength.class, null, value);
    }
}
