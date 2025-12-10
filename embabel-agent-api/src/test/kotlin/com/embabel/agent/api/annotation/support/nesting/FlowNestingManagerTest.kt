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
import com.embabel.agent.api.common.support.SupplierAction
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.Goal
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
 * Tests for nested agent patterns where @Agentic classes (@Subflow, @Agent) can be
 * returned from actions.
 *
 * For GOAP planning: requires exactly one business goal (excluding NIRVANA) to determine output type.
 * For Utility AI planning: any @Agentic class with actions can be run.
 *
 * FlowReturning is still supported for backward compatibility.
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
        fun `@Subflow with single goal IS runnable with GOAP planner`() {
            // SimpleSubflow has exactly one @AchievesGoal, so it should be runnable with GOAP
            val scope = SimpleSubflow("test")
            assertTrue(
                runner.isRunnableNestedAgent(scope, PlannerType.GOAP),
                "@Subflow with single goal should be runnable with GOAP"
            )
        }

        @Test
        fun `@Subflow IS runnable with UTILITY planner`() {
            val scope = SimpleSubflow("test")
            assertTrue(runner.isRunnableNestedAgent(scope, PlannerType.UTILITY))
        }

        @Test
        fun `@Subflow with multiple goals is NOT runnable with GOAP planner`() {
            val scope = MultiGoalSubflow("test")
            assertFalse(
                runner.isRunnableNestedAgent(scope, PlannerType.GOAP),
                "@Subflow with multiple goals should NOT be runnable with GOAP"
            )
            // But should still be runnable with Utility
            assertTrue(
                runner.isRunnableNestedAgent(scope, PlannerType.UTILITY),
                "@Subflow with multiple goals should be runnable with UTILITY"
            )
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
        fun `@Agent class with single goal IS runnable with GOAP planner`() {
            // NestedAgentProcessor has exactly one @AchievesGoal, so it should be runnable with GOAP
            val agent = NestedAgentProcessor()
            assertTrue(
                runner.isRunnableNestedAgent(agent, PlannerType.GOAP),
                "@Agent with single goal should be runnable with GOAP"
            )
        }

        @Test
        fun `@Agent class IS runnable with UTILITY planner`() {
            val agent = NestedAgentProcessor()
            assertTrue(runner.isRunnableNestedAgent(agent, PlannerType.UTILITY))
        }

        @Test
        fun `@Agent class with multiple goals is NOT runnable with GOAP planner`() {
            val agent = MultiGoalAgent()
            assertFalse(
                runner.isRunnableNestedAgent(agent, PlannerType.GOAP),
                "@Agent with multiple goals should NOT be runnable with GOAP"
            )
            // But should still be runnable with Utility
            assertTrue(
                runner.isRunnableNestedAgent(agent, PlannerType.UTILITY),
                "@Agent with multiple goals should be runnable with UTILITY"
            )
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

    @Nested
    inner class GoapNestedExecution {

        @Test
        fun `GOAP agent returning single-goal @Subflow executes nested flow`() {
            val agent = GoapAgentWithSubflow()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions().withPlannerType(PlannerType.GOAP),
                mapOf("it" to UserInput("goap-test"))
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
            assertEquals("processed: goap-test", (result as ProcessedData).content)
        }
    }

    /**
     * Tests for programmatically created agents (via SimpleAgentBuilder or direct Agent construction)
     * being returned from actions and run as nested agents.
     *
     * These tests verify that the same rules apply to programmatic agents as to @Agent/@Subflow classes:
     * - GOAP: requires exactly one goal
     * - Utility: works with any number of goals
     *
     * core.Agent and AgentScope implement the AgentScope interface which has goals and actions directly.
     * FlowNestingManager detects these via isAgentScope() check without needing @Agentic annotation.
     */
    @Nested
    inner class ProgrammaticAgentDetection {

        @Test
        fun `core Agent is not detected as FlowReturning`() {
            val programmaticAgent = createSingleGoalAgent()
            assertFalse(
                runner.isFlowReturning(programmaticAgent),
                "Core Agent does not implement FlowReturning"
            )
        }

        @Test
        fun `core Agent implements AgentScope which has goals and actions`() {
            val programmaticAgent = createSingleGoalAgent()
            // core.Agent implements AgentScope - it has goals and actions directly
            assertTrue(programmaticAgent is AgentScope, "Core Agent should implement AgentScope")
            assertEquals(1, programmaticAgent.goals.size, "Should have 1 goal")
            assertEquals(1, programmaticAgent.actions.size, "Should have 1 action")
        }

        @Test
        fun `AgentScope has goals and actions directly accessible`() {
            val agentScope = createSingleGoalAgentScope()
            assertTrue(agentScope is AgentScope, "Should be an AgentScope")
            assertEquals(1, agentScope.goals.size, "Should have 1 goal")
            assertEquals(1, agentScope.actions.size, "Should have 1 action")
        }

        @Test
        fun `core Agent with single goal is runnable with GOAP`() {
            val programmaticAgent = createSingleGoalAgent()
            val isRunnable = runner.isRunnableNestedAgent(programmaticAgent, PlannerType.GOAP)
            assertTrue(isRunnable, "AgentScope with single goal should be runnable with GOAP")
        }

        @Test
        fun `core Agent with single goal is runnable with UTILITY`() {
            val programmaticAgent = createSingleGoalAgent()
            val isRunnable = runner.isRunnableNestedAgent(programmaticAgent, PlannerType.UTILITY)
            assertTrue(isRunnable, "AgentScope should be runnable with UTILITY")
        }

        @Test
        fun `AgentScope with single goal is runnable with GOAP`() {
            val agentScope = createSingleGoalAgentScope()
            val isRunnable = runner.isRunnableNestedAgent(agentScope, PlannerType.GOAP)
            assertTrue(isRunnable, "AgentScope with single goal should be runnable with GOAP")
        }

        @Test
        fun `AgentScope with single goal is runnable with UTILITY`() {
            val agentScope = createSingleGoalAgentScope()
            val isRunnable = runner.isRunnableNestedAgent(agentScope, PlannerType.UTILITY)
            assertTrue(isRunnable, "AgentScope should be runnable with UTILITY")
        }

        @Test
        fun `core Agent with multiple goals should NOT be runnable with GOAP`() {
            val programmaticAgent = createMultiGoalAgent()
            val isRunnable = runner.isRunnableNestedAgent(programmaticAgent, PlannerType.GOAP)
            assertFalse(isRunnable, "Multiple goals should not be runnable with GOAP")
        }

        @Test
        fun `core Agent with multiple goals is runnable with UTILITY`() {
            val programmaticAgent = createMultiGoalAgent()
            val isRunnable = runner.isRunnableNestedAgent(programmaticAgent, PlannerType.UTILITY)
            assertTrue(isRunnable, "AgentScope with multiple goals should be runnable with UTILITY")
        }
    }

    /**
     * Tests for ProgrammaticWriteAndReviewAgent which uses high-level DSL builders
     * (SimpleAgentBuilder, RepeatUntilBuilder) to create programmatic agents.
     */
    @Nested
    inner class ProgrammaticWriteAndReviewAgentTests {

        @Test
        fun `SimpleProcessor creates valid single-goal AgentScope using SimpleAgentBuilder`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessor()
            assertTrue(processor is AgentScope)
            assertEquals(1, processor.goals.size, "Should have single goal for GOAP compatibility")
            assertEquals(1, processor.actions.size)
        }

        @Test
        fun `SimpleProcessor is detected as AgentScope`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessor()
            assertTrue(runner.isAgentScope(processor), "Should be detected as AgentScope")
        }

        @Test
        fun `SimpleProcessor with single goal is runnable with GOAP`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessor()
            assertTrue(
                runner.isRunnableNestedAgent(processor, PlannerType.GOAP),
                "Programmatic agent with single goal should be runnable with GOAP"
            )
        }

        @Test
        fun `SimpleProcessor is runnable with UTILITY`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessor()
            assertTrue(
                runner.isRunnableNestedAgent(processor, PlannerType.UTILITY),
                "Programmatic agent should be runnable with UTILITY"
            )
        }

        @Test
        fun `SimpleProcessor runs with GOAP planner`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessor()
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val agentProcess = ap.runAgentFrom(
                processor.createAgent(
                    name = processor.name,
                    provider = "test",
                    description = processor.description,
                ),
                ProcessOptions().withPlannerType(PlannerType.GOAP),
                mapOf("it" to UserInput("programmatic-goap-test"))
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, agentProcess.status,
                "SimpleProcessor should complete with GOAP planner"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("programmatically processed: programmatic-goap-test", (result as ProcessedData).content)
        }

        @Test
        fun `SimpleProcessor runs with UTILITY planner`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessor()
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val agentProcess = ap.runAgentFrom(
                processor.createAgent(
                    name = processor.name,
                    provider = "test",
                    description = processor.description,
                ),
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("programmatic-utility-test"))
            )

            // UTILITY planner may COMPLETE or TERMINATE (when business goal is satisfied)
            assertTrue(
                agentProcess.status == AgentProcessStatusCode.COMPLETED ||
                    agentProcess.status == AgentProcessStatusCode.TERMINATED,
                "SimpleProcessor should complete or terminate with UTILITY planner, but was: ${agentProcess.status}"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("programmatically processed: programmatic-utility-test", (result as ProcessedData).content)
        }

        @Test
        fun `StoryWriter creates valid AgentScope using SimpleAgentBuilder`() {
            val writer = ProgrammaticWriteAndReviewAgent.createStoryWriter()
            assertTrue(writer is AgentScope)
            assertEquals(1, writer.goals.size, "Should have single goal")
            assertEquals(1, writer.actions.size)
        }

        @Test
        fun `StoryWriter is runnable with GOAP`() {
            val writer = ProgrammaticWriteAndReviewAgent.createStoryWriter()
            assertTrue(
                runner.isRunnableNestedAgent(writer, PlannerType.GOAP),
                "Story writer should be runnable with GOAP"
            )
        }

        @Test
        fun `ReviewingAgent creates valid AgentScope using RepeatUntilBuilder`() {
            val story = Story("Test story")
            val reviewing = ProgrammaticWriteAndReviewAgent.createReviewingAgent(story)
            assertTrue(reviewing is AgentScope)
            assertTrue(reviewing.goals.isNotEmpty(), "Should have goals")
            assertTrue(reviewing.actions.isNotEmpty(), "Should have actions")
        }

        @Test
        fun `ReviewingAgent is runnable with UTILITY`() {
            val story = Story("Test story")
            val reviewing = ProgrammaticWriteAndReviewAgent.createReviewingAgent(story)
            assertTrue(
                runner.isRunnableNestedAgent(reviewing, PlannerType.UTILITY),
                "Reviewing agent should be runnable with UTILITY"
            )
        }

        @Test
        fun `WriteAndReviewWorkflow creates valid AgentScope using RepeatUntilBuilder`() {
            val workflow = ProgrammaticWriteAndReviewAgent.createWriteAndReviewWorkflow()
            assertTrue(workflow is AgentScope)
            assertTrue(workflow.goals.isNotEmpty(), "Should have goals")
            assertTrue(workflow.actions.isNotEmpty(), "Should have actions")
        }

        @Test
        fun `WriteAndReviewWorkflow is runnable with UTILITY`() {
            val workflow = ProgrammaticWriteAndReviewAgent.createWriteAndReviewWorkflow()
            assertTrue(
                runner.isRunnableNestedAgent(workflow, PlannerType.UTILITY),
                "Write and review workflow should be runnable with UTILITY"
            )
        }
    }

    /**
     * Tests for Kotlin DSL (agent {}) created agents.
     */
    @Nested
    inner class KotlinDslAgentTests {

        @Test
        fun `SimpleProcessorDsl creates valid Agent using Kotlin DSL`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessorWithDsl()
            assertTrue(processor is AgentScope)
            assertEquals("SimpleProcessorDsl", processor.name)
            assertEquals(1, processor.goals.size, "Should have single goal")
            assertEquals(1, processor.actions.size, "Should have single action")
        }

        @Test
        fun `SimpleProcessorDsl is detected as AgentScope`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessorWithDsl()
            assertTrue(runner.isAgentScope(processor), "DSL agent should be detected as AgentScope")
        }

        @Test
        fun `SimpleProcessorDsl with single goal is runnable with GOAP`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessorWithDsl()
            assertTrue(
                runner.isRunnableNestedAgent(processor, PlannerType.GOAP),
                "DSL agent with single goal should be runnable with GOAP"
            )
        }

        @Test
        fun `SimpleProcessorDsl is runnable with UTILITY`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessorWithDsl()
            assertTrue(
                runner.isRunnableNestedAgent(processor, PlannerType.UTILITY),
                "DSL agent should be runnable with UTILITY"
            )
        }

        @Test
        fun `SimpleProcessorDsl runs with GOAP planner`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessorWithDsl()
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val agentProcess = ap.runAgentFrom(
                processor,
                ProcessOptions().withPlannerType(PlannerType.GOAP),
                mapOf("it" to UserInput("dsl-goap-test"))
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, agentProcess.status,
                "DSL processor should complete with GOAP planner"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("dsl processed: dsl-goap-test", (result as ProcessedData).content)
        }

        @Test
        fun `SimpleProcessorDsl runs with UTILITY planner`() {
            val processor = ProgrammaticWriteAndReviewAgent.createSimpleProcessorWithDsl()
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val agentProcess = ap.runAgentFrom(
                processor,
                ProcessOptions().withPlannerType(PlannerType.UTILITY),
                mapOf("it" to UserInput("dsl-utility-test"))
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, agentProcess.status,
                "DSL processor should complete with UTILITY planner"
            )
            val result = agentProcess.lastResult()
            assertTrue(result is ProcessedData, "Expected ProcessedData but got $result")
            assertEquals("dsl processed: dsl-utility-test", (result as ProcessedData).content)
        }

        @Test
        fun `StoryWriterDsl creates valid Agent`() {
            val writer = ProgrammaticWriteAndReviewAgent.createStoryWriterWithDsl()
            assertEquals("StoryWriterDsl", writer.name)
            assertEquals(1, writer.goals.size)
            assertEquals(1, writer.actions.size)
        }

        @Test
        fun `StoryWriterDsl is runnable with GOAP`() {
            val writer = ProgrammaticWriteAndReviewAgent.createStoryWriterWithDsl()
            assertTrue(
                runner.isRunnableNestedAgent(writer, PlannerType.GOAP),
                "DSL story writer should be runnable with GOAP"
            )
        }

        @Test
        fun `ReviewerDsl creates valid Agent`() {
            val reviewer = ProgrammaticWriteAndReviewAgent.createReviewerWithDsl()
            assertEquals("ReviewerDsl", reviewer.name)
            assertEquals(1, reviewer.goals.size)
            assertEquals(1, reviewer.actions.size)
        }

        @Test
        fun `ReviewerDsl is runnable with GOAP`() {
            val reviewer = ProgrammaticWriteAndReviewAgent.createReviewerWithDsl()
            assertTrue(
                runner.isRunnableNestedAgent(reviewer, PlannerType.GOAP),
                "DSL reviewer should be runnable with GOAP"
            )
        }
    }
}

// Empty @Agent class - no @Action methods, should not be runnable
@Agent(description = "Empty agent")
class EmptyAgentClass

// @Agent class with multiple goals - should NOT be runnable with GOAP
@Agent(description = "Multi-goal agent")
class MultiGoalAgent {

    @Action
    @AchievesGoal(description = "First goal")
    fun firstGoal(it: UserInput): ProcessedData = ProcessedData("first: ${it.content}")

    @Action
    @AchievesGoal(description = "Second goal")
    fun secondGoal(it: UserInput): ProcessedData = ProcessedData("second: ${it.content}")
}

// @Subflow class with multiple goals - should NOT be runnable with GOAP
@Subflow
class MultiGoalSubflow(val data: String) {

    @Action
    @AchievesGoal(description = "Process A")
    fun processA(): ProcessedData = ProcessedData("A: $data")

    @Action
    @AchievesGoal(description = "Process B")
    fun processB(): ProcessedData = ProcessedData("B: $data")
}

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

// GOAP agent that returns a single-goal @Subflow - should work with GOAP
@Agent(description = "GOAP agent with @Subflow", planner = PlannerType.GOAP)
class GoapAgentWithSubflow {

    @Action
    @AchievesGoal(description = "Process input through nested subflow")
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

// Helper functions to create programmatic agents for testing

/**
 * Creates a programmatic Agent (core.Agent) with a single goal.
 * This should be runnable with both GOAP and Utility planners once implemented.
 */
fun createSingleGoalAgent(): CoreAgent {
    val action = SupplierAction(
        name = "generateProcessedData",
        description = "Generates ProcessedData",
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = false,
        pre = emptyList(),
        outputClass = ProcessedData::class.java,
        toolGroups = emptySet(),
    ) {
        ProcessedData("programmatic result")
    }

    val goal = Goal(
        name = "ProcessedData",
        description = "Goal to produce ProcessedData",
        satisfiedBy = ProcessedData::class.java,
    )

    return CoreAgent(
        name = "ProgrammaticSingleGoalAgent",
        provider = "test",
        description = "A programmatically created agent with a single goal",
        actions = listOf(action),
        goals = setOf(goal),
    )
}

/**
 * Creates a programmatic Agent (core.Agent) with multiple goals.
 * This should only be runnable with Utility planner once implemented.
 */
fun createMultiGoalAgent(): CoreAgent {
    val action1 = SupplierAction(
        name = "generateProcessedData",
        description = "Generates ProcessedData",
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = false,
        pre = emptyList(),
        outputClass = ProcessedData::class.java,
        toolGroups = emptySet(),
    ) {
        ProcessedData("result 1")
    }

    val action2 = SupplierAction(
        name = "generateAnotherData",
        description = "Generates another ProcessedData",
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = false,
        pre = emptyList(),
        outputClass = ProcessedData::class.java,
        toolGroups = emptySet(),
    ) {
        ProcessedData("result 2")
    }

    val goal1 = Goal(
        name = "FirstGoal",
        description = "First goal",
        satisfiedBy = ProcessedData::class.java,
    )

    val goal2 = Goal(
        name = "SecondGoal",
        description = "Second goal",
        satisfiedBy = ProcessedData::class.java,
    )

    return CoreAgent(
        name = "ProgrammaticMultiGoalAgent",
        provider = "test",
        description = "A programmatically created agent with multiple goals",
        actions = listOf(action1, action2),
        goals = setOf(goal1, goal2),
    )
}

/**
 * Creates a programmatic AgentScope with a single goal.
 * AgentScope is the interface that Agent implements.
 */
fun createSingleGoalAgentScope(): AgentScope {
    val action = SupplierAction(
        name = "generateProcessedData",
        description = "Generates ProcessedData",
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = false,
        pre = emptyList(),
        outputClass = ProcessedData::class.java,
        toolGroups = emptySet(),
    ) {
        ProcessedData("scope result")
    }

    val goal = Goal(
        name = "ProcessedData",
        description = "Goal to produce ProcessedData",
        satisfiedBy = ProcessedData::class.java,
    )

    return AgentScope(
        name = "ProgrammaticAgentScope",
        description = "A programmatically created AgentScope",
        actions = listOf(action),
        goals = setOf(goal),
    )
}
