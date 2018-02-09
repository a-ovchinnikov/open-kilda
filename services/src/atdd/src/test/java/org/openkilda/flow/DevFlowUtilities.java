/* Copyright 2017 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.flow;

import java.nio.file.Files;
import org.openkilda.messaging.payload.flow.FlowEndpointPayload;
import org.openkilda.messaging.payload.flow.FlowPayload;
import org.openkilda.messaging.model.Flow;
import org.openkilda.LinksUtils;

public class DevFlowUtilities implements FlowUtilities {

    public boolean prepareNetwork(){
        // And a nonrandom linear topology of 7 switches
        // And topology contains 12 links
        // And a clean flow topology
        // Single topology is supposed to be used with these tests
        String fileName = "topologies/nonrandom-topology.json";
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource(fileName).getFile());
        String json = new String(Files.readAllBytes(file.toPath()));
        boolean topologyCreated = TopologyHelp.CreateMininetTopology(json);
        boolean enoughLinks = getLinksCount(12) == 12;
        FlowUtils.cleanupFlows();
        return topologyCreated && enoughLinks;
    }

    public FlowPayload flowCreate(String flowDescription) {
        // Flows can span one, two or multiple switches,
        // vlan tags could be absent, present, same or different
        // on ingress and egress ports.
        String[] description = flowDescription.split(" ");
        String srcSwitch, dstSwitch;
        int srcVlan, dstVlan;
        // Topology is not supposed to be changed often. Endpoints are
        // hardcoded for now, but it is better to rework topology description
        // in corresponding json.
        int srcPort = 1, dstPort = 2;
        switch (description[0]){
            case "single": srcSwitch = "foo"; dstSwitch = "bar"; srcPort = 1; dstPort = 2; break;
            case "two": srcSwitch = "foo"; dstSwitch = "bar";  srcPort = 1; dstPort = 2; break;
            case "many": srcSwitch = "foo"; dstSwitch = "bar";  srcPort = 1; dstPort = 2; break;
            default: throw new IllegalArgumentException("No such option for flows: " + desctiption[0]);
        }
        switch (description[2]){
            case "not": srcVlan = 0; dstVlan = 0; break;
            case "differently": srcVlan = 200; dstVlan = 100; break;
            case "same": srcVlan = 100; dstVlan = 100; break;
            case "tagged": srcVlan = 0; dstVlan = 100; break;
            case "untagged": srcVlan = 100; dstVlan = 0; break;
            default: throw new IllegalArgumentException("No such option for flows: " + desctiption[0]);
        }

        FlowPayload flowPayload = new FlowPayload("ExperimentalFlow",
                new FlowEndpointPayload(srcSwitch, srcPort, srcVlan),
                new FlowEndpointPayload(dstSwitch, dstPort, dstVlan),
                bandwidth, flowId, null);

        FlowPayload response = FlowUtils.putFlow(flowPayload);
        return flowPayload;
    }

    public boolean trafficFlowsThrough(Flow flow) {
        Client client = ClientBuilder.newClient(new ClientConfig());
        Response result = client
                .target(trafficEndpoint)
                .path("/checkflowtraffic")
                .queryParam("srcswitch", flow.getSourceSwitch())
                .queryParam("dstswitch", flow.getDestinationSwitch())
                .queryParam("srcport", flow.getSourcePort())
                .queryParam("dstport", flow.getDestinationSwitch())
                .queryParam("srcvlan", flow.getSourceVlan())
                .queryParam("dstvlan", flow.getDestinationSwitch())
                .request()
                .get();
        return result.getStatus() == 200;
    }

    private int getLinksCount(int expectedLinks) throws Exception {
        int actualLinks = 0;

        for (int i = 0; i < 10; i++) {
            List<IslInfoData> links = LinksUtils.dumpLinks();
            actualLinks = links.size();

            if (actualLinks == expectedLinks) {
                break;
            }

            TimeUnit.SECONDS.sleep(3);
        }
        return actualLinks;
}
