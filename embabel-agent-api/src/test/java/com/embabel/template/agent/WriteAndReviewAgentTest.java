/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.template.agent;

import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.AgentScope;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WriteAndReviewAgent demonstrating nested @Subflow in Java.
 *
 * The WriteAndReviewAgent workflow:
 * 1. craftStory: UserInput -> PolishStory (@Subflow)
 * 2. PolishStory.polish: -> Story
 * 3. reviewStory: UserInput + Story -> ReviewedStory (goal achieved)
 */
public class WriteAndReviewAgentTest {

    private final AgentMetadataReader reader = new AgentMetadataReader();

    @Test
    public void writeAndReviewAgentCanBeCreatedFromMetadata() {
        var agent = new WriteAndReviewAgent(100, 100);
        var metadata = reader.createAgentMetadata(agent);
        assertNotNull(metadata, "Metadata should not be null");
        assertTrue(metadata instanceof Agent, "Metadata should be an Agent");
        assertEquals(2, metadata.getActions().size(), "Should have craftStory and reviewStory actions at top level");

    }

    @Test
    public void writeAndReviewAgentRunsWithGoapPlannerAndProducesReviewedStory() {
        var agent = new WriteAndReviewAgent(100, 100);
        var metadata = reader.createAgentMetadata(agent);
        assertNotNull(metadata);

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.runAgentFrom(
                (Agent) metadata,
                new ProcessOptions().withPlannerType(PlannerType.GOAP),
                Map.of("it", new UserInput("Tell me a story about a robot"))
        );

        assertEquals(
                AgentProcessStatusCode.COMPLETED,
                agentProcess.getStatus(),
                "WriteAndReviewAgent should complete, but was: " + agentProcess.getStatus()
        );

        // Check that the final ReviewedStory was produced
        var result = agentProcess.lastResult();
        assertInstanceOf(
                WriteAndReviewAgent.ReviewedStory.class,
                result,
                "Expected ReviewedStory but got " + result
        );
    }

    @Test
    public void nestedPolishStoryProducesStory() {
        var agent = new WriteAndReviewAgent(100, 100);
        var metadata = reader.createAgentMetadata(agent);
        assertNotNull(metadata);

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.runAgentFrom(
                (Agent) metadata,
                new ProcessOptions().withPlannerType(PlannerType.GOAP),
                Map.of("it", new UserInput("A tale of adventure"))
        );

        assertEquals(
                AgentProcessStatusCode.COMPLETED,
                agentProcess.getStatus(),
                "WriteAndReviewAgent should complete"
        );

        // Verify the intermediate Story was created (should be on blackboard)
        var storyObjects = agentProcess.getObjects().stream()
                .filter(obj -> obj instanceof WriteAndReviewAgent.Story)
                .toList();
        assertFalse(
                storyObjects.isEmpty(),
                "Should have Story on blackboard after nested subflow runs"
        );

        // And the final ReviewedStory result
        var result = agentProcess.lastResult();
        assertInstanceOf(WriteAndReviewAgent.ReviewedStory.class, result, "Expected ReviewedStory but got " + result);
    }
}
