package ch.sbb.matsim.scoring;

import ch.sbb.matsim.config.SBBBehaviorGroupsConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.variables.SBBModes;
import ch.sbb.matsim.preparation.ActivityParamsBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt.PtConstants;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Main idea of the test: Run a mini-scenario with a single agent twice, once with default MATSim scoring, once with SBB Scoring. Then we can calculate the score-difference and compare it against our
 * expectations.
 *
 * @author mrieser
 */

public class TransferScoringTest {

	private static final Logger log = LogManager.getLogger(TransferScoringTest.class);

	@RegisterExtension
	public final MatsimTestUtils helper = new MatsimTestUtils();

	@Test
	public void testTransferScoring() {
		double score1;
		double score2;
		double score3;
		{
            Fixture f1 = new Fixture(this.helper.getOutputDirectory() + "/run1/");
            f1.setLineSwitchConfig();

			f1.config.controller().setLastIteration(0);

            Controler controler = new Controler(f1.scenario);
            controler.run();

            Person p1 = f1.scenario.getPopulation().getPersons().get(Id.create(1, Person.class));
            Plan plan1 = p1.getSelectedPlan();
            score1 = plan1.getScore();
        }
        {
            Fixture f2 = new Fixture(this.helper.getOutputDirectory() + "/run2/");
            f2.setSBBTransferUtility();

			f2.config.controller().setLastIteration(0);

            Controler controler = new Controler(f2.scenario);
            controler.setScoringFunctionFactory(new SBBScoringFunctionFactory(f2.scenario));
            controler.run();

            Person p1 = f2.scenario.getPopulation().getPersons().get(Id.create(1, Person.class));
            Plan plan1 = p1.getSelectedPlan();
            score2 = plan1.getScore();
		}
		{
			Fixture f3 = new Fixture(this.helper.getOutputDirectory() + "/run3/");
			f3.setSBBTransferUtilityAndModeToModePenalties();

			f3.config.controller().setLastIteration(0);

			Controler controler = new Controler(f3.scenario);
			controler.setScoringFunctionFactory(new SBBScoringFunctionFactory(f3.scenario));
			controler.run();

			Person p3 = f3.scenario.getPopulation().getPersons().get(Id.create(1, Person.class));
			Plan plan3 = p3.getSelectedPlan();
			score3 = plan3.getScore();
		}

		log.info("score with default scoring: " + score1);
		log.info("score with sbb-scoring: " + score2);
		log.info("score with sbb-scoring and ModeToModePenalties: " + score3);

		double defaultTransferScore = -6.0;
		double sbbTransferScore = 2 * Math.max(-12.0, Math.min(-2.0, -1.0 - 2.0 * (945.0 / 3600.0)));
		double sbbTransferScoreWithModeToModePenalty = -1.5 + Math.max(-12.0, Math.min(-2.0, -1.0 - 2.0 * (480.0 / 3600.0)));

		double actualScoreDiff = score2 - score1;
		double expectedScoreDiff = sbbTransferScore - defaultTransferScore;

		Assert.assertEquals(expectedScoreDiff, actualScoreDiff, 1e-7);

		actualScoreDiff = score3 - score1;
		expectedScoreDiff = sbbTransferScoreWithModeToModePenalty - defaultTransferScore;

		Assert.assertEquals(expectedScoreDiff, actualScoreDiff, 1e-7);
	}

    private static class Fixture {

        final Config config;
        final Scenario scenario;

        Fixture(String outputDirectory) {
            this.config = ConfigUtils.createConfig();
            prepareConfig(outputDirectory);
            this.scenario = ScenarioUtils.createScenario(this.config);
            createNetwork();
            createTransitSchedule();
            createPopulation();
        }

		private void prepareConfig(String outputDirectory) {
			ScoringConfigGroup scoringConfig = this.config.scoring();
			ScoringConfigGroup.ActivityParams homeScoring = new ScoringConfigGroup.ActivityParams("home");
			homeScoring.setTypicalDuration(12 * 3600);
			scoringConfig.addActivityParams(homeScoring);

			this.config.controller().setOutputDirectory(outputDirectory);
			this.config.controller().setCreateGraphsInterval(0);
			this.config.controller().setDumpDataAtEnd(false);

			this.config.transit().setUseTransit(true);
			ActivityParamsBuilder.buildStageActivityModeParams(config);
		}

        void setLineSwitchConfig() {
			this.config.scoring().setUtilityOfLineSwitch(-3.0);
        }

        void setSBBTransferUtility() {
            SBBBehaviorGroupsConfigGroup sbbConfig = ConfigUtils.addOrGetModule(this.config, SBBBehaviorGroupsConfigGroup.class);

            sbbConfig.setBaseTransferUtility(-1.0);
            sbbConfig.setTransferUtilityPerTravelTime_utils_hr(-2.0);
            sbbConfig.setMinimumTransferUtility(-12.0);
            sbbConfig.setMaximumTransferUtility(-2.0);
        }

		void setSBBTransferUtilityAndModeToModePenalties() {
			SBBBehaviorGroupsConfigGroup sbbConfig = ConfigUtils.addOrGetModule(this.config, SBBBehaviorGroupsConfigGroup.class);

			sbbConfig.setBaseTransferUtility(-1.0);
			sbbConfig.setTransferUtilityPerTravelTime_utils_hr(-2.0);
			sbbConfig.setMinimumTransferUtility(-12.0);
			sbbConfig.setMaximumTransferUtility(-2.0);

			SwissRailRaptorConfigGroup srrConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

			srrConfig.addModeToModeTransferPenalty(new SwissRailRaptorConfigGroup.ModeToModeTransferPenalty("train", "bus", -1.5));
			srrConfig.addModeToModeTransferPenalty(new SwissRailRaptorConfigGroup.ModeToModeTransferPenalty("bus", "train", -1.5));
		}

		private void createNetwork() {
            Network network = this.scenario.getNetwork();
			NetworkFactory nf = network.getFactory();

			Node n1 = nf.createNode(Id.create("1", Node.class), new Coord(1000, 1000));
			Node n2 = nf.createNode(Id.create("2", Node.class), new Coord(3000, 1000));
			Node n3 = nf.createNode(Id.create("3", Node.class), new Coord(5000, 1000));
			Node n4 = nf.createNode(Id.create("4", Node.class), new Coord(7000, 1000));
            Node n5 = nf.createNode(Id.create("5", Node.class), new Coord(9000, 1000));
			Node n6 = nf.createNode(Id.create("6", Node.class), new Coord(11000, 1000));
			Node n7 = nf.createNode(Id.create("7", Node.class), new Coord(11900, 1000));

            network.addNode(n1);
            network.addNode(n2);
            network.addNode(n3);
            network.addNode(n4);
            network.addNode(n5);
            network.addNode(n6);
			network.addNode(n7);

            Link l1 = createLink(nf, "1", n1, n2);
            Link l2 = createLink(nf, "2", n2, n3);
            Link l3 = createLink(nf, "3", n3, n4);
            Link l4 = createLink(nf, "4", n4, n5);
            Link l5 = createLink(nf, "5", n5, n6);
			Link l6 = createLink(nf, "6", n6, n7);


            network.addLink(l1);
            network.addLink(l2);
            network.addLink(l3);
            network.addLink(l4);
            network.addLink(l5);
			network.addLink(l6);

			network.addLink(createLink(nf, "7", n2, n1));
			network.addLink(createLink(nf, "8", n3, n2));
			network.addLink(createLink(nf, "9", n4, n3));
			network.addLink(createLink(nf, "10", n5, n4));
			network.addLink(createLink(nf, "11", n6, n5));
			network.addLink(createLink(nf, "12", n7, n6));


		}

        private Link createLink(NetworkFactory nf, String id, Node fromNode, Node toNode) {
            Link l = nf.createLink(Id.create(id, Link.class), fromNode, toNode);
            l.setLength(4000);
            l.setCapacity(1000);
            l.setFreespeed(25);
            l.setAllowedModes(CollectionUtils.stringToSet("car"));
            l.setNumberOfLanes(1);
            return l;
        }

        private void createTransitSchedule() {
			Vehicles vehicles = this.scenario.getTransitVehicles();
			VehiclesFactory vf = vehicles.getFactory();
			VehicleType vt = vf.createVehicleType(Id.create("train", VehicleType.class));
			VehicleCapacity vc = vt.getCapacity();
			vc.setSeats(100);
			vehicles.addVehicleType(vt);
			vehicles.addVehicle(vf.createVehicle(Id.create("b1", Vehicle.class), vt));
			vehicles.addVehicle(vf.createVehicle(Id.create("r1", Vehicle.class), vt));
			vehicles.addVehicle(vf.createVehicle(Id.create("g1", Vehicle.class), vt));

			TransitSchedule schedule = this.scenario.getTransitSchedule();
			TransitScheduleFactory sf = schedule.getFactory();

			TransitStopFacility stop1 = sf.createTransitStopFacility(Id.create("1", TransitStopFacility.class), new Coord(3000, 1000), false);
			stop1.setLinkId(Id.create("1", Link.class));
			schedule.addStopFacility(stop1);

			TransitStopFacility stop2 = sf.createTransitStopFacility(Id.create("2", TransitStopFacility.class), new Coord(5000, 1000), false);
			stop2.setLinkId(Id.create("2", Link.class));
			schedule.addStopFacility(stop2);

			TransitStopFacility stop3 = sf.createTransitStopFacility(Id.create("3", TransitStopFacility.class), new Coord(7000, 1000), false);
			stop3.setLinkId(Id.create("3", Link.class));
			schedule.addStopFacility(stop3);

			TransitStopFacility stop4 = sf.createTransitStopFacility(Id.create("4", TransitStopFacility.class), new Coord(9000, 1000), false);
			stop4.setLinkId(Id.create("4", Link.class));
			schedule.addStopFacility(stop4);

			TransitLine blueLine = sf.createTransitLine(Id.create("blue", TransitLine.class));
			NetworkRoute blueNetRoute = RouteUtils.createLinkNetworkRouteImpl(Id.create(1, Link.class), Id.create(2, Link.class));
			List<TransitRouteStop> blueStops = new ArrayList<>();
			blueStops.add(sf.createTransitRouteStopBuilder(stop1).departureOffset(0.).build());
			blueStops.add(sf.createTransitRouteStopBuilder(stop2).arrivalOffset(120.0).build());
			TransitRoute blueRoute = sf.createTransitRoute(Id.create("blue1", TransitRoute.class), blueNetRoute, blueStops, "train");
			Departure blueDeparture = sf.createDeparture(Id.create(1, Departure.class), 8 * 3600);
			blueDeparture.setVehicleId(Id.create("b1", Vehicle.class));
			blueRoute.addDeparture(blueDeparture);

			blueLine.addRoute(blueRoute);
			schedule.addTransitLine(blueLine);

			TransitLine redLine = sf.createTransitLine(Id.create("red", TransitLine.class));
			NetworkRoute redNetRoute = RouteUtils.createLinkNetworkRouteImpl(Id.create(2, Link.class), Id.create(3, Link.class));
			List<TransitRouteStop> redStops = new ArrayList<>();
			redStops.add(sf.createTransitRouteStopBuilder(stop2).departureOffset(0.).build());
			redStops.add(sf.createTransitRouteStopBuilder(stop3).arrivalOffset(120.).build());
			TransitRoute redRoute = sf.createTransitRoute(Id.create("red1", TransitRoute.class), redNetRoute, redStops, "bus");
			Departure redDeparture = sf.createDeparture(Id.create(1, Departure.class), 8 * 3600 + 240);
			redDeparture.setVehicleId(Id.create("r1", Vehicle.class));
			redRoute.addDeparture(redDeparture);
			redLine.addRoute(redRoute);
			schedule.addTransitLine(redLine);

			TransitLine greenLine = sf.createTransitLine(Id.create("green", TransitLine.class));
			NetworkRoute greenNetRoute = RouteUtils.createLinkNetworkRouteImpl(Id.create(3, Link.class), Id.create(4, Link.class));
			List<TransitRouteStop> greenStops = new ArrayList<>();
			greenStops.add(sf.createTransitRouteStopBuilder(stop3).departureOffset(0.).build());
			greenStops.add(sf.createTransitRouteStopBuilder(stop4).arrivalOffset(120.).build());
			TransitRoute greenRoute = sf.createTransitRoute(Id.create("green1", TransitRoute.class), greenNetRoute, greenStops, "bus");
			Departure greenDeparture = sf.createDeparture(Id.create(1, Departure.class), 8 * 3600 + 480);
			greenDeparture.setVehicleId(Id.create("g1", Vehicle.class));
			greenRoute.addDeparture(greenDeparture);
			greenLine.addRoute(greenRoute);
			schedule.addTransitLine(greenLine);
		}

		private void createPopulation() {
			TransitSchedule schedule = this.scenario.getTransitSchedule();
			TransitStopFacility stop1 = schedule.getFacilities().get(Id.create(1, TransitStopFacility.class));
			TransitStopFacility stop2 = schedule.getFacilities().get(Id.create(2, TransitStopFacility.class));
			TransitStopFacility stop3 = schedule.getFacilities().get(Id.create(3, TransitStopFacility.class));
			TransitStopFacility stop4 = schedule.getFacilities().get(Id.create(4, TransitStopFacility.class));
			TransitLine blueLine = schedule.getTransitLines().get(Id.create("blue", TransitLine.class));
			TransitRoute blueRoute = blueLine.getRoutes().get(Id.create("blue1", TransitRoute.class));
			TransitLine redLine = schedule.getTransitLines().get(Id.create("red", TransitLine.class));
			TransitRoute redRoute = redLine.getRoutes().get(Id.create("red1", TransitRoute.class));
			TransitLine greenLine = schedule.getTransitLines().get(Id.create("green", TransitLine.class));
			TransitRoute greenRoute = greenLine.getRoutes().get(Id.create("green1", TransitRoute.class));

			Population pop = this.scenario.getPopulation();
			PopulationFactory pf = pop.getFactory();

			Id<Person> personId = Id.create("1", Person.class);
			Person person = pf.createPerson(personId);
			Plan plan = pf.createPlan();
			person.addPlan(plan);

			Coord home1Coord = new Coord(1000, 900);
			Coord home2Coord = new Coord(11900, 900);

			Activity home1 = pf.createActivityFromCoord("home", home1Coord);
			home1.setEndTime(8 * 3600 - 600);
			home1.setLinkId(Id.create("1", Link.class));

			Activity home2 = pf.createActivityFromCoord("home", home2Coord);
			home2.setLinkId(Id.create("5", Link.class));

			Activity ptAct1 = pf.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(3000, 1000));
			ptAct1.setLinkId(Id.create(1, Link.class));
			ptAct1.setMaximumDuration(0.0);
			Activity transferAct = pf.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(5000, 1000));
			transferAct.setLinkId(Id.create(2, Link.class));
			transferAct.setMaximumDuration(0.0);
			Activity transferAct2 = pf.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(7000, 1000));
			transferAct2.setLinkId(Id.create(3, Link.class));
			transferAct2.setMaximumDuration(0.0);
			Activity ptAct2 = pf.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(9000, 1000));
			ptAct2.setLinkId(Id.create(4, Link.class));
			ptAct2.setMaximumDuration(0.0);

			plan.addActivity(home1);
			Leg accessLeg = pf.createLeg(SBBModes.ACCESS_EGRESS_WALK);
			accessLeg.setRoute(RouteUtils.createGenericRouteImpl(Id.create("1", Link.class), Id.create("1", Link.class)));
			accessLeg.getRoute().setDistance(200);
			accessLeg.getRoute().setTravelTime(300);
			plan.addLeg(accessLeg);
			plan.addActivity(ptAct1);
			Leg pt1Leg = pf.createLeg(SBBModes.PT);
			pt1Leg.setRoute(new DefaultTransitPassengerRoute(stop1, blueLine, blueRoute, stop2));
			plan.addLeg(pt1Leg);
			plan.addActivity(transferAct);
			Leg pt2Leg = pf.createLeg(SBBModes.PT);
			pt2Leg.setRoute(new DefaultTransitPassengerRoute(stop2, redLine, redRoute, stop3));
			plan.addLeg(pt2Leg);
			plan.addActivity(ptAct2);
			plan.addActivity(transferAct2);
			Leg pt3Leg = pf.createLeg(SBBModes.PT);
			pt3Leg.setRoute(new DefaultTransitPassengerRoute(stop3, greenLine, greenRoute, stop4));
			plan.addLeg(pt3Leg);
			plan.addActivity(ptAct2);
			Leg egressLeg = pf.createLeg(SBBModes.ACCESS_EGRESS_WALK);
			egressLeg.setRoute(RouteUtils.createGenericRouteImpl(Id.create("4", Link.class), Id.create("5", Link.class)));
			egressLeg.getRoute().setDistance(200);
			egressLeg.getRoute().setTravelTime(300);
			plan.addLeg(egressLeg);
			plan.addActivity(home2);
			TripStructureUtils.getLegs(plan).forEach(leg -> TripStructureUtils.setRoutingMode(leg, SBBModes.PT));
			pop.addPerson(person);
		}

	}

}
