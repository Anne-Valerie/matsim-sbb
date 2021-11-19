package ch.sbb.matsim.preparation;

import ch.sbb.matsim.config.variables.SBBModes;
import ch.sbb.matsim.config.variables.Variables;
import ch.sbb.matsim.csv.CSVWriter;
import ch.sbb.matsim.zones.Zone;
import ch.sbb.matsim.zones.Zones;
import ch.sbb.matsim.zones.ZonesLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.contrib.util.random.WeightedRandomSelection;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.MatsimFacilitiesReader;

public class GenerateEAPDemand {

    public static final String CB_HOME = "cbHome";
    private final Scenario scenario;
    private final Zones zones;
    private final List<ODDemand> demandData;
    private final double scaleFactorCar;
    private final double scaleFactorPt;
    private final Coord destinationCoord = new Coord(2607061.18838157, 1272114.54069301);
    private final Random r = MatsimRandom.getRandom();
    private WeightedRandomSelection<Integer> departureSelector = new WeightedRandomSelection<>();
    private WeightedRandomSelection<Integer> arrivalSelector = new WeightedRandomSelection<>();
    private Map<String, List<Id<ActivityFacility>>> facilitiesPerMunId = new HashMap<>();

    GenerateEAPDemand(String facilitiesFile, String demandMatrixFile, String zonesFile, String dailyDistribution, double scaleFactorPt, double scaleFactorCar) {
        this.scaleFactorPt = scaleFactorPt;
        this.scaleFactorCar = scaleFactorCar;
        this.scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimFacilitiesReader(scenario).readFile(facilitiesFile);
        zones = ZonesLoader.loadZones("zones", zonesFile, Variables.ZONE_ID);
        demandData = readDemandData(demandMatrixFile);

        try {
            Files.lines(Path.of(dailyDistribution)).forEach(s -> {
                String[] distribution = s.split(";");
                int hour = Integer.parseInt(distribution[0]);
                double departureWeight = Double.parseDouble(distribution[1]);
                double arrivalWeight = Double.parseDouble(distribution[2]);
                departureSelector.add(hour, departureWeight);
                arrivalSelector.add(hour, arrivalWeight);
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        prepareFacilities();

    }

    public static void main(String[] args) {
        String facilitiesFile = args[0];
        String demandMatrixFile = args[1];
        String zonesFile = args[2];
        String dailyDistributionFile = args[3];
        String outputFile = args[4];
        double scaleFactorPt = Double.parseDouble(args[5]);
        double scaleFactorCar = Double.parseDouble(args[6]);
        new GenerateEAPDemand(facilitiesFile, demandMatrixFile, zonesFile, dailyDistributionFile, scaleFactorPt, scaleFactorCar).generatePlans(outputFile);
    }

    private void generatePlans(String outputFile) {
        int departingPassengersCar = (int) Math.round(demandData.stream().mapToDouble(d -> d.carDemand).sum());
        int departingPassengersPt = (int) Math.round(demandData.stream().mapToDouble(d -> d.ptDemand).sum());
        Population carPopulation = PopulationUtils.createPopulation(scenario.getConfig());
        Population ptPopulation = PopulationUtils.createPopulation(scenario.getConfig());
        Map<String, MutableInt> amrPtDemand = new HashMap<>();
        Map<String, MutableInt> amrCarDemand = new HashMap<>();
        generateDemand(carPopulation, SBBModes.CAR, departingPassengersCar, amrCarDemand);
        generateDemand(ptPopulation, SBBModes.PT, departingPassengersPt, amrPtDemand);
        new PopulationWriter(carPopulation).write(outputFile + "_airport_road.xml");
        new PopulationWriter(ptPopulation).write(outputFile + "_airport_rail.xml");
        writeStatsMap(amrPtDemand, outputFile + "pt_demand_amr.csv");
        writeStatsMap(amrCarDemand, outputFile + "car_demand_amr.csv");
    }

    private void writeStatsMap(Map<String, MutableInt> amrPtDemand, String filename) {
        try (CSVWriter csvWriter = new CSVWriter(null, new String[]{"amr_id", "demand"}, filename)) {
            for (Entry<String, MutableInt> e : amrPtDemand.entrySet()) {
                csvWriter.set("amr_id", e.getKey());
                csvWriter.set("demand", e.getValue().toString());
                csvWriter.writeRow();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void generateDemand(Population population, String mode, int desiredPassengers, Map<String, MutableInt> demandStatsMap) {
        WeightedRandomSelection<String> zoneSelector = new WeightedRandomSelection<>();
        PopulationFactory factory = scenario.getPopulation().getFactory();

        demandData.stream().forEach(d -> zoneSelector.add(d.destinationZone, d.getDemand(mode)));
        for (int i = 0; i < desiredPassengers; i++) {
            int departureTime = departureSelector.select() * 3600 + r.nextInt(3600);
            int desiredArrivalTime = departureTime - 50 * 60 - r.nextInt(80 * 60);
            String homeZone = zoneSelector.select();
            final Zone zone = zones.getZone(Id.create(homeZone, Zone.class));
            if (zone == null) {
                Logger.getLogger(getClass()).error("Zone " + homeZone + " is not in Shape. ");

            }
            var facilities = facilitiesPerMunId.get(String.valueOf(zone.getAttribute("mun_id")));
            Coord homeCoord;
            String amr_id = zones.getZone(Id.create(homeZone, Zone.class)).getAttribute("amr_id").toString();
            demandStatsMap.computeIfAbsent(amr_id, (a) -> new MutableInt()).increment();
            if (facilities != null) {
                Id<ActivityFacility> homefacId = facilities.get(r.nextInt(facilities.size()));
                homeCoord = scenario.getActivityFacilities().getFacilities().get(homefacId).getCoord();

            } else {
                Logger.getLogger(getClass()).error("Zone " + homeZone + " has no facilities. ");

                var c = zone.getEnvelope().centre();
                homeCoord = new Coord(c.x, c.y);
            }

            //deliberately not use facility in plan
            double beelineDistance = CoordUtils.calcEuclideanDistance(homeCoord, destinationCoord);
            double tt = beelineDistance / (50 / 3.6);
            Person person = factory.createPerson(Id.createPersonId("EAP_dep_" + mode + "_" + homeZone + "_" + i));
            Plan plan = factory.createPlan();
            Activity home = factory.createActivityFromCoord(CB_HOME, homeCoord);
            home.setEndTime(Math.max(0, desiredArrivalTime - tt));
            Leg leg = factory.createLeg(mode);
            Activity airport = factory.createActivityFromCoord(CB_HOME, destinationCoord);
            person.addPlan(plan);
            plan.addActivity(home);
            plan.addLeg(leg);
            plan.addActivity(airport);
            population.addPerson(person);
        }

        for (int i = 0; i < desiredPassengers; i++) {
            int arrivalTime = arrivalSelector.select() * 3600 + r.nextInt(3600);
            int desiredAirportDepartureTime = arrivalTime + r.nextInt(45 * 60);
            String homeZone = zoneSelector.select();
            Coord homeCoord;
            final Zone zone = zones.getZone(Id.create(homeZone, Zone.class));
            if (zone == null) {
                Logger.getLogger(getClass()).error("Zone " + homeZone + " is not in Shape. ");

            }
            var facilities = facilitiesPerMunId.get(String.valueOf(zone.getAttribute("mun_id")));
            String amr_id = zones.getZone(Id.create(homeZone, Zone.class)).getAttribute("amr_id").toString();
            demandStatsMap.computeIfAbsent(amr_id, (a) -> new MutableInt()).increment();
            if (facilities != null) {
                Id<ActivityFacility> homefacId = facilities.get(r.nextInt(facilities.size()));
                homeCoord = scenario.getActivityFacilities().getFacilities().get(homefacId).getCoord();

            } else {
                Logger.getLogger(getClass()).error("Zone " + homeZone + " has no facilities. ");
                var c = zone.getEnvelope().centre();
                homeCoord = new Coord(c.x, c.y);
            }

            //deliberately not use facility in plan
            Person person = factory.createPerson(Id.createPersonId("EAP_arr_" + mode + "_" + homeZone + "_" + i));
            Plan plan = factory.createPlan();
            Activity airport = factory.createActivityFromCoord(CB_HOME, destinationCoord);
            airport.setEndTime(desiredAirportDepartureTime);
            Leg leg = factory.createLeg(mode);
            Activity home = factory.createActivityFromCoord(CB_HOME, homeCoord);
            person.addPlan(plan);
            plan.addActivity(airport);
            plan.addLeg(leg);
            plan.addActivity(home);
            population.addPerson(person);
        }

    }

    private void prepareFacilities() {
        for (ActivityFacility facility : scenario.getActivityFacilities().getFacilities().values()) {
            Zone z = zones.findZone(facility.getCoord());
            if (z != null) {
                facilitiesPerMunId.computeIfAbsent(z.getAttribute("mun_id").toString(), (s) -> new ArrayList<>()).add(facility.getId());
            }

        }
    }

    private List<ODDemand> readDemandData(String demandMatrixFile) {
        try {
            return Files.lines(Path.of(demandMatrixFile)).map(s -> {
                var dem = s.split(";");
                if (dem.length > 0) {
                    return new ODDemand(dem[0], dem[1], Double.parseDouble(dem[2]) * scaleFactorPt, Double.parseDouble(dem[3]) * scaleFactorCar);
                } else {
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException();
        }

    }

    private static class ODDemand {

        String destinationZone;
        String munID;
        double ptDemand;
        double carDemand;

        public ODDemand(String destinationZone, String munID, double ptDemand, double carDemand) {
            this.destinationZone = destinationZone;
            this.munID = munID;
            this.ptDemand = ptDemand;
            this.carDemand = carDemand;
        }

        public double getDemand(String mode) {
            if (mode.equals(SBBModes.CAR)) {
                return carDemand;
            } else if (mode.equals(SBBModes.PT)) {
                return ptDemand;
            } else {
                throw new RuntimeException("unknown mode " + mode);
            }
        }
    }

}