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
import com.embabel.agent.api.common.subflow.FlowReturning
import com.embabel.agent.api.common.workflow.WorkflowRunner
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for WriteAndReviewAgent and its nested workflow pattern.
 */
class WriteAndReviewAgentTest {

    private val reader = AgentMetadataReader()
    private val runner = WorkflowRunner()

    @Nested
    inner class DomainTypes {

        @Test
        fun `Story is a simple data class`() {
            val story = Story("test content")
            assertEquals("test content", story.text)
        }

        @Test
        fun `HumanFeedback captures approval status and comments`() {
            val feedback = HumanFeedback(approved = true, comments = "Great!")
            assertTrue(feedback.approved)
            assertEquals("Great!", feedback.comments)
        }

        @Test
        fun `Assessment combines story, feedback, and decision`() {
            val story = Story("test")
            val feedback = HumanFeedback(approved = false, comments = "Needs work")
            val assessment = Assessment(
                story = story,
                feedback = feedback,
                accepted = false,
                suggestions = listOf("Add detail")
            )
            assertFalse(assessment.accepted)
            assertEquals(1, assessment.suggestions.size)
        }

        @Test
        fun `ReviewedStory is the final output combining story and feedback`() {
            val story = Story("final story")
            val feedback = HumanFeedback(approved = true, comments = "Perfect")
            val reviewed = ReviewedStory(story, feedback)
            assertEquals("final story", reviewed.story.text)
            assertTrue(reviewed.feedback.approved)
        }
    }

    @Nested
    inner class FlowReturningInterfaceDetection {

        @Test
        fun `StoryFlow interface extends Workflow with Story output type`() {
            // Verify the interface hierarchy
            assertTrue(FlowReturning::class.java.isAssignableFrom(WriteAndReviewAgent.StoryFlow::class.java))
        }

        @Test
        fun `Reviewing implements StoryFlow`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            assertTrue(reviewing is WriteAndReviewAgent.StoryFlow)
            assertTrue(reviewing is FlowReturning<*>)
            assertEquals(Story::class.java, reviewing.outputType)
        }

        @Test
        fun `Revising implements StoryFlow`() {
            val story = Story("test")
            val feedback = HumanFeedback(approved = false, comments = "fix it")
            val assessment = Assessment(story, feedback, false, listOf("improve"))
            val revising = WriteAndReviewAgent.Revising(story, assessment)
            assertTrue(revising is WriteAndReviewAgent.StoryFlow)
            assertEquals(Story::class.java, revising.outputType)
        }

        @Test
        fun `Done implements StoryFlow`() {
            val story = Story("final")
            val feedback = HumanFeedback(approved = true, comments = "good")
            val done = WriteAndReviewAgent.Done(story, feedback)
            assertTrue(done is WriteAndReviewAgent.StoryFlow)
            assertEquals(Story::class.java, done.outputType)
        }
    }

    @Nested
    inner class FlowReturningRunnerDetection {

        @Test
        fun `Reviewing is detected as runnable workflow`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            assertTrue(runner.isFlowReturning(reviewing))
        }

        @Test
        fun `Revising is detected as runnable workflow`() {
            val story = Story("test")
            val feedback = HumanFeedback(approved = false, comments = "fix it")
            val assessment = Assessment(story, feedback, false, listOf("improve"))
            val revising = WriteAndReviewAgent.Revising(story, assessment)
            assertTrue(runner.isFlowReturning(revising))
        }

        @Test
        fun `Done is detected as runnable workflow`() {
            val story = Story("final")
            val feedback = HumanFeedback(approved = true, comments = "good")
            val done = WriteAndReviewAgent.Done(story, feedback)
            assertTrue(runner.isFlowReturning(done))
        }

        @Test
        fun `plain Story is not a workflow`() {
            assertFalse(runner.isFlowReturning(Story("test")))
        }

        @Test
        fun `HumanFeedback is not a workflow`() {
            assertFalse(runner.isFlowReturning(HumanFeedback(true, "test")))
        }
    }

    @Nested
    inner class AgentMetadataCreation {

        @Test
        fun `WriteAndReviewAgent creates valid agent metadata`() {
            val agent = WriteAndReviewAgent()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)
            assertEquals("WriteAndReviewAgent", metadata!!.name)
        }

        @Test
        fun `WriteAndReviewAgent has writeStory action`() {
            val agent = WriteAndReviewAgent()
            val metadata = reader.createAgentMetadata(agent)
            assertNotNull(metadata)
            val writeStoryAction = metadata!!.actions.find { it.name.contains("writeStory") }
            assertNotNull(writeStoryAction, "Should have writeStory action")
        }

        @Test
        fun `Reviewing workflow creates valid agent metadata`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            val metadata = reader.createAgentMetadata(reviewing)
            assertNotNull(metadata)
            assertEquals(3, metadata!!.actions.size, "Reviewing should have 3 actions")
        }

        @Test
        fun `Reviewing has collectFeedback, assessFeedback, and decide actions`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            val metadata = reader.createAgentMetadata(reviewing)
            assertNotNull(metadata)
            val actionNames = metadata!!.actions.map { it.name }
            assertTrue(actionNames.any { it.contains("collectFeedback") }, "Should have collectFeedback")
            assertTrue(actionNames.any { it.contains("assessFeedback") }, "Should have assessFeedback")
            assertTrue(actionNames.any { it.contains("decide") }, "Should have decide")
        }

        @Test
        fun `Reviewing has workflow goal based on outputType`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            val metadata = reader.createAgentMetadata(reviewing)
            assertNotNull(metadata)
            val workflowGoal = metadata!!.goals.find { it.name.contains("workflow_output") }
            assertNotNull(workflowGoal, "Should have workflow output goal")
            assertEquals(Story::class.java.name, workflowGoal!!.outputType?.name)
        }

        @Test
        fun `Done workflow creates valid agent metadata with AchievesGoal`() {
            val done = WriteAndReviewAgent.Done(Story("final"), HumanFeedback(true, "ok"))
            val metadata = reader.createAgentMetadata(done)
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size, "Done should have 1 action (publish)")

            // Should have both workflow goal and AchievesGoal
            assertTrue(metadata.goals.size >= 1, "Should have at least 1 goal")
        }

        @Test
        fun `Revising workflow creates valid agent metadata`() {
            val story = Story("test")
            val feedback = HumanFeedback(approved = false, comments = "fix")
            val assessment = Assessment(story, feedback, false, listOf("improve"))
            val revising = WriteAndReviewAgent.Revising(story, assessment)
            val metadata = reader.createAgentMetadata(revising)
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size, "Revising should have 1 action (improveStory)")
        }
    }

    @Nested
    inner class IndividualFlowReturningExecution {

        @Test
        fun `Done workflow executes and produces ReviewedStory`() {
            val story = Story("My final story")
            val feedback = HumanFeedback(approved = true, comments = "Excellent!")
            val done = WriteAndReviewAgent.Done(story, feedback)

            val metadata = reader.createAgentMetadata(done)
            assertNotNull(metadata)

            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val agentProcess = ap.runAgentFrom(
                metadata!!.createAgent(
                    name = metadata.name,
                    provider = "test",
                    description = "Test Done workflow"
                ),
                ProcessOptions(),
                emptyMap()
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
            val result = agentProcess.lastResult()
            assertTrue(result is ReviewedStory, "Expected ReviewedStory but got $result")
            assertEquals("My final story", (result as ReviewedStory).story.text)
            assertEquals("Excellent!", result.feedback.comments)
        }
    }

    @Nested
    inner class ActionBehavior {

        @Test
        fun `writeStory creates Story and returns Reviewing workflow`() {
            val agent = WriteAndReviewAgent()
            val input = UserInput("dragons")
            val result = agent.writeStory(input)

            assertTrue(result is WriteAndReviewAgent.Reviewing)
            assertEquals("A story about: dragons", result.story.text)
        }

        @Test
        fun `Reviewing collectFeedback returns HumanFeedback`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("test"))
            val feedback = reviewing.collectFeedback()

            assertFalse(feedback.approved, "Default test feedback is not approved")
            assertEquals("Needs more detail", feedback.comments)
        }

        @Test
        fun `Reviewing assessFeedback creates Assessment from feedback`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("my story"))
            val feedback = HumanFeedback(approved = false, comments = "Add more")
            val assessment = reviewing.assessFeedback(feedback)

            assertEquals("my story", assessment.story.text)
            assertFalse(assessment.accepted)
            assertEquals(listOf("Add more detail"), assessment.suggestions)
        }

        @Test
        fun `Reviewing decide returns Done when accepted`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("good story"))
            val feedback = HumanFeedback(approved = true, comments = "Perfect!")
            val assessment = Assessment(
                story = Story("good story"),
                feedback = feedback,
                accepted = true,
                suggestions = emptyList()
            )

            val result = reviewing.decide(assessment)
            assertTrue(result is WriteAndReviewAgent.Done, "Should return Done when accepted")
        }

        @Test
        fun `Reviewing decide returns Revising when not accepted`() {
            val reviewing = WriteAndReviewAgent.Reviewing(Story("draft story"))
            val feedback = HumanFeedback(approved = false, comments = "Needs work")
            val assessment = Assessment(
                story = Story("draft story"),
                feedback = feedback,
                accepted = false,
                suggestions = listOf("Improve pacing")
            )

            val result = reviewing.decide(assessment)
            assertTrue(result is WriteAndReviewAgent.Revising, "Should return Revising when not accepted")
        }

        @Test
        fun `Revising improveStory creates improved story and returns to Reviewing`() {
            val story = Story("Original story")
            val feedback = HumanFeedback(approved = false, comments = "Too short")
            val assessment = Assessment(story, feedback, false, listOf("Add more content"))
            val revising = WriteAndReviewAgent.Revising(story, assessment)

            val result = revising.improveStory()
            assertTrue(result is WriteAndReviewAgent.Reviewing, "Should return Reviewing")
            assertTrue(result.story.text.contains("Original story"), "Should keep original content")
            assertTrue(result.story.text.contains("Improved based on"), "Should mention improvement")
            assertTrue(result.story.text.contains("Add more content"), "Should include suggestions")
        }

        @Test
        fun `Done publish produces ReviewedStory`() {
            val story = Story("Final story")
            val feedback = HumanFeedback(approved = true, comments = "Great work!")
            val done = WriteAndReviewAgent.Done(story, feedback)

            val result = done.publish()
            assertEquals("Final story", result.story.text)
            assertEquals("Great work!", result.feedback.comments)
        }
    }

    @Nested
    inner class FlowReturningStateTransitions {

        @Test
        fun `happy path - story approved immediately`() {
            // Simulate: writeStory -> Reviewing -> (approved) -> Done -> ReviewedStory
            val agent = WriteAndReviewAgent()

            // Step 1: Write story
            val reviewing = agent.writeStory(UserInput("adventure"))
            assertEquals("A story about: adventure", reviewing.story.text)

            // Step 2: Collect feedback (approved)
            val feedback = HumanFeedback(approved = true, comments = "Wonderful!")

            // Step 3: Assess feedback
            val assessment = reviewing.assessFeedback(feedback)
            assertTrue(assessment.accepted)

            // Step 4: Decide - should go to Done
            val done = reviewing.decide(assessment)
            assertTrue(done is WriteAndReviewAgent.Done)

            // Step 5: Publish
            val result = (done as WriteAndReviewAgent.Done).publish()
            assertEquals("A story about: adventure", result.story.text)
            assertEquals("Wonderful!", result.feedback.comments)
        }

        @Test
        fun `revision path - story needs one revision`() {
            // Simulate: writeStory -> Reviewing -> (rejected) -> Revising -> Reviewing -> (approved) -> Done
            val agent = WriteAndReviewAgent()

            // Step 1: Write story
            val reviewing1 = agent.writeStory(UserInput("mystery"))

            // Step 2: First review - rejected
            val feedback1 = HumanFeedback(approved = false, comments = "Too predictable")
            val assessment1 = reviewing1.assessFeedback(feedback1)
            assertFalse(assessment1.accepted)

            // Step 3: Decide to revise
            val revising = reviewing1.decide(assessment1)
            assertTrue(revising is WriteAndReviewAgent.Revising)

            // Step 4: Improve story
            val reviewing2 = (revising as WriteAndReviewAgent.Revising).improveStory()
            assertTrue(reviewing2.story.text.contains("mystery"))
            assertTrue(reviewing2.story.text.contains("Improved"))

            // Step 5: Second review - approved
            val feedback2 = HumanFeedback(approved = true, comments = "Much better!")
            val assessment2 = reviewing2.assessFeedback(feedback2)
            assertTrue(assessment2.accepted)

            // Step 6: Decide - should go to Done
            val done = reviewing2.decide(assessment2)
            assertTrue(done is WriteAndReviewAgent.Done)

            // Step 7: Publish
            val result = (done as WriteAndReviewAgent.Done).publish()
            assertTrue(result.story.text.contains("Improved"))
            assertEquals("Much better!", result.feedback.comments)
        }

        @Test
        fun `multiple revisions maintain story history`() {
            val agent = WriteAndReviewAgent()

            // Initial story
            var reviewing: WriteAndReviewAgent.Reviewing = agent.writeStory(UserInput("sci-fi"))
            val originalText = reviewing.story.text

            // First revision
            val feedback1 = HumanFeedback(approved = false, comments = "More aliens")
            val assessment1 = reviewing.assessFeedback(feedback1)
            val revising1 = reviewing.decide(assessment1) as WriteAndReviewAgent.Revising
            reviewing = revising1.improveStory()

            // Second revision
            val feedback2 = HumanFeedback(approved = false, comments = "More spaceships")
            val assessment2 = reviewing.assessFeedback(feedback2)
            val revising2 = reviewing.decide(assessment2) as WriteAndReviewAgent.Revising
            reviewing = revising2.improveStory()

            // Story should contain original content and both improvements
            val finalText = reviewing.story.text
            assertTrue(finalText.contains("sci-fi"), "Should contain original topic")
            // Each revision adds to the text
            assertTrue(finalText.length > originalText.length, "Story should grow with revisions")
        }
    }
}
