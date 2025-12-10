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

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Subflow
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.subflow.FlowReturning
import com.embabel.agent.api.common.support.FlowNestingManager
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

// Test domain types
data class ProcessedData(val content: String)

/**
 * Tests for @Subflow pattern where classes annotated with @Subflow can be
 * returned from actions when using Utility AI planner.
 *
 * Unlike FlowReturning<O>, @Subflow doesn't require specifying an output type,
 * which is suitable for Utility AI where goal-oriented planning isn't needed.
 */
class FlowNestingManagerTest {

    private val runner = FlowNestingManager()
    private val reader = AgentMetadataReader()

    @Nested
    inner class SubflowDetection {

        @Test
        fun `plain data class is not a @Subflow`() {
            assertFalse(runner.isSubflow(ProcessedData("test")))
        }

        @Test
        fun `class with @Subflow annotation and @Action methods is detected`() {
            val scope = SimpleSubflow("data")
            assertTrue(runner.isSubflow(scope))
        }

        @Test
        fun `@Subflow without @Action methods is not runnable`() {
            val scope = EmptySubflow()
            assertFalse(runner.isSubflow(scope))
        }

        @Test
        fun `FlowReturning is not detected as @Subflow only`() {
            val workflow = SimpleFlowReturning("test")
            // FlowReturning is detected separately, not through @Subflow
            assertFalse(runner.isSubflow(workflow))
            assertTrue(runner.isFlowReturning(workflow))
        }

        @Test
        fun `@Subflow is not detected as FlowReturning`() {
            val scope = SimpleSubflow("test")
            assertFalse(runner.isFlowReturning(scope))
        }

        @Test
        fun `nested @Subflow inside agent is detected`() {
            val processingScope = UtilityAgentWithNestedTransitions.ProcessingSubflow("test")
            assertTrue(
                runner.isSubflow(processingScope),
                "ProcessingSubflow should be detected as @Subflow"
            )
            assertTrue(
                runner.isRunnableNestedAgent(processingScope, PlannerType.UTILITY),
                "ProcessingSubflow should be runnable with UTILITY planner"
            )
        }

        @Test
        fun `@Agent class is detected as @Subflow (via meta-annotation)`() {
            val agent = NestedAgentProcessor()
            assertTrue(runner.isSubflow(agent), "@Agent should be detected as @Subflow")
        }
    }

    @Nested
    inner class RunnableNestedAgentDetection {

        @Test
        fun `FlowReturning is runnable with GOAP planner`() {
            val workflow = SimpleFlowReturning("test")
            assertTrue(runner.isRunnableNestedAgent(workflow, PlannerType.GOAP))
        }

        @Test
        fun `FlowReturning is runnable with UTILITY planner`() {
            val workflow = SimpleFlowReturning("test")
            assertTrue(runner.isRunnableNestedAgent(workflow, PlannerType.UTILITY))
        }

        @Test
        fun `@Subflow is NOT runnable with GOAP planner`() {
            val scope = SimpleSubflow("test")
            assertFalse(runner.isRunnableNestedAgent(scope, PlannerType.GOAP))
        }

        @Test
        fun `@Subflow IS runnable with UTILITY planner`() {
            val scope = SimpleSubflow("test")
            assertTrue(runner.isRunnableNestedAgent(scope, PlannerType.UTILITY))
        }

        @Test
        fun `plain data class is not runnable with any planner`() {
            val data = ProcessedData("test")
            assertFalse(runner.isRunnableNestedAgent(data, PlannerType.GOAP))
            assertFalse(runner.isRunnableNestedAgent(data, PlannerType.UTILITY))
        }
    }

    @Nested
    inner class AgentMetadataCreation {

        @Test
        fun `can create agent metadata from @Subflow`() {
            val scope = SimpleSubflow("test")
            val metadata = reader.createAgentMetadata(scope)
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
        }

        @Test
        fun `@Subflow should have NIRVANA goal automatically`() {
            // @Subflow classes without @Agent get NIRVANA goal automatically
            // since they're meant for Utility AI
            val scope = SimpleSubflow("test")
            val metadata = reader.createAgentMetadata(scope)
            assertNotNull(metadata)
            // @Subflow won't have workflow_output goal since it doesn't define an output type
            assertFalse(metadata!!.goals.any { it.name.contains("workflow_output") })
            // But it SHOULD have NIRVANA goal
            assertTrue(metadata.goals.any { it.name == "Nirvana" }, "@Subflow should have NIRVANA goal")
            // And it should also have the @AchievesGoal goal
            assertTrue(
                metadata.goals.any { it.name.contains("process") },
                "@Subflow should have @AchievesGoal-created goal: ${metadata.goals.map { it.name }}"
            )
        }

        @Test
        fun `cannot create agent metadata from plain data class`() {
            val metadata = reader.createAgentMetadata(ProcessedData("test"))
            assertNull(metadata)
        }
    }

    @Nested
    inner class UtilityAgentExecution {

        @Test
        fun `SimpleSubflow runs directly with Utility planner`() {
            // First test that SimpleSubflow can run as a standalone agent
            val scope = SimpleSubflow("test input")
            val metadata = reader.createAgentMetadata(scope)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata!!.createAgent(
                    name = metadata.name,
                    provider = "test",
                    description = "Test SimpleSubflow"
                ),
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                emptyMap()
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, agentProcess.status,
                "SimpleSubflow should complete, but got: ${agentProcess.status}"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("processed: test input", (result as ProcessedData).content)
        }

        @Test
        fun `agent returning @Subflow executes action and enters nested scope`() {
            val agent = UtilityAgentWithSubflow()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("test input"))
            )

            // The outer agent should complete (either by achieving a goal or running out of actions)
            // The important thing is that the @Subflow was run and produced a result
            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                    agentProcess.status == AgentProcessStatusCode.STUCK,
                "Agent should at least have run, but status was: ${agentProcess.status}"
            )

            // Check that the @Subflow's result is on the blackboard
            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("processed: test input", (result as ProcessedData).content)
        }

        @Test
        fun `agent with multiple @Subflow actions works with Utility planner`() {
            // Test that the parent agent's action can return a @Subflow which runs
            // and produces results that end up on the blackboard
            val agent = UtilityAgentWithSubflow()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("multi-test"))
            )

            // The outer agent may be STUCK (no more actions) or COMPLETED
            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                    agentProcess.status == AgentProcessStatusCode.STUCK,
                "Agent should at least have run, but status was: ${agentProcess.status}"
            )

            // Check that the nested @Subflow result is on the blackboard
            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("processed: multi-test", (result as ProcessedData).content)
        }
    }

    @Nested
    inner class SubflowVsFlowReturningComparison {

        @Test
        fun `@Subflow and FlowReturning are distinct`() {
            val scope = SimpleSubflow("test")
            val workflow = SimpleFlowReturning("test")

            assertTrue(runner.isSubflow(scope))
            assertFalse(scope is FlowReturning<*>)

            assertTrue(workflow is FlowReturning<*>)
            assertFalse(runner.isSubflow(workflow))
        }
    }

    @Nested
    inner class AgentClassDetection {

        @Test
        fun `plain data class is not an @Agent class`() {
            assertFalse(runner.isSubflow(ProcessedData("test")))
        }

        @Test
        fun `class with @Agent annotation and @Action methods is detected as @Subflow`() {
            val agent = NestedAgentProcessor()
            assertTrue(runner.isSubflow(agent))
        }

        @Test
        fun `@Agent class without @Action methods is not runnable`() {
            val agent = EmptyAgentClass()
            assertFalse(runner.isSubflow(agent))
        }

        @Test
        fun `@Agent class is not detected as FlowReturning`() {
            val agent = NestedAgentProcessor()
            assertFalse(runner.isFlowReturning(agent))
        }
    }

    @Nested
    inner class AgentClassRunnableDetection {

        @Test
        fun `@Agent class is NOT runnable with GOAP planner`() {
            val agent = NestedAgentProcessor()
            assertFalse(runner.isRunnableNestedAgent(agent, PlannerType.GOAP))
        }

        @Test
        fun `@Agent class IS runnable with UTILITY planner`() {
            val agent = NestedAgentProcessor()
            assertTrue(runner.isRunnableNestedAgent(agent, PlannerType.UTILITY))
        }
    }

    @Nested
    inner class AgentClassExecution {

        @Test
        fun `agent returning @Agent class executes nested agent with Utility planner`() {
            val parentAgent = UtilityAgentWithNestedAgent()
            val metadata = reader.createAgentMetadata(parentAgent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("agent-test"))
            )

            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                    agentProcess.status == AgentProcessStatusCode.STUCK,
                "Agent should at least have run, but status was: ${agentProcess.status}"
            )

            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("agent processed: agent-test", (result as ProcessedData).content)
        }

        @Test
        fun `@Agent class can chain to @Subflow`() {
            val agent = AgentThatReturnsSubflow()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("chain-test"))
            )

            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                    agentProcess.status == AgentProcessStatusCode.STUCK,
                "Agent should at least have run, but status was: ${agentProcess.status}"
            )

            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("processed: chain-test", (result as ProcessedData).content)
        }
    }
}

// Empty @Agent class - no @Action methods, should not be runnable
@Agent(description = "Empty agent")
class EmptyAgentClass

// Simple @Agent class with one action - uses UserInput from blackboard instead of constructor param
@Agent(description = "Nested agent processor")
class NestedAgentProcessor {

    @Action
    @AchievesGoal(description = "Process data via agent")
    fun process(it: UserInput): ProcessedData = ProcessedData("agent processed: ${it.content}")
}

// Utility AI agent that returns an @Agent class
@Agent(description = "Utility agent with nested agent", planner = PlannerType.UTILITY)
class UtilityAgentWithNestedAgent {

    @Action
    fun delegateToAgent(it: UserInput): NestedAgentProcessor {
        return NestedAgentProcessor()
    }
}

// @Agent class that returns a @Subflow
@Agent(description = "Agent that returns @Subflow", planner = PlannerType.UTILITY)
class AgentThatReturnsSubflow {

    @Action
    fun delegateToScope(it: UserInput): SimpleSubflow {
        return SimpleSubflow(it.content)
    }
}

// Empty @Subflow - no @Action methods, should not be runnable
@Subflow
class EmptySubflow

// Simple @Subflow with one action
@Subflow
class SimpleSubflow(val data: String) {

    @Action
    @AchievesGoal(description = "Process data")
    fun process(): ProcessedData = ProcessedData("processed: $data")
}

// @Subflow for a second phase
@Subflow
class FinalizingSubflow(val data: ProcessedData) {

    @Action
    @AchievesGoal(description = "Finalize data")
    fun finalize(): ProcessedData = ProcessedData("${data.content} - finalized")
}

// Utility AI agent that returns a @Subflow
@Agent(description = "Utility agent with @Subflow", planner = PlannerType.UTILITY)
class UtilityAgentWithSubflow {

    @Action
    fun enterScope(it: UserInput): SimpleSubflow {
        return SimpleSubflow(it.content)
    }
}

// Utility AI agent with nested @Subflow transitions
@Agent(description = "Utility agent with nested transitions", planner = PlannerType.UTILITY)
class UtilityAgentWithNestedTransitions {

    @Action
    fun startProcessing(it: UserInput): ProcessingSubflow {
        return ProcessingSubflow(it.content)
    }

    @Subflow
    class ProcessingSubflow(val content: String) {

        @Action
        @AchievesGoal(description = "Process and pass to finalizing")
        fun process(): FinalizingSubflow {
            val data = ProcessedData("processing: $content")
            return FinalizingSubflow(data)
        }
    }
}
