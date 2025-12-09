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
import com.embabel.agent.api.common.workflow.WorkflowRunner
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
 * Tests for ActionClass pattern where classes implementing ActionClass can be
 * returned from actions when using Utility AI planner.
 *
 * Unlike Workflow<O>, ActionClass doesn't require specifying an output type,
 * which is suitable for Utility AI where goal-oriented planning isn't needed.
 */
class FlowNestingTest {

    private val runner = WorkflowRunner()
    private val reader = AgentMetadataReader()

    @Nested
    inner class FlowDetection {

        @Test
        fun `plain data class is not an ActionClass`() {
            assertFalse(runner.isFlow(ProcessedData("test")))
        }

        @Test
        fun `class implementing ActionClass with @Action methods is detected`() {
            val actionClass = SimpleFlow("data")
            assertTrue(runner.isFlow(actionClass))
        }

        @Test
        fun `ActionClass without @Action methods is not runnable`() {
            val actionClass = EmptyFlow()
            assertFalse(runner.isFlow(actionClass))
        }

        @Test
        fun `Workflow is not detected as ActionClass`() {
            val workflow = SimpleFlowReturning("test")
            assertFalse(runner.isFlow(workflow))
        }

        @Test
        fun `ActionClass is not detected as Workflow`() {
            val actionClass = SimpleFlow("test")
            assertFalse(runner.isFlowReturning(actionClass))
        }

        @Test
        fun `nested ActionClass inside agent is detected`() {
            val processingActionClass = UtilityAgentWithNestedTransitions.ProcessingFlow("test")
            assertTrue(
                runner.isFlow(processingActionClass),
                "ProcessingActionClass should be detected as ActionClass"
            )
            assertTrue(
                runner.isRunnableNestedAgent(processingActionClass, PlannerType.UTILITY),
                "ProcessingActionClass should be runnable with UTILITY planner"
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
        fun `ActionClass is NOT runnable with GOAP planner`() {
            val actionClass = SimpleFlow("test")
            assertFalse(runner.isRunnableNestedAgent(actionClass, PlannerType.GOAP))
        }

        @Test
        fun `ActionClass IS runnable with UTILITY planner`() {
            val actionClass = SimpleFlow("test")
            assertTrue(runner.isRunnableNestedAgent(actionClass, PlannerType.UTILITY))
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
        fun `can create agent metadata from ActionClass`() {
            val actionClass = SimpleFlow("test")
            val metadata = reader.createAgentMetadata(actionClass)
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
        }

        @Test
        fun `ActionClass should have NIRVANA goal automatically`() {
            // ActionClass implementations without @Agent get NIRVANA goal automatically
            // since they're meant for Utility AI
            val actionClass = SimpleFlow("test")
            val metadata = reader.createAgentMetadata(actionClass)
            assertNotNull(metadata)
            // ActionClass won't have workflow_output goal since it doesn't define an output type
            assertFalse(metadata!!.goals.any { it.name.contains("workflow_output") })
            // But it SHOULD have NIRVANA goal
            assertTrue(metadata.goals.any { it.name == "Nirvana" }, "ActionClass should have NIRVANA goal")
            // And it should also have the @AchievesGoal goal
            assertTrue(
                metadata.goals.any { it.name.contains("process") },
                "ActionClass should have @AchievesGoal-created goal: ${metadata.goals.map { it.name }}"
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
        fun `SimpleActionClass runs directly with Utility planner`() {
            // First test that SimpleActionClass can run as a standalone agent
            val actionClass = SimpleFlow("test input")
            val metadata = reader.createAgentMetadata(actionClass)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata!!.createAgent(
                    name = metadata.name,
                    provider = "test",
                    description = "Test SimpleActionClass"
                ),
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                emptyMap()
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, agentProcess.status,
                "SimpleActionClass should complete, but got: ${agentProcess.status}"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("processed: test input", (result as ProcessedData).content)
        }

        @Test
        fun `agent returning ActionClass executes action and enters nested flow`() {
            val agent = UtilityAgentWithActionClass()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("test input"))
            )

            // The outer agent should complete (either by achieving a goal or running out of actions)
            // The important thing is that the ActionClass was run and produced a result
            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                        agentProcess.status == AgentProcessStatusCode.STUCK,
                "Agent should at least have run, but status was: ${agentProcess.status}"
            )

            // Check that the ActionClass's result is on the blackboard
            val result = agentProcess.lastResult()
            assertTrue(
                result is ProcessedData,
                "Expected ProcessedData but got $result (objects: ${agentProcess.objects})"
            )
            assertEquals("processed: test input", (result as ProcessedData).content)
        }

        @Test
        fun `agent with multiple ActionClass actions works with Utility planner`() {
            // Test that the parent agent's action can return an ActionClass which runs
            // and produces results that end up on the blackboard
            val agent = UtilityAgentWithActionClass()
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

            // Check that the nested ActionClass result is on the blackboard
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
        fun `ActionClass and Workflow are distinct interfaces`() {
            val actionClass = SimpleFlow("test")
            val workflow = SimpleFlowReturning("test")

            assertTrue(actionClass is Flow)
            assertFalse(actionClass is FlowReturning<*>)

            assertTrue(workflow is FlowReturning<*>)
            assertFalse(workflow is Flow)
        }

        @Test
        fun `class can implement both ActionClass and Workflow`() {
            val dual = DualInterfaceClass("test")
            assertTrue(dual is Flow)
            assertTrue(dual is FlowReturning<*>)
            assertTrue(runner.isFlowReturning(dual))
            assertTrue(runner.isFlow(dual))
        }
    }
}

// Empty ActionClass - no @Action methods, should not be runnable
class EmptyFlow : Flow

// Simple ActionClass with one action
class SimpleFlow(val data: String) : Flow {

    @Action
    @AchievesGoal(description = "Process data")
    fun process(): ProcessedData = ProcessedData("processed: $data")
}

// ActionClass for a second phase
class FinalizingFlow(val data: ProcessedData) : Flow {

    @Action
    @AchievesGoal(description = "Finalize data")
    fun finalize(): ProcessedData = ProcessedData("${data.content} - finalized")
}

// Class implementing both ActionClass and Workflow
class DualInterfaceClass(val data: String) : Flow, FlowReturning<ProcessedData> {
    override val outputType = ProcessedData::class.java

    @Action
    @AchievesGoal(description = "Process dual")
    fun process(): ProcessedData = ProcessedData("dual: $data")
}

// Utility AI agent that returns an ActionClass
@Agent(description = "Utility agent with ActionClass", planner = PlannerType.UTILITY)
class UtilityAgentWithActionClass {

    @Action
    fun enterActionClass(it: UserInput): SimpleFlow {
        return SimpleFlow(it.content)
    }
}

// Utility AI agent with nested ActionClass transitions
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
