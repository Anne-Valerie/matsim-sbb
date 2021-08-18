/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2020 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package ch.sbb.matsim.preparation.casestudies;

import ch.sbb.matsim.config.variables.Variables;
import ch.sbb.matsim.zones.Zone;
import ch.sbb.matsim.zones.Zones;
import ch.sbb.matsim.zones.ZonesLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkChangeEvent.ChangeType;
import org.matsim.core.network.NetworkChangeEvent.ChangeValue;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkChangeEventsWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

public class GenerateNetworkChangeEvents {

    private final int ENDTIME = 32 * 3600;
    private final int TIMESTEP = 15 * 60;

    private final double MINIMUMFREESPEED = 3;
    private final String networkFile;
    private final String eventsFile;
    private final String outputChangeEventsFile;
    private final String blacklistZones;
    private final String zonesFile;
    private Scenario sc;
    private TravelTimeCalculator tcc;
    private List<NetworkChangeEvent> networkChangeEvents;
    private Set<Id<Link>> blacklistlinks;

    public GenerateNetworkChangeEvents(String networkFile, String eventsFile, String outputChangeEventsFile, String blacklistZones, String zonesFile) {
        this.networkFile = networkFile;
        this.eventsFile = eventsFile;
        this.outputChangeEventsFile = outputChangeEventsFile;
        this.blacklistZones = blacklistZones;
        this.zonesFile = zonesFile;
        this.networkChangeEvents = new ArrayList<>();

    }

    public static void main(String[] args) {
        String networkFile = args[0];
        String eventsFile = args[1];
        String outputChangeEvents = args[2];
        String blacklistZones = args[3];
        String zonesFile = args[4];
        GenerateNetworkChangeEvents ncg = new GenerateNetworkChangeEvents(networkFile, eventsFile, outputChangeEvents, blacklistZones, zonesFile);
        ncg.run();

    }

    private void run() {
        prepareScen();
        tcc = readEvents();
        createNetworkChangeEvents(sc.getNetwork(), tcc);
        new NetworkChangeEventsWriter().write(outputChangeEventsFile, networkChangeEvents);
    }

    public void createNetworkChangeEvents(Network network, TravelTimeCalculator tcc2) {
        for (Link l : network.getLinks().values()) {

            if ((l.getAllowedModes().size() == 1) && l.getAllowedModes().contains("pt")) {
                continue;
            }
            if (blacklistlinks.contains(l.getId())) {
                continue;
            }
            double length = l.getLength();
            double previousTravelTime = l.getLength() / l.getFreespeed();

            for (double time = 0; time < ENDTIME; time = time + TIMESTEP) {

                double newTravelTime = tcc2.getLinkTravelTimes().getLinkTravelTime(l, time, null, null);
                if (newTravelTime != previousTravelTime) {
                    NetworkChangeEvent nce = new NetworkChangeEvent(time);
                    nce.addLink(l);
                    double newFreespeed = length / newTravelTime;
                    if (newFreespeed < MINIMUMFREESPEED) {
                        newFreespeed = MINIMUMFREESPEED;
                    }
                    ChangeValue freespeedChange = new ChangeValue(ChangeType.ABSOLUTE_IN_SI_UNITS, newFreespeed);
                    nce.setFreespeedChange(freespeedChange);
                    this.networkChangeEvents.add(nce);
                    previousTravelTime = newTravelTime;
                }
            }
        }
    }

    private void prepareScen() {

        try {
            sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            new MatsimNetworkReader(sc.getNetwork()).readFile(networkFile);
            Zones zones = ZonesLoader.loadZones("zones", zonesFile, Variables.ZONE_ID);
            Set<Id<Zone>> whitelistZones = Files.lines(Path.of(blacklistZones)).map(s -> Id.create(s, Zone.class)).collect(Collectors.toSet());
            this.blacklistlinks = sc.getNetwork().getLinks().values().parallelStream().filter(l -> {
                var z = zones.findZone(l.getFromNode().getCoord());
                if (z != null) {
                    if (whitelistZones.contains(z.getId())) {
                        return true;
                    }
                }
                return false;
            }).map(Link::getId).collect(Collectors.toSet());

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private TravelTimeCalculator readEvents() {
        EventsManager manager = EventsUtils.createEventsManager();

        TravelTimeCalculatorConfigGroup ttccg = new TravelTimeCalculatorConfigGroup();
        TravelTimeCalculator tc = new TravelTimeCalculator(sc.getNetwork(), ttccg);
        manager.addHandler(tc);
        new MatsimEventsReader(manager).readFile(eventsFile);
        return tc;
    }

    public List<NetworkChangeEvent> getNetworkChangeEvents() {
        return networkChangeEvents;
    }

}