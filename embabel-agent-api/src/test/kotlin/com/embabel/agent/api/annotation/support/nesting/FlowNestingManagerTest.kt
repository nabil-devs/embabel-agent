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
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.subflow.Flow
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
 * Tests for Flow pattern where classes implementing Flow can be
 * returned from actions when using Utility AI planner.
 *
 * Unlike Workflow<O>, Flow doesn't require specifying an output type,
 * which is suitable for Utility AI where goal-oriented planning isn't needed.
 */
class FlowNestingManagerTest {

    private val runner = FlowNestingManager()
    private val reader = AgentMetadataReader()

    @Nested
    inner class FlowDetection {

        @Test
        fun `plain data class is not an Flow`() {
            assertFalse(runner.isFlow(ProcessedData("test")))
        }

        @Test
        fun `class implementing Flow with @Action methods is detected`() {
            val Flow = SimpleFlow("data")
            assertTrue(runner.isFlow(Flow))
        }

        @Test
        fun `Flow without @Action methods is not runnable`() {
            val Flow = EmptyFlow()
            assertFalse(runner.isFlow(Flow))
        }

        @Test
        fun `Workflow is not detected as Flow`() {
            val workflow = SimpleFlowReturning("test")
            assertFalse(runner.isFlow(workflow))
        }

        @Test
        fun `Flow is not detected as Workflow`() {
            val Flow = SimpleFlow("test")
            assertFalse(runner.isFlowReturning(Flow))
        }

        @Test
        fun `nested Flow inside agent is detected`() {
            val processingFlow = UtilityAgentWithNestedTransitions.ProcessingFlow("test")
            assertTrue(
                runner.isFlow(processingFlow),
                "ProcessingFlow should be detected as Flow"
            )
            assertTrue(
                runner.isRunnableNestedAgent(processingFlow, PlannerType.UTILITY),
                "ProcessingFlow should be runnable with UTILITY planner"
            )
        }
    }

    @Nested
    inner class RunnableNestedAgentDetection {

        @Test
        fun `Workflow is runnable with GOAP planner`() {
            val workflow = SimpleFlowReturning("test")
            assertTrue(runner.isRunnableNestedAgent(workflow, PlannerType.GOAP))
        }

        @Test
        fun `Workflow is runnable with UTILITY planner`() {
            val workflow = SimpleFlowReturning("test")
            assertTrue(runner.isRunnableNestedAgent(workflow, PlannerType.UTILITY))
        }

        @Test
        fun `Flow is NOT runnable with GOAP planner`() {
            val Flow = SimpleFlow("test")
            assertFalse(runner.isRunnableNestedAgent(Flow, PlannerType.GOAP))
        }

        @Test
        fun `Flow IS runnable with UTILITY planner`() {
            val Flow = SimpleFlow("test")
            assertTrue(runner.isRunnableNestedAgent(Flow, PlannerType.UTILITY))
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
        fun `can create agent metadata from Flow`() {
            val Flow = SimpleFlow("test")
            val metadata = reader.createAgentMetadata(Flow)
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
        }

        @Test
        fun `Flow should have NIRVANA goal automatically`() {
            // Flow implementations without @Agent get NIRVANA goal automatically
            // since they're meant for Utility AI
            val Flow = SimpleFlow("test")
            val metadata = reader.createAgentMetadata(Flow)
            assertNotNull(metadata)
            // Flow won't have workflow_output goal since it doesn't define an output type
            assertFalse(metadata!!.goals.any { it.name.contains("workflow_output") })
            // But it SHOULD have NIRVANA goal
            assertTrue(metadata.goals.any { it.name == "Nirvana" }, "Flow should have NIRVANA goal")
            // And it should also have the @AchievesGoal goal
            assertTrue(
                metadata.goals.any { it.name.contains("process") },
                "Flow should have @AchievesGoal-created goal: ${metadata.goals.map { it.name }}"
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
        fun `SimpleFlow runs directly with Utility planner`() {
            // First test that SimpleFlow can run as a standalone agent
            val Flow = SimpleFlow("test input")
            val metadata = reader.createAgentMetadata(Flow)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata!!.createAgent(
                    name = metadata.name,
                    provider = "test",
                    description = "Test SimpleFlow"
                ),
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                emptyMap()
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, agentProcess.status,
                "SimpleFlow should complete, but got: ${agentProcess.status}"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("processed: test input", (result as ProcessedData).content)
        }

        @Test
        fun `agent returning Flow executes action and enters nested flow`() {
            val agent = UtilityAgentWithFlow()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("test input"))
            )

            // The outer agent should complete (either by achieving a goal or running out of actions)
            // The important thing is that the Flow was run and produced a result
            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                        agentProcess.status == AgentProcessStatusCode.STUCK,
                "Agent should at least have run, but status was: ${agentProcess.status}"
            )

            // Check that the Flow's result is on the blackboard
            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("processed: test input", (result as ProcessedData).content)
        }

        @Test
        fun `agent with multiple Flow actions works with Utility planner`() {
            // Test that the parent agent's action can return an Flow which runs
            // and produces results that end up on the blackboard
            val agent = UtilityAgentWithFlow()
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

            // Check that the nested Flow result is on the blackboard
            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("processed: multi-test", (result as ProcessedData).content)
        }
    }

    @Nested
    inner class FlowVsFlowReturningComparison {

        @Test
        fun `Flow and Workflow are distinct interfaces`() {
            val Flow = SimpleFlow("test")
            val workflow = SimpleFlowReturning("test")

            assertTrue(Flow is Flow)
            assertFalse(Flow is FlowReturning<*>)

            assertTrue(workflow is FlowReturning<*>)
            assertFalse(workflow is Flow)
        }

        @Test
        fun `class can implement both Flow and Workflow`() {
            val dual = DualInterfaceClass("test")
            assertTrue(dual is Flow)
            assertTrue(dual is FlowReturning<*>)
            assertTrue(runner.isFlowReturning(dual))
            assertTrue(runner.isFlow(dual))
        }
    }

    @Nested
    inner class AgentClassDetection {

        @Test
        fun `plain data class is not an @Agent class`() {
            assertFalse(runner.isAgentClass(ProcessedData("test")))
        }

        @Test
        fun `class with @Agent annotation and @Action methods is detected`() {
            val agent = NestedAgentProcessor()
            assertTrue(runner.isAgentClass(agent))
        }

        @Test
        fun `@Agent class without @Action methods is not runnable`() {
            val agent = EmptyAgentClass()
            assertFalse(runner.isAgentClass(agent))
        }

        @Test
        fun `@Agent class is not detected as Flow or FlowReturning`() {
            val agent = NestedAgentProcessor()
            assertFalse(runner.isFlow(agent))
            assertFalse(runner.isFlowReturning(agent))
        }

        @Test
        fun `Flow is not detected as @Agent class`() {
            val flow = SimpleFlow("test")
            assertFalse(runner.isAgentClass(flow))
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
        fun `@Agent class can chain to Flow`() {
            val agent = AgentThatReturnsFlow()
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

// @Agent class that returns a Flow
@Agent(description = "Agent that returns Flow", planner = PlannerType.UTILITY)
class AgentThatReturnsFlow {

    @Action
    fun delegateToFlow(it: UserInput): SimpleFlow {
        return SimpleFlow(it.content)
    }
}

// Empty Flow - no @Action methods, should not be runnable
class EmptyFlow : Flow

// Simple Flow with one action
class SimpleFlow(val data: String) : Flow {

    @Action
    @AchievesGoal(description = "Process data")
    fun process(): ProcessedData = ProcessedData("processed: $data")
}

// Flow for a second phase
class FinalizingFlow(val data: ProcessedData) : Flow {

    @Action
    @AchievesGoal(description = "Finalize data")
    fun finalize(): ProcessedData = ProcessedData("${data.content} - finalized")
}

// Class implementing both Flow and Workflow
class DualInterfaceClass(val data: String) : Flow, FlowReturning<ProcessedData> {
    override val outputType = ProcessedData::class.java

    @Action
    @AchievesGoal(description = "Process dual")
    fun process(): ProcessedData = ProcessedData("dual: $data")
}

// Utility AI agent that returns an Flow
@Agent(description = "Utility agent with Flow", planner = PlannerType.UTILITY)
class UtilityAgentWithFlow {

    @Action
    fun enterFlow(it: UserInput): SimpleFlow {
        return SimpleFlow(it.content)
    }
}

// Utility AI agent with nested Flow transitions
@Agent(description = "Utility agent with nested transitions", planner = PlannerType.UTILITY)
class UtilityAgentWithNestedTransitions {

    @Action
    fun startProcessing(it: UserInput): ProcessingFlow {
        return ProcessingFlow(it.content)
    }

    class ProcessingFlow(val content: String) : Flow {

        @Action
        @AchievesGoal(description = "Process and pass to finalizing")
        fun process(): FinalizingFlow {
            val data = ProcessedData("processing: $content")
            return FinalizingFlow(data)
        }
    }
}
