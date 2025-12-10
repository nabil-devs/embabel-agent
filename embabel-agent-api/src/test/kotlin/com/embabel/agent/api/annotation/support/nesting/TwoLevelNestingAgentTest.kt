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
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Tests for TwoLevelNestingAgent demonstrating two levels of nested subflows.
 *
 * The TwoLevelNestingAgent workflow:
 * 1. start: UserInput -> Level1 (nested @Subflow)
 * 2. Level1.toL2: -> Level2 (nested @Subflow)
 * 3. Level2.toFrog: -> Frog
 * 4. end: Frog -> String (goal achieved)
 *
 * This tests that nested @Subflow classes can themselves return other @Subflow classes,
 * creating a chain of nested agent executions.
 */
class TwoLevelNestingAgentTest {

    private val reader = AgentMetadataReader()

    @Test
    fun `TwoLevelNestingAgent can be created from metadata`() {
        val agent = TwoLevelNestingAgent()
        val metadata = reader.createAgentMetadata(agent)
        assertNotNull(metadata)
        assertEquals(2, metadata!!.actions.size, "Should have start and end actions at top level")
    }

    @Test
    fun `TwoLevelNestingAgent runs with GOAP planner and produces final result`() {
        val agent = TwoLevelNestingAgent()
        val metadata = reader.createAgentMetadata(agent)
        assertNotNull(metadata)

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.runAgentFrom(
            metadata as CoreAgent,
            ProcessOptions().withPlannerType(PlannerType.GOAP),
            mapOf("it" to UserInput("TestInput"))
        )

        assertEquals(
            AgentProcessStatusCode.COMPLETED,
            agentProcess.status,
            "TwoLevelNestingAgent should complete, but was: ${agentProcess.status}"
        )

        // Check that the final String result was produced
        val result = agentProcess.lastResult()
        assertTrue(
            result is String,
            "Expected String but got $result (objects: ${agentProcess.objects})"
        )
        assertEquals("Received frog named: LevelOne processing of: TestInput", result)
    }

    @Test
    fun `nested Level1 produces Level2`() {
        val agent = TwoLevelNestingAgent()
        val metadata = reader.createAgentMetadata(agent)
        assertNotNull(metadata)

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.runAgentFrom(
            metadata as CoreAgent,
            ProcessOptions().withPlannerType(PlannerType.GOAP),
            mapOf("it" to UserInput("Charlie"))
        )

        assertEquals(
            AgentProcessStatusCode.COMPLETED,
            agentProcess.status,
            "TwoLevelNestingAgent should complete"
        )

        // Verify the final result
        val result = agentProcess.lastResult()
        assertTrue(result is String, "Expected String but got $result")
        assertEquals("Received frog named: LevelOne processing of: Charlie", result)
    }
}
