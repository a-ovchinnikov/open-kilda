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

package org.openkilda.atdd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import cucumber.api.java.en.Given;
import cucumber.api.java.en.When;
import cucumber.api.java.en.Then;
import cucumber.api.PendingException;

import static java.util.Base64.getEncoder;
import static org.openkilda.DefaultParameters.northboundEndpoint;
import static org.openkilda.DefaultParameters.topologyEndpoint;
import static org.openkilda.DefaultParameters.topologyPassword;
import static org.openkilda.DefaultParameters.topologyUsername;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.openkilda.messaging.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jackson.JacksonFeature;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientConfig;
import org.openkilda.messaging.model.Flow;
import org.openkilda.flow.DevFlowUtilities;
import org.openkilda.flow.FlowUtilities;

public class UniversalCRUDTest {

    private FlowUtilities utils;
    private Flow currentFlow;

    private static final String auth = topologyUsername + ":" + topologyPassword;
    private static final String authHeaderValue = "Basic " + getEncoder().encodeToString(auth.getBytes());
    public static final Client clientFactory(){
        Client client = ClientBuilder.newClient(new ClientConfig()).register(JacksonFeature.class);
        return client;
    }

    public UniversalCRUDTest () {
        String testEnv = System.getenv("TEST_ENVIRONMENT");
        if (testEnv.equals( "development")){
            utils = new DevFlowUtilities();
        }
        else {
            throw new RuntimeException("Unknown development environment");
        }
    }
    @Given("^controlled network ready for testing$")
    public void controlled_network_ready_for_testing() throws Throwable {
        // Utilities implementtaions take care of network initialization.
        // In the case of dev environment switches and links must be created
        // in this phase, in the case of staging this has been done already.
        assertTrue(utils.prepareNetwork());
    }

    @Then("^this flow can be deleted$")
    public void this_flow_can_be_deleted() throws Throwable {
        // Flow deletion is not dependent on which env tests are running.
        Client client = clientFactory();

        Response response = client
                .target(northboundEndpoint)
                .path("/api/v1/flows")
                .path("{flowid}")
                .resolveTemplate("flowid", currentFlow.getFlowId())
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, authHeaderValue)
                .header(Utils.CORRELATION_ID, String.valueOf(System.currentTimeMillis()))
                .delete();

        assertTrue(response.getStatus() == 200);
    }

    @Then("^(.*) flow can be created$")
    public void flow_can_be_created(final String flowDescription) throws Throwable {
          currentFlow = utils.flowCreate(flowDescription);
          assertNotNull(currentFlow);
    }

    @Then("^traffic can flow through this flow$")
    public void traffic_can_flow_through_this_flow() throws Throwable {
          assertTrue(utils.trafficFlowsThrough(currentFlow));
    }

    @Then("^no traffic flows through this flow$")
    public void no_traffic_flows_through_this_flow() throws Throwable {
          assertFalse(utils.trafficFlowsThrough(currentFlow));
    }
}
