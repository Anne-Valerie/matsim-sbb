package ch.sbb.matsim.scoring;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.config.groups.ScenarioConfigGroup;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.CharyparNagelScoringParameters;
import org.matsim.core.scoring.functions.CharyparNagelScoringParametersForPerson;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.pt.PtConstants;
import org.matsim.pt.config.TransitConfigGroup;
import org.matsim.utils.objectattributes.ObjectAttributes;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author jlie based on org.matsim.core.scoring.functions.RandomizedCharyparNagelScoringParameters
 * adding: the default CharyparNagelScoringParametersForPerson seems to be org.matsim.core.scoring.functions.SubpopulationCharyparNagelScoringParameters
 */

public class SBBCharyparNagelScoringParametersForPerson implements CharyparNagelScoringParametersForPerson {

    private final PlanCalcScoreConfigGroup config;
    private final ScenarioConfigGroup scConfig;
    private final Map<Person, CharyparNagelScoringParameters> paramsPerPerson = new LinkedHashMap<>();
    private final ObjectAttributes personAttributes;
    private final String subpopulationAttributeName;
    private final TransitConfigGroup transitConfigGroup;
    Map<String, PlanCalcScoreConfigGroup.ModeParams> paramsPerMode;
    private Logger log = Logger.getLogger(SBBCharyparNagelScoringParametersForPerson.class);

    public SBBCharyparNagelScoringParametersForPerson(Scenario scenario) {
        this(scenario.getConfig().plans(),
                scenario.getConfig().planCalcScore(),
                scenario.getConfig().scenario(),
                scenario.getPopulation(),
                scenario.getConfig().transit());
    }

    SBBCharyparNagelScoringParametersForPerson(
            PlansConfigGroup plansConfigGroup,
            PlanCalcScoreConfigGroup planCalcScoreConfigGroup,
            ScenarioConfigGroup scenarioConfigGroup,
            Population population,
            TransitConfigGroup transitConfigGroup) {
        this.config = planCalcScoreConfigGroup;
        this.scConfig = scenarioConfigGroup;
        this.personAttributes = population.getPersonAttributes();
        this.subpopulationAttributeName = plansConfigGroup.getSubpopulationAttributeName();
        this.transitConfigGroup = transitConfigGroup;
        this.paramsPerMode = new HashMap<>();
        for (Map.Entry<String, PlanCalcScoreConfigGroup.ModeParams> aModeEntry: this.config.getModes().entrySet()) {
            String mode = aModeEntry.getKey();
            PlanCalcScoreConfigGroup.ModeParams modeParams = aModeEntry.getValue();
            String[] splitted = mode.split("_");
            if (splitted.length == 2) {
                if (splitted[0].equals(TransportMode.pt)) {
                    this.paramsPerMode.put(splitted[1], modeParams);
                }
                else if (mode.equals(TransportMode.transit_walk)) {
                    this.paramsPerMode.put(mode, modeParams);
                }
                else {
                    throw new IllegalArgumentException("mode separated by exactly one _, but first argument is not pt " + mode);
                }
            }
            else if (splitted.length == 1) {
                this.paramsPerMode.put(mode, modeParams);
            }
            else throw new IllegalArgumentException("not well SBB-formatted mode " + mode);
        }
    }

    @Override
    public CharyparNagelScoringParameters getScoringParameters(Person person) {
        if (!this.paramsPerPerson.containsKey(person)) {
            final String subpopulation = (String) personAttributes.getAttribute(person.getId().toString(), subpopulationAttributeName);
            final String aboType = (String) personAttributes.getAttribute(person.getId().toString(), "season_ticket"); // TODO define "season_ticket" as static string?
            PlanCalcScoreConfigGroup.ModeParams modeParams = paramsPerMode.get(aboType);
            if (modeParams == null) {
                log.info("no mode parameters defined for season-ticket " + aboType);
                modeParams = paramsPerMode.get(TransportMode.pt);
                if (modeParams == null) {
                    throw new IllegalStateException("mode parameters for pt must be defined");
                }
            }
            CharyparNagelScoringParameters.Builder builder = new CharyparNagelScoringParameters.Builder(
                    this.config, this.config.getScoringParameters(subpopulation),
                    scConfig);
            if (transitConfigGroup.isUseTransit()) {
                // jlie (17.08.2017): this is from org.matsim.core.scoring.functions.SubpopulationCharyparNagelScoringParameters
                // without this MATSim does not know "pt interaction" and throws an IllegalArgumentException in CharyparNagelActivityScoring
                // yyyy this should go away somehow. :-)
                PlanCalcScoreConfigGroup.ActivityParams transitActivityParams = new PlanCalcScoreConfigGroup.ActivityParams(PtConstants.TRANSIT_ACTIVITY_TYPE);
                transitActivityParams.setTypicalDuration(120.0);
                transitActivityParams.setOpeningTime(0.) ;
                transitActivityParams.setClosingTime(0.) ;
                ActivityUtilityParameters.Builder modeParamsBuilder = new ActivityUtilityParameters.Builder(transitActivityParams);
                modeParamsBuilder.setScoreAtAll(false);
                builder.setActivityParameters(PtConstants.TRANSIT_ACTIVITY_TYPE, modeParamsBuilder);
            }
            builder.setModeParameters(TransportMode.pt, new ModeUtilityParameters.Builder(modeParams));
            this.paramsPerPerson.put(person, builder.build());
        }
        return this.paramsPerPerson.get(person);
    }
}
