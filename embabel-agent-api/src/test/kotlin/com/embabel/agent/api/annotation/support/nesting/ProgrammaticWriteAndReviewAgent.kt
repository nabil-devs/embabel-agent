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

import com.embabel.agent.api.common.workflow.control.SimpleAgentBuilder
import com.embabel.agent.api.common.workflow.loop.RepeatUntilBuilder
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentScope
import com.embabel.agent.domain.io.UserInput

/**
 * Programmatic version of WriteAndReviewAgent demonstrating nested agent patterns
 * using high-level DSL builders (SimpleAgentBuilder, RepeatUntilBuilder) and
 * the Kotlin DSL (agent {}) instead of annotation-based classes.
 *
 * This shows how programmatically created agents can use the same nested flow
 * mechanism as annotated classes.
 *
 * The workflow:
 * 1. writeStory: UserInput -> Story
 * 2. Review loop: Story -> ReviewedStory (with simulated feedback)
 */
object ProgrammaticWriteAndReviewAgent {

    /**
     * Creates a simple processor agent using SimpleAgentBuilder.
     * This is the simplest way to create a programmatic agent.
     */
    fun createSimpleProcessor(): AgentScope {
        return SimpleAgentBuilder<ProcessedData>()
            .consuming(UserInput::class.java)
            .running { ctx ->
                ProcessedData("programmatically processed: ${ctx.input.content}")
            }
            .build()
            .build()
    }

    /**
     * Creates a reviewing agent using RepeatUntilBuilder for the feedback loop.
     * Demonstrates a more complex programmatic workflow with iteration.
     */
    fun createReviewingAgent(story: Story): AgentScope {
        // Use RepeatUntilBuilder to create a feedback loop
        return RepeatUntilBuilder
            .returning(ReviewedStory::class.java)
            .withMaxIterations(3)
            .repeating { ctx ->
                // Simulate feedback - in real usage this would use waitFor(fromForm(...))
                val attemptCount = ctx.history.attemptCount()
                val feedback = HumanFeedback(
                    approved = attemptCount >= 1, // Approve after first iteration
                    comments = if (attemptCount >= 1) "Great story!" else "Needs more detail"
                )
                ReviewedStory(story, feedback)
            }
            .until { ctx ->
                // Accept when the reviewer approves
                ctx.lastAttempt()?.feedback?.approved == true
            }
            .build()
            .build()
    }

    /**
     * Creates a story writer agent using SimpleAgentBuilder.
     */
    fun createStoryWriter(): AgentScope {
        return SimpleAgentBuilder<Story>()
            .consuming(UserInput::class.java)
            .running { ctx ->
                Story("A story about: ${ctx.input.content}")
            }
            .build()
            .build()
    }

    /**
     * Creates a complete write-and-review workflow using RepeatUntilBuilder.
     * This combines writing and reviewing with a feedback loop.
     */
    fun createWriteAndReviewWorkflow(): AgentScope {
        return RepeatUntilBuilder
            .returning(ReviewedStory::class.java)
            .consuming(UserInput::class.java)
            .withMaxIterations(5)
            .repeating { ctx ->
                val attemptCount = ctx.history.attemptCount()
                // Write the story
                val story = Story("A story about: ${ctx.input?.content} (iteration $attemptCount)")

                // Simulate feedback
                val feedback = HumanFeedback(
                    approved = attemptCount >= 2, // Approve after second iteration
                    comments = if (attemptCount >= 2) "Excellent!" else "Keep improving"
                )
                ReviewedStory(story, feedback)
            }
            .until { ctx ->
                ctx.lastAttempt()?.feedback?.approved == true
            }
            .build()
            .build()
    }

    // ========== Kotlin DSL (agent {}) versions ==========

    /**
     * Creates a simple processor agent using the Kotlin DSL.
     * This is the most idiomatic Kotlin way to create agents.
     */
    fun createSimpleProcessorWithDsl(): Agent {
        return agent(
            name = "SimpleProcessorDsl",
            description = "Simple processor using Kotlin DSL",
        ) {
            transformation<UserInput, ProcessedData>(
                name = "process",
                description = "Process user input into processed data",
            ) {
                ProcessedData("dsl processed: ${it.input.content}")
            }

            goal(
                name = "ProcessedData",
                description = "Data has been processed",
                satisfiedBy = ProcessedData::class,
            )
        }
    }

    /**
     * Creates a story writer agent using the Kotlin DSL.
     */
    fun createStoryWriterWithDsl(): Agent {
        return agent(
            name = "StoryWriterDsl",
            description = "Write a story using Kotlin DSL",
        ) {
            transformation<UserInput, Story>(
                name = "writeStory",
                description = "Write a story based on user input",
            ) {
                Story("A DSL story about: ${it.input.content}")
            }

            goal(
                name = "Story",
                description = "A story has been written",
                satisfiedBy = Story::class,
            )
        }
    }

    /**
     * Creates a reviewing agent using the Kotlin DSL.
     * This agent reviews a story and produces feedback.
     */
    fun createReviewerWithDsl(): Agent {
        return agent(
            name = "ReviewerDsl",
            description = "Review a story using Kotlin DSL",
        ) {
            transformation<Story, ReviewedStory>(
                name = "reviewStory",
                description = "Review the story and provide feedback",
            ) {
                val feedback = HumanFeedback(
                    approved = true,
                    comments = "Great story from DSL!"
                )
                ReviewedStory(it.input, feedback)
            }

            goal(
                name = "ReviewedStory",
                description = "Story has been reviewed",
                satisfiedBy = ReviewedStory::class,
            )
        }
    }
}
