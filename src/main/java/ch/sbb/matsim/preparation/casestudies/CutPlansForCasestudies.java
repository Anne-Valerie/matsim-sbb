package ch.sbb.matsim.preparation.casestudies;

import ch.sbb.matsim.config.variables.Variables;
import ch.sbb.matsim.preparation.slicer.PopulationSlicerByAttribute;
import ch.sbb.matsim.zones.Zones;
import ch.sbb.matsim.zones.ZonesLoader;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.facilities.MatsimFacilitiesReader;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This class reads several (full) plans files and cuts selects those agents for a mini simulation that
 * - touch the select area at least once
 * - irrespective in which of the plans
 */
public class CutPlansForCasestudies {

    private final Zones zones;
    private final Set<Id<ActivityFacility>> facilityWhiteList;
    private final Set<String> whitelistZones;
    private final List<PlansCase> plansCases;
    private final Set<Id<Person>> commonPersonsWhitelist = new HashSet<>();
    private final Random random = MatsimRandom.getLocalInstance();

    public CutPlansForCasestudies(Zones zones, Set<Id<ActivityFacility>> facilityWhiteList, Set<String> whitelistZones, List<PlansCase> plansCases) {
        this.zones = zones;
        this.whitelistZones = whitelistZones;
        this.facilityWhiteList = facilityWhiteList;
        this.plansCases = plansCases;
    }

    public static void main(String[] args) {
        String zonesFile = args[0];
        String relevantZonesFile = args[1];
        String facilitiesFile = args[2];
        List<PlansCase> plansCases = new ArrayList<>();
        if (args.length % 2 != 1) {
            throw new RuntimeException("Insufficient number of arguments.");
        }
        for (int i = 3; i < args.length; i = i + 2) {
            PlansCase plansCase = new PlansCase();
            plansCase.inputFile = args[i];
            plansCase.outputFile = args[i + 1];
            plansCase.outputFacilitiesFile = plansCase.outputFile.replace(".xml.gz", "_facilities.xml.gz");
            plansCases.add(plansCase);
        }

        Zones zones = ZonesLoader.loadZones("zone", zonesFile, Variables.ZONE_ID);
        Set<String> whitelistZones = MergeRoutedAndUnroutedPlans.readWhiteListZones(relevantZonesFile);
        Set<Id<ActivityFacility>> facilityWhiteList = MergeRoutedAndUnroutedPlans.prepareRelevantFacilities(whitelistZones, zones, Collections.singletonList(facilitiesFile));

        CutPlansForCasestudies cutPlansForCasestudies = new CutPlansForCasestudies(zones, facilityWhiteList, whitelistZones, plansCases);
        cutPlansForCasestudies.readPlans();
        cutPlansForCasestudies.findRelevantPersonsForEachCase();
        cutPlansForCasestudies.mergePersonList();

        cutPlansForCasestudies.writePlans(facilitiesFile);

    }

    private void reArrangeSlices(Population population) {
        int maxSliceNoEndogeneous = getMaxSliceNo(population, true);
        if (maxSliceNoEndogeneous == -1) return;

        int maxSliceNoExogeneous = getMaxSliceNo(population, false);
        if (maxSliceNoExogeneous == -1) return;

        var endogeneousPersonList = getPersonList(population, true);
        var exogeneousPersonList = getPersonList(population, false);

        shuffleAndReassignSlices(population, endogeneousPersonList, maxSliceNoEndogeneous);
        shuffleAndReassignSlices(population, exogeneousPersonList, maxSliceNoExogeneous);
    }

    private int getMaxSliceNo(Population population, boolean isEndogeneous) {
        return population.getPersons().values().stream()
                .filter(person -> isEndogeneous == Variables.REGULAR.equals(PopulationUtils.getSubpopulation(person)))
                .mapToInt(person -> (int) person.getAttributes().getAttribute(PopulationSlicerByAttribute.SLICE))
                .max().orElse(-1);
    }

    private List<Id<Person>> getPersonList(Population population, boolean isEndogeneous) {
        return population.getPersons().values().stream()
                .filter(person -> isEndogeneous == Variables.REGULAR.equals(PopulationUtils.getSubpopulation(person)))
                .map(Person::getId)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void shuffleAndReassignSlices(Population population, List<Id<Person>> personList, int maxSliceNo) {
        Collections.shuffle(personList, random);
        int counter = 0;
        for (var personId : personList) {
            if (counter == maxSliceNo) {
                counter = 0;
            }
            population.getPersons().get(personId).getAttributes().putAttribute(PopulationSlicerByAttribute.SLICE, counter);
            counter++;
        }
    }


    private void writePlans(String facilitiesFile) {
        plansCases.parallelStream().forEach(plansCase -> {
            Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            if (!"-".equals(facilitiesFile)) {
                new MatsimFacilitiesReader(scenario).readFile(facilitiesFile);
            }
            Population outPopulation = scenario.getPopulation();
            for (Id<Person> personId : commonPersonsWhitelist) {
                Person p = plansCase.population.getPersons().get(personId);
                if (p != null) {
                    outPopulation.addPerson(p);
                } else {
                    LogManager.getLogger(getClass()).warn("Person " + personId + " not found in Population " + plansCase.inputFile);
                }
            }
            reArrangeSlices(outPopulation);
            new CutFacilitiesToPlans(scenario).cut();
            new PopulationWriter(outPopulation).write(plansCase.outputFile);
            new FacilitiesWriter(scenario.getActivityFacilities()).write(plansCase.outputFacilitiesFile);
        });
    }

    private void mergePersonList() {
        plansCases.forEach(p -> commonPersonsWhitelist.addAll(p.relevantPersons));
    }

    private void findRelevantPersonsForEachCase() {
        plansCases.parallelStream().forEach(plansCase -> {
            plansCase.relevantPersons = plansCase.population.getPersons().values().stream().filter(person -> {
                        Plan plan = person.getSelectedPlan();
                        return TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities).stream().anyMatch(activity -> {
                            Id<ActivityFacility> facilityId = activity.getFacilityId();
                            if (facilityId != null) {
                                return this.facilityWhiteList.contains(facilityId);
                            } else {
                                return MergeRoutedAndUnroutedPlans.isCoordinWhiteListZone(whitelistZones, zones, activity.getCoord());

                            }
                        });
                    })
                    .map(person -> person.getId())
                    .collect(Collectors.toSet());
        });
    }

    private void readPlans() {
        plansCases.parallelStream().forEach(plansCase -> {
            Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            new PopulationReader(scenario).readFile(plansCase.inputFile);
            plansCase.population = scenario.getPopulation();
        });
    }


    private static class PlansCase {
        String inputFile;
        String outputFile;
        String outputFacilitiesFile;
        Population population;
        Set<Id<Person>> relevantPersons;

    }
}
