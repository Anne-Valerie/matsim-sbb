package ch.sbb.matsim.projects.synpop;

import ch.sbb.matsim.config.variables.Variables;
import ch.sbb.matsim.csv.CSVWriter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.util.List;

public class SingleTripAgentToCSVConverter {

    public static void main(String[] args) throws IOException {
        String inputPlansFile = args[0];
        String outputCSVFile = args[1];
        String subpopulationToSet = "freight_road";

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(inputPlansFile);
        Population population = scenario.getPopulation();


        writeSingleTripsAsCSV(outputCSVFile, population, subpopulationToSet);

    }

    public static void writeSingleTripsAsCSV(String outputCSVFile, Population population) throws IOException {
        writeSingleTripsAsCSV(outputCSVFile, population, "");

    }

    public static void writeSingleTripsAsCSV(String outputCSVFile, Population population, String subpulationToSet) throws IOException {
        String tripPurpose = Variables.MOBiTripAttributes.PURPOSE;
        String direction = Variables.MOBiTripAttributes.DIRECTION;
        String toActivityY = "to_activity_y";
        String toActivityX = "to_activity_x";
        String toActivityType = "to_activity_type";
        String legMode = "leg_mode";
        String fromActivityEndTime = "from_activity_end_time";
        String fromActivityY = "from_activity_y";
        String fromActivityX = "from_activity_x";
        String fromActivityType = "from_activity_type";
        List<String> columns = List.of(Variables.PERSONID, Variables.SUBPOPULATION, fromActivityType, fromActivityX, fromActivityY, fromActivityEndTime, legMode, toActivityType, toActivityX, toActivityY, direction, tripPurpose);

        try (CSVWriter writer = new CSVWriter(columns, outputCSVFile)) {
            for (Person person : population.getPersons().values()) {

                Plan plan = person.getSelectedPlan();
                if (plan.getPlanElements().size() != 3) {
                    throw new RuntimeException("Some plans have more or less than three plan elements. This code is not designed to transform them. Agent: " + person.getId());
                }
                writer.set(Variables.PERSONID, person.getId().toString());
                String subpopulation = PopulationUtils.getSubpopulation(person) != null ? PopulationUtils.getSubpopulation(person) : subpulationToSet;
                writer.set(Variables.SUBPOPULATION, subpopulation);
                Activity start = (Activity) plan.getPlanElements().get(0);
                Leg leg = (Leg) plan.getPlanElements().get(1);
                Activity end = (Activity) plan.getPlanElements().get(2);
                writer.set(fromActivityType, start.getType());
                writer.set(fromActivityEndTime, String.valueOf((int) start.getEndTime().seconds()));
                writer.set(fromActivityX, String.valueOf(start.getCoord().getX()));
                writer.set(fromActivityY, String.valueOf(start.getCoord().getY()));
                writer.set(legMode, leg.getMode());
                writer.set(toActivityType, end.getType());
                writer.set(toActivityX, String.valueOf(end.getCoord().getX()));
                writer.set(toActivityY, String.valueOf(end.getCoord().getY()));
                Object purpose = start.getAttributes().getAttribute(tripPurpose);
                writer.set(tripPurpose, purpose == null ? "" : purpose.toString());
                writer.writeRow();
            }
        }
    }
}
