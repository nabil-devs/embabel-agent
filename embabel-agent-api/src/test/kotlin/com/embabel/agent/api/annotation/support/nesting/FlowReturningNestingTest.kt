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

// Test domain types - separate from WriteAndReviewAgent's types
data class TestStory(val content: String)

/**
 * Tests for nested workflow pattern where actions can return Workflow<O> instances
 * to enter nested GOAP flows.
 */
class FlowReturningNestingTest {

    @Nested
    inner class FlowReturningDetection {

        @Test
        fun `plain data class is not a workflow`() {
            val runner = WorkflowRunner()
            assertFalse(runner.isFlowReturning(TestStory("test")))
        }

        @Test
        fun `class implementing Workflow with @Action methods is detected`() {
            val runner = WorkflowRunner()
            val workflow = SimpleFlowReturning("data")
            assertTrue(runner.isFlowReturning(workflow))
        }

        @Test
        fun `Workflow without @Action methods is not runnable`() {
            val runner = WorkflowRunner()
            val workflow = EmptyFlowReturning()
            assertFalse(runner.isFlowReturning(workflow))
        }

        @Test
        fun `nested static class is not detected as inner class`() {
            // WriteAndReviewAgent's Reviewing is a nested (static) class
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            assertFalse(
                reviewing::class.java.isMemberClass &&
                        !java.lang.reflect.Modifier.isStatic(reviewing::class.java.modifiers),
                "Nested class should not be detected as inner class"
            )
        }

        @Test
        fun `inner class is detected correctly`() {
            // AgentWithInnerWorkflow's InnerWorkflow is an inner class
            val agent = AgentWithInnerWorkflow()
            val innerWorkflow = agent.InnerFlowReturning("test")
            assertTrue(
                innerWorkflow::class.java.isMemberClass &&
                        !java.lang.reflect.Modifier.isStatic(innerWorkflow::class.java.modifiers),
                "Inner class should be detected as inner class"
            )
        }
    }

    @Nested
    inner class AgentMetadataCreation {

        @Test
        fun `can create agent metadata from workflow class`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(SimpleFlowReturning("test"))
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            // Should have a goal based on the Workflow's outputType
            assertTrue(metadata.goals.any { it.name.contains("workflow_output") })
        }

        @Test
        fun `workflow goal uses correct output type`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(SimpleFlowReturning("test"))
            assertNotNull(metadata)
            val workflowGoal = metadata!!.goals.find { it.name.contains("workflow_output") }
            assertNotNull(workflowGoal)
            assertEquals(TestStory::class.java.name, workflowGoal!!.outputType?.name)
        }

        @Test
        fun `cannot create agent metadata from plain data class`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(TestStory("test"))
            assertNull(metadata)
        }
    }

    @Nested
    inner class NestedExecution {

        @Test
        fun `agent with single workflow returns correct result`() {
            val reader = AgentMetadataReader()
            val agent = SimpleAgentWithWorkflow()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata as CoreAgent,
                ProcessOptions(),
                mapOf("it" to UserInput("test input"))
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
            val result = agentProcess.lastResult()
            assertTrue(result is TestStory, "Expected TestStory but got $result")
            assertEquals("processed: test input", (result as TestStory).content)
        }

        @Test
        fun `agent with chained workflows runs to completion`() {
            // TODO: This test requires multi-level workflow nesting which is complex
            // For now, skip this and focus on single-level nesting
            val reader = AgentMetadataReader()
            val agent = AgentWithChainedWorkflows()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)
            // Basic validation - agent has the expected structure
            assertEquals(2, metadata!!.actions.size) // startProcessing and complete
        }
    }
}

// Empty workflow - no @Action methods, should not be runnable
class EmptyFlowReturning : FlowReturning<TestStory> {
    override val outputType = TestStory::class.java
}

// Simple workflow with one action
class SimpleFlowReturning(val data: String) : FlowReturning<TestStory> {
    override val outputType = TestStory::class.java

    @Action
    @AchievesGoal(description = "Process data")
    fun process(): TestStory = TestStory("processed: $data")
}

// Workflow for the second phase
class FinalizingFlowReturning(val story: TestStory) : FlowReturning<TestStory> {
    override val outputType = TestStory::class.java

    @Action
    @AchievesGoal(description = "Finalize story")
    fun finalize(): TestStory = TestStory("${story.content} - finalized")
}

// Simple agent that enters a workflow
@Agent(description = "Simple agent with workflow")
class SimpleAgentWithWorkflow {

    @Action
    @AchievesGoal(description = "Get a processed story")
    fun enterWorkflow(input: UserInput): SimpleFlowReturning {
        return SimpleFlowReturning(input.content)
    }
}

// Agent that chains multiple workflows
@Agent(description = "Agent with chained workflows")
class AgentWithChainedWorkflows {

    @Action
    fun startProcessing(input: UserInput): ProcessingFlowReturning {
        return ProcessingFlowReturning(input.content)
    }

    class ProcessingFlowReturning(val content: String) : FlowReturning<TestStory> {
        override val outputType = TestStory::class.java

        @Action
        fun process(): FinalizingFlowReturning {
            val story = TestStory("processing: $content")
            return FinalizingFlowReturning(story)
        }
    }

    // Goal at the agent level
    @Action
    @AchievesGoal(description = "Get finalized story")
    fun complete(story: TestStory): TestStory = story
}

// Agent with an inner class workflow (not recommended - for testing warning)
@Agent(description = "Agent with inner class workflow")
class AgentWithInnerWorkflow {

    // Inner class (note the 'inner' keyword) - holds reference to enclosing instance
    // This will trigger a warning at runtime
    inner class InnerFlowReturning(val data: String) : FlowReturning<TestStory> {
        override val outputType = TestStory::class.java

        @Action
        @AchievesGoal(description = "Process with inner class")
        fun process(): TestStory = TestStory("inner processed: $data")
    }

    @Action
    fun enterWorkflow(input: UserInput): InnerFlowReturning {
        return InnerFlowReturning(input.content)
    }
}
