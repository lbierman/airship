/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.galaxy.coordinator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.proofpoint.galaxy.shared.AgentStatus;
import com.proofpoint.galaxy.shared.SlotLifecycleState;
import com.proofpoint.galaxy.shared.MockUriInfo;
import com.proofpoint.galaxy.shared.SlotStatus;
import com.proofpoint.galaxy.shared.SlotStatusRepresentation;
import com.proofpoint.galaxy.shared.VersionConflictException;
import com.proofpoint.galaxy.shared.VersionsUtil;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.units.Duration;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.proofpoint.galaxy.coordinator.CoordinatorSlotResource.MIN_PREFIX_SIZE;
import static com.proofpoint.galaxy.shared.Strings.shortestUniquePrefix;
import static com.proofpoint.galaxy.coordinator.TestingMavenRepository.MOCK_REPO;
import static com.proofpoint.galaxy.shared.AgentLifecycleState.ONLINE;
import static com.proofpoint.galaxy.shared.AssignmentHelper.APPLE_ASSIGNMENT;
import static com.proofpoint.galaxy.shared.AssignmentHelper.BANANA_ASSIGNMENT;
import static com.proofpoint.galaxy.shared.ExtraAssertions.assertEqualsNoOrder;
import static com.proofpoint.galaxy.shared.SlotLifecycleState.RUNNING;
import static com.proofpoint.galaxy.shared.SlotLifecycleState.STOPPED;
import static com.proofpoint.galaxy.shared.SlotStatus.createSlotStatus;
import static com.proofpoint.galaxy.shared.VersionsUtil.GALAXY_SLOTS_VERSION_HEADER;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

public class TestCoordinatorLifecycleResource
{
    private final UriInfo uriInfo = MockUriInfo.from("http://localhost/v1/slot/lifecycle");
    private CoordinatorLifecycleResource resource;

    private Coordinator coordinator;
    private String agentId;
    private int prefixSize;
    private UUID apple1SlotId;
    private UUID apple2SlotId;
    private UUID bananaSlotId;

    @BeforeMethod
    public void setup()
            throws Exception
    {
        NodeInfo nodeInfo = new NodeInfo("testing");

        coordinator = new Coordinator(nodeInfo,
                new CoordinatorConfig().setStatusExpiration(new Duration(1, TimeUnit.DAYS)),
                new MockRemoteAgentFactory(),
                MOCK_REPO,
                new LocalProvisioner(),
                new InMemoryStateManager(),
                new MockServiceInventory());
        resource = new CoordinatorLifecycleResource(coordinator, MOCK_REPO);

        apple1SlotId = UUID.randomUUID();
        SlotStatus appleSlotStatus1 = createSlotStatus(apple1SlotId,
                "apple1",
                URI.create("fake://foo/v1/agent/slot/apple1"),
                URI.create("fake://foo/v1/agent/slot/apple1"),
                "location",
                STOPPED,
                APPLE_ASSIGNMENT,
                "/apple1",
                ImmutableMap.<String, Integer>of());
        apple2SlotId = UUID.randomUUID();
        SlotStatus appleSlotStatus2 = createSlotStatus(apple2SlotId,
                "apple2",
                URI.create("fake://foo/v1/agent/slot/apple1"),
                URI.create("fake://foo/v1/agent/slot/apple1"),
                "location",
                STOPPED,
                APPLE_ASSIGNMENT,
                "/apple2",
                ImmutableMap.<String, Integer>of());
        bananaSlotId = UUID.randomUUID();
        SlotStatus bananaSlotStatus = createSlotStatus(bananaSlotId,
                "banana",
                URI.create("fake://foo/v1/agent/slot/banana"),
                URI.create("fake://foo/v1/agent/slot/banana"),
                "location",
                STOPPED,
                BANANA_ASSIGNMENT,
                "/banana",
                ImmutableMap.<String, Integer>of());

        agentId = UUID.randomUUID().toString();
        AgentStatus agentStatus = new AgentStatus(agentId,
                ONLINE,
                URI.create("fake://foo/"),
                URI.create("fake://foo/"),
                "unknown/location",
                "instance.type",
                ImmutableList.of(appleSlotStatus1, appleSlotStatus2, bananaSlotStatus),
                ImmutableMap.of("cpu", 8, "memory", 1024));

        prefixSize = shortestUniquePrefix(asList(
                appleSlotStatus1.getId().toString(),
                appleSlotStatus2.getId().toString(),
                bananaSlotStatus.getId().toString()),
                MIN_PREFIX_SIZE);

        coordinator.setAgentStatus(agentStatus);
    }

    @Test
    public void testMultipleStateMachineWithFilter()
    {
        UriInfo uriInfo = MockUriInfo.from("http://localhost/v1/slot/lifecycle?binary=*:apple:*");

        // default state is stopped
        assertSlotState(apple1SlotId, STOPPED);
        assertSlotState(apple2SlotId, STOPPED);
        assertSlotState(bananaSlotId, STOPPED);

        // stopped.start => running
        assertOkResponse(resource.setState("running", uriInfo, null), RUNNING, apple1SlotId, apple2SlotId);
        assertSlotState(apple1SlotId, RUNNING);
        assertSlotState(apple2SlotId, RUNNING);
        assertSlotState(bananaSlotId, STOPPED);

        // running.start => running
        assertOkResponse(resource.setState("running", uriInfo, null), RUNNING, apple1SlotId, apple2SlotId);
        assertSlotState(apple1SlotId, RUNNING);
        assertSlotState(apple2SlotId, RUNNING);
        assertSlotState(bananaSlotId, STOPPED);

        // running.stop => stopped
        assertOkResponse(resource.setState("stopped", uriInfo, null), STOPPED, apple1SlotId, apple2SlotId);
        assertSlotState(apple1SlotId, STOPPED);
        assertSlotState(apple2SlotId, STOPPED);
        assertSlotState(bananaSlotId, STOPPED);

        // stopped.stop => stopped
        assertOkResponse(resource.setState("stopped", uriInfo, null), STOPPED, apple1SlotId, apple2SlotId);
        assertSlotState(apple1SlotId, STOPPED);
        assertSlotState(apple2SlotId, STOPPED);
        assertSlotState(bananaSlotId, STOPPED);

        // stopped.restart => running
        assertOkResponse(resource.setState("restarting", uriInfo, null), RUNNING, apple1SlotId, apple2SlotId);
        assertSlotState(apple1SlotId, RUNNING);
        assertSlotState(apple2SlotId, RUNNING);
        assertSlotState(bananaSlotId, STOPPED);

        // running.restart => running
        assertOkResponse(resource.setState("restarting", uriInfo, null), RUNNING, apple1SlotId, apple2SlotId);
        assertSlotState(apple1SlotId, RUNNING);
        assertSlotState(apple2SlotId, RUNNING);
        assertSlotState(bananaSlotId, STOPPED);
    }

    @Test
    public void testSetStateUnknownState()
    {
        Response response = resource.setState("unknown", uriInfo, null);
        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());
        assertNull(response.getEntity());
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testSetStateNullState()
    {
        resource.setState(null, uriInfo, null);
    }

    @Test(expectedExceptions = InvalidSlotFilterException.class)
    public void testSetStateNoFilter()
    {
        resource.setState("running", MockUriInfo.from("http://localhost/v1/slot/lifecycle"), null);
    }

    @Test
    public void testInvalidVersion()
    {
        UriInfo uriInfo = MockUriInfo.from("http://localhost/v1/slot/lifecycle?binary=*:apple:*");
        try {
            resource.setState("running", uriInfo, "invalid-version");
            fail("Expected VersionConflictException");
        }
        catch (VersionConflictException e) {
            assertEquals(e.getName(), GALAXY_SLOTS_VERSION_HEADER);
            assertEquals(e.getVersion(), VersionsUtil.createSlotsVersion(coordinator.getAllSlotsStatus(SlotFilterBuilder.build(uriInfo, false, ImmutableList.<UUID>of()))));
        }
    }

    @Test
    public void testValidVersion()
    {
        UriInfo uriInfo = MockUriInfo.from("http://localhost/v1/slot/lifecycle?binary=*:apple:*");
        String slotsVersion = VersionsUtil.createSlotsVersion(coordinator.getAllSlotsStatus(SlotFilterBuilder.build(uriInfo, false, ImmutableList.<UUID>of())));
        assertOkResponse(resource.setState("running", uriInfo, slotsVersion), RUNNING, apple1SlotId, apple2SlotId);
    }

    private void assertOkResponse(Response response, SlotLifecycleState state, UUID... slotIds)
    {
        assertEquals(response.getStatus(), Response.Status.OK.getStatusCode());

        AgentStatus agentStatus = coordinator.getAgentStatus(agentId);
        Builder<SlotStatusRepresentation> builder = ImmutableList.builder();
        for (UUID slotId : slotIds) {
            SlotStatus slotStatus = agentStatus.getSlotStatus(slotId);
            builder.add(SlotStatusRepresentation.from(slotStatus.changeState(state), prefixSize, MOCK_REPO));
            assertEquals(slotStatus.getAssignment(), APPLE_ASSIGNMENT);
        }
        assertEqualsNoOrder((Collection<?>) response.getEntity(), builder.build());
        assertNull(response.getMetadata().get("Content-Type")); // content type is set by jersey based on @Produces
    }

    private void assertSlotState(UUID slotId, SlotLifecycleState state)
    {
        assertEquals(coordinator.getAgentStatus(agentId).getSlotStatus(slotId).getState(), state);

    }
}
