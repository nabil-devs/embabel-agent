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
package com.embabel.agent.api.annotation.support.nesting

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Tests for StatesAgent demonstrating nested agent state transitions.
 *
 * The StatesAgent workflow:
 * 1. takeUserInput: UserInput -> nested Agent (returns PersonWithReverseTool)
 * 2. turnIntoFrog: PersonWithReverseTool -> Frog (goal achieved)
 *
 * This tests that an @Agent action can return a programmatic agent (created via DSL)
 * which runs as a nested agent, and its output becomes available for subsequent actions.
 */
class StatesAgentTest {

    private val reader = AgentMetadataReader()

    @Test
    fun `StatesAgent can be created from metadata`() {
        val agent = StatesAgent()
        val metadata = reader.createAgentMetadata(agent)
        assertNotNull(metadata)
        assertEquals(2, metadata!!.actions.size, "Should have takeUserInput and turnIntoFrog actions")
    }

    // Note: UTILITY planner with business goals (like @AchievesGoal) doesn't work the same way as GOAP.
    // UTILITY planner with business goals checks if goal is achievable in 1 step, which fails for
    // multi-step workflows like StatesAgent. For UTILITY planning with nested agents,
    // use agents configured with PlannerType.UTILITY that use NIRVANA goal (no @AchievesGoal).

    @Test
    fun `StatesAgent runs with GOAP planner and returns Frog`() {
        val agent = StatesAgent()
        val metadata = reader.createAgentMetadata(agent)
        assertNotNull(metadata)

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.runAgentFrom(
            metadata as CoreAgent,
            ProcessOptions().withPlannerType(PlannerType.GOAP),
            mapOf("it" to UserInput("Bob"))
        )

        assertTrue(
            agentProcess.status == AgentProcessStatusCode.COMPLETED,
            "StatesAgent should complete, but was: ${agentProcess.status}"
        )

        // Check that a Frog was produced
        val result = agentProcess.lastResult()
        assertTrue(
            result is Frog,
            "Expected Frog but got $result (objects: ${agentProcess.objects})"
        )
        assertEquals("Frog version of Bob", (result as Frog).name)
    }

    @Test
    fun `nested agent from takeUserInput produces PersonWithReverseTool`() {
        // Test that the nested agent (returned by takeUserInput) correctly produces
        // a PersonWithReverseTool which is then available for turnIntoFrog

        val agent = StatesAgent()
        val metadata = reader.createAgentMetadata(agent)
        assertNotNull(metadata)

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.runAgentFrom(
            metadata as CoreAgent,
            ProcessOptions().withPlannerType(PlannerType.GOAP),
            mapOf("it" to UserInput("Charlie"))
        )

        // Verify the intermediate PersonWithReverseTool was created
        val personObjects =
            agentProcess.objects.filterIsInstance<com.embabel.agent.api.annotation.support.PersonWithReverseTool>()
        assertTrue(
            personObjects.isNotEmpty(),
            "Should have PersonWithReverseTool on blackboard after nested agent runs"
        )
        assertEquals("Charlie", personObjects.first().name)

        // And the final Frog result
        val result = agentProcess.lastResult()
        assertTrue(result is Frog, "Expected Frog but got $result")
        assertEquals("Frog version of Charlie", (result as Frog).name)
    }
}
