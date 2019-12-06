package ch.sbb.matsim.config.variables;

import org.matsim.api.core.v01.TransportMode;

import java.util.HashMap;
import java.util.Map;

public class SBBModes {
    private SBBModes() {}

    public static final String CAR = TransportMode.car;
    public static final String RIDE = TransportMode.ride;
    public static final String PT = TransportMode.pt;
    public static final String WALK = TransportMode.walk;
    public static final String BIKE = TransportMode.bike;
    public static final String AVTAXI = "avtaxi";
    public static final String AVFEEDER = "avfeeder";
    public static final String DRT = TransportMode.drt;


    public static final int DEFAULT_MODE_HIERARCHY = 99;
    public final static Map<String, Integer> mode2HierarchalNumber;

    static {
        mode2HierarchalNumber = new HashMap<>();

        mode2HierarchalNumber.put(PT, 0);
        mode2HierarchalNumber.put(CAR, 10);
        mode2HierarchalNumber.put(RIDE, 20);
        mode2HierarchalNumber.put(BIKE, 30);
        mode2HierarchalNumber.put(WALK, 40);
    }
}
