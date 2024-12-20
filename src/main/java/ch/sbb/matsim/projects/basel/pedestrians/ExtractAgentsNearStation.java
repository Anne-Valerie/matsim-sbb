package ch.sbb.matsim.projects.basel.pedestrians;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ExtractAgentsNearStation {


    public static final int RADIUS = 2000;

    public ExtractAgentsNearStation(List<String> plans, Coord coord, String extractedTrips, Network network) {
        run(plans, coord, extractedTrips, network);

    }

    public static void main(String[] args) {
        String inputPlans1 = "\\\\wsbbrz0283\\mobi\\50_Ergebnisse\\MOBi_4.0\\2040\\sim\\3.3.2040.11.50pct\\output_slice0\\M3340.11.output_experienced_plans.xml.gz";
        String inputPlans2 = "\\\\wsbbrz0283\\mobi\\50_Ergebnisse\\MOBi_4.0\\2040\\sim\\3.3.2040.11.50pct\\output_slice1\\M3340.11.output_experienced_plans.xml.gz";
        String networkFile = "\\\\wsbbrz0283\\mobi\\50_Ergebnisse\\MOBi_4.0\\2040\\sim\\3.3.2040.11.50pct\\output_slice1\\M3340.11.output_network.xml.gz";
        String extractedTrips = "\\\\wsbbrz0283\\mobi\\40_Projekte\\20240911_Fussgaenger_Oberwinterthur\\plans\\plans-near-station.xml.gz";
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkFile);
        List<String> plans = List.of(inputPlans1, inputPlans2);
        Coord basel = new Coord(2699593.415, 1262742.062);
        new ExtractAgentsNearStation(plans, basel, extractedTrips, network);


    }

    private void run(List<String> plans, Coord relCoord, String extractedTrips, Network network) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Map<Id<Person>, Person> persons = new ConcurrentHashMap<>();
        plans.parallelStream().forEach(s -> {
            Scenario readScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            StreamingPopulationReader spr = new StreamingPopulationReader(readScenario);
            spr.addAlgorithm(person -> {
                Plan plan = person.getSelectedPlan();
                for (Activity activity : TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.StagesAsNormalActivities)) {
                    Coord coord = activity.getCoord();
                    if (coord == null) {
                        coord = network.getLinks().get(activity.getLinkId()).getFromNode().getCoord();
                    }
                    if (CoordUtils.calcEuclideanDistance(coord, relCoord) < RADIUS) {
                        PersonUtils.removeUnselectedPlans(person);
                        persons.put(person.getId(), person);
                    }
                }


            });


            spr.readFile(s);
        });
        Population extractedPopulation = scenario.getPopulation();
        persons.values().forEach(person -> extractedPopulation.addPerson(person));
        new PopulationWriter(extractedPopulation).write(extractedTrips);
    }
}
