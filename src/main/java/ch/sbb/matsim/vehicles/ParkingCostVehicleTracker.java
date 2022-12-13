package ch.sbb.matsim.vehicles;

import ch.sbb.matsim.config.ParkingCostConfigGroup;
import ch.sbb.matsim.events.ParkingCostEvent;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.vehicles.Vehicle;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;

/**
 * @author mrieser
 */
public class ParkingCostVehicleTracker implements ActivityStartEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler {

	private final static Logger log = LogManager.getLogger(ParkingCostVehicleTracker.class);

	private final Map<Id<Vehicle>, ParkingInfo> parkingPerVehicle = new HashMap<>();
	private final Map<Id<Person>, Id<Vehicle>> lastVehiclePerDriver = new HashMap<>();
	private final Scenario scenario;
	private final EventsManager events;
	private final String parkingCostAttributeName;
	private boolean badAttributeTypeWarningShown = false;

	@Inject
	public ParkingCostVehicleTracker(Scenario scenario, EventsManager events) {
		this.scenario = scenario;
		ParkingCostConfigGroup parkCostConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), ParkingCostConfigGroup.class);
		this.parkingCostAttributeName = parkCostConfig.getZonesParkingCostAttributeName();
		this.events = events;
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		ParkingInfo pi = this.parkingPerVehicle.remove(event.getVehicleId());
		if (pi == null) {
			return;
		}
		Link link = this.scenario.getNetwork().getLinks().get(pi.parkingLinkId);

		Object value = link.getAttributes().getAttribute(this.parkingCostAttributeName);
		if (value == null) {
			return;
		}
		if (value instanceof Number) {
			double parkDuration = event.getTime() - pi.startParkingTime;
			double hourlyParkingCost = ((Number) value).doubleValue();
			double parkingCost = hourlyParkingCost * (parkDuration / 3600.0);
			this.events.processEvent(new ParkingCostEvent(event.getTime(), pi.driverId, event.getVehicleId(), link.getId(), parkingCost));
		} else if (!this.badAttributeTypeWarningShown) {
			log.error("ParkingCost attribute must be of type Double or Integer, but is of type " + value.getClass() + ". This message is only given once.");
			this.badAttributeTypeWarningShown = true;
		}
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		ParkingInfo pi = new ParkingInfo(event.getLinkId(), event.getPersonId(), event.getTime());
		this.parkingPerVehicle.put(event.getVehicleId(), pi);
		this.lastVehiclePerDriver.put(event.getPersonId(), event.getVehicleId());
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		if (event.getActType().contains("home")) {
			// don't track the vehicle parking if the agent is at home, assuming the agent does not have to pay at his home location
			Id<Vehicle> vehicleId = this.lastVehiclePerDriver.get(event.getPersonId());
			if (vehicleId != null) {
				this.parkingPerVehicle.remove(vehicleId);
			}
		}
	}

	@Override
	public void reset(int iteration) {
		this.parkingPerVehicle.clear();
		this.lastVehiclePerDriver.clear();
	}

	private static class ParkingInfo {

		final Id<Link> parkingLinkId;
		final Id<Person> driverId;
		final double startParkingTime;

		ParkingInfo(Id<Link> parkingLinkId, Id<Person> driverId, double startParkingTime) {
			this.parkingLinkId = parkingLinkId;
			this.driverId = driverId;
			this.startParkingTime = startParkingTime;
		}
	}
}
