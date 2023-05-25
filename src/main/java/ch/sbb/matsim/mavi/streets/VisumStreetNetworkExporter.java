package ch.sbb.matsim.mavi.streets;

import ch.sbb.matsim.counts.VisumToCounts;
import ch.sbb.matsim.mavi.PolylinesCreator;
import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.Dispatch;
import com.jacob.com.SafeArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class VisumStreetNetworkExporter {

    private final static Logger log = LogManager.getLogger(VisumStreetNetworkExporter.class);

    private Scenario scenario;
    private NetworkFactory nf;
    private final Map<Id<Link>, String> wktLineStringPerVisumLink = new HashMap<>();

    public static void main(String[] args) throws IOException {
        String inputvisum = args[0];
        String outputPath = args[1];
        int visumVersion = 21;
        boolean exportCounts = true;
        if (args.length > 2) {
            exportCounts = Boolean.parseBoolean(args[2]);
        }

		VisumStreetNetworkExporter exp = new VisumStreetNetworkExporter();
		exp.run(inputvisum, outputPath, visumVersion, exportCounts, true);
		exp.writeNetwork(outputPath);

	}

	public static Id<Link> createLinkId(String fromNode, String visumLinkId) {
		return Id.createLinkId(Integer.toString(Integer.parseInt(fromNode), 36) + "_" + Integer.toString(Integer.parseInt(visumLinkId), 36));
	}

	public static Map.Entry<Integer, Integer> extractVisumLinkAndNodeId(Id<Link> linkId) {
		try {
			int visumFromNodeId = Integer.parseInt(linkId.toString().split("_")[0], 36);
			int visumLinkId = Integer.parseInt(linkId.toString().split("_")[1], 36);
			return Map.entry(visumFromNodeId, visumLinkId);
		} catch (NumberFormatException e) {
			log.error("Failed to extract Visum Link and FromNode Ids from " + linkId);
			throw e;
		}
	}

	public void run(String inputvisum, String outputPath, int visumVersion, boolean exportCounts, boolean exportPolylines) throws IOException {
		ActiveXComponent visum = new ActiveXComponent("Visum.Visum." + visumVersion);
		log.info("VISUM Client gestartet.");
		Dispatch.call(visum, "LoadVersion", inputvisum);

		this.scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		this.nf = scenario.getNetwork().getFactory();

		Dispatch net = Dispatch.get(visum, "Net").toDispatch();

		Dispatch filters = Dispatch.get(visum, "Filters").toDispatch();
		Dispatch.call(filters, "InitAll");
		if (exportCounts) {
			this.exportCountStations(visum, outputPath);
		}
		String[][] nodes = importNodes(net, "No", "XCoord", "YCoord");
		String[][] links = importLinks(net, "FromNodeNo", "ToNodeNo", "Length", "CapPrT", "V0PrT", "TypeNo",
				"NumLanes", "TSysSet", "accessControlled", "WKTPoly", "No");
		createNetwork(nodes, links);

		// Export Polylines
		if (exportPolylines) {
			new PolylinesCreator().runStreets(this.scenario.getNetwork(), wktLineStringPerVisumLink, "polylines.csv", outputPath);
		}

	}

	private void exportCountStations(Dispatch net, String outputFolder) throws IOException {
		VisumToCounts visumToCounts = new VisumToCounts();

		File file = new File(outputFolder, "counts");
		File csv = new File(outputFolder, "counts.csv");
		visumToCounts.exportCountStations(net, file.getAbsolutePath(), csv.getAbsolutePath());
	}

	private String[][] importNodes(Dispatch net, String... attribute) {
		Dispatch nodes = Dispatch.get(net, "Nodes").toDispatch();//import nodes
		return this.toArray(nodes, attribute);
	}

	private String[][] importLinks(Dispatch net, String... attribute) {
		Dispatch links = Dispatch.get(net, "Links").toDispatch();
		return toArray(links, attribute);
	}

	private String[][] toArray(Dispatch objects, String... attributes) {
		int n = Integer.parseInt(Dispatch.call(objects, "CountActive").toString()); //number of nodes

		String[][] attarray = new String[n][attributes.length]; //2d array containing all attributes of all nodes
		int j = 0;

		for (String att : attributes) {
			log.info(att);
			SafeArray a = Dispatch.call(objects, "GetMultiAttValues", att).toSafeArray();
			int i = 0;
			while (i < n) {
				attarray[i][j] = a.getString(i, 1);
				i++;
			}
			log.info("done");
			j++;
		}
		return attarray;
	}

	private void createNetwork(String[][] attarraynode, String[][] attarraylink) {
		Network network = this.scenario.getNetwork();
		network.setCapacityPeriod(3600);

		for (String[] anAttarraynode : attarraynode) {
			Coord coord = new Coord(Double.parseDouble(anAttarraynode[1]),
					Double.parseDouble(anAttarraynode[2]));
			Node node = nf.createNode(Id.createNodeId("C_" + anAttarraynode[0]), coord);
			network.addNode(node);
		}

		for (String[] anAttarraylink : attarraylink) {
			if (anAttarraylink[7].contains("P")) {
				final String fromNode = anAttarraylink[0];
				final String toNode = anAttarraylink[1];
				final String visumLinkNo = anAttarraylink[10];
				Id<Link> id = createLinkId(fromNode, visumLinkNo);
				Link link = createLink(id, fromNode, toNode, Double.parseDouble(anAttarraylink[2]),
						Double.parseDouble(anAttarraylink[3]), (Double.parseDouble(anAttarraylink[4])),
						Integer.parseInt(anAttarraylink[6]));
				if (link != null) {
					NetworkUtils.setType(link, anAttarraylink[5]);
					int ac = 0;
					try {
						ac = Integer.parseInt(anAttarraylink[8]);
					} catch (NumberFormatException e) {
						log.warn("Access Control not defined for link " + link.getId() + ". Assuming = 0");
					}
					link.getAttributes().putAttribute("accessControlled", ac);
					network.addLink(link);
				}
				this.wktLineStringPerVisumLink.put(id, anAttarraylink[9]);
			}
		}
	}

	private Link createLink(Id<Link> id, String fromNode, String toNode, double length, double cap, double v, int numlanes) {
		Node fnode = scenario.getNetwork().getNodes().get(Id.createNodeId("C_" + fromNode));
		Node tnode = scenario.getNetwork().getNodes().get(Id.createNodeId("C_" + toNode));

		if (fnode == null || tnode == null) {
			return null;
		}
		Set<String> modeset = new HashSet<>(Arrays.asList("car", "ride"));
		Link link = nf.createLink(id, fnode, tnode);
		if (length < 0.01) {
			length = 0.01;
		}
		if (numlanes < 1) {
			numlanes = 1;
		}
		length *= 1000.;
		double beelineDistance = CoordUtils.calcEuclideanDistance(fnode.getCoord(), tnode.getCoord());
		if (length < beelineDistance) {
			if (beelineDistance - length > 1.0) {
				log.warn(link.getId() + " has a length (" + length + ") shorter than its beeline distance (" + beelineDistance + "). Will not correct this.");
			}
        }
        link.setLength(length);
        link.setCapacity(cap);
        link.setFreespeed(v / 3.6);
        link.setNumberOfLanes(numlanes);
        link.setAllowedModes(modeset);

        return link;
    }

    private void writeNetwork(String outputFolder) {
        org.matsim.core.network.algorithms.NetworkCleaner cleaner = new org.matsim.core.network.algorithms.NetworkCleaner();
        cleaner.run(scenario.getNetwork());

        File file = new File(outputFolder, "network.xml.gzwithPolylines.xml.gz");
        new NetworkWriter(this.scenario.getNetwork()).write(file.getAbsolutePath());
    }

    public Network getNetwork() {
        return scenario.getNetwork();
    }
}
