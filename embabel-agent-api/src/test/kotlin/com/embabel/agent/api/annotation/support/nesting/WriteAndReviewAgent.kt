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
import com.embabel.agent.api.common.workflow.Workflow
import com.embabel.agent.domain.io.UserInput

data class Story(val text: String)

data class HumanFeedback(
    val approved: Boolean,
    val comments: String,
)

data class Assessment(
    val story: Story,
    val feedback: HumanFeedback,
    val accepted: Boolean,
    val suggestions: List<String>,
)

data class ReviewedStory(
    val story: Story,
    val feedback: HumanFeedback,
)

/**
 * State machine with a loop: A -> B -> (back to A or C) -> Output
 * This represents the WriteAndReview pattern.
 */
@Agent(
    description = "Write and review story with human feedback loop",
)
class WriteAndReviewAgent {

    interface StoryFlow : Workflow<Story> {
        override val outputType: Class<Story>
            get() = Story::class.java
    }

    // Entry point for the workflow
    @Action
    fun writeStory(input: UserInput): Reviewing {
        val story = Story("A story about: ${input.content}")
        // Return a state class
        return Reviewing(story)
    }

    class Reviewing(val story: Story) : StoryFlow {

        @Action
        fun collectFeedback(): HumanFeedback {
            // In real usage, this would use waitFor(fromForm(HumanFeedback::class.java))
            // For testing, we simulate the feedback
            return HumanFeedback(approved = false, comments = "Needs more detail")
        }

        @Action
        fun assessFeedback(feedback: HumanFeedback): Assessment {
            // In real usage, this might use AI to analyze the feedback
            return Assessment(
                story = story,
                feedback = feedback,
                accepted = feedback.approved,
                suggestions = if (feedback.approved) emptyList() else listOf("Add more detail"),
            )
        }

        @Action
        fun decide(assessment: Assessment): StoryFlow {
            return if (assessment.accepted) {
                Done(assessment.story, assessment.feedback)
            } else {
                Revising(assessment.story, assessment)
            }
        }
    }

    class Revising(
        val story: Story,
        val assessment: Assessment,
    ) : StoryFlow {

        @Action
        fun improveStory(): Reviewing {
            val improvedStory = Story("${story.text} [Improved based on: ${assessment.suggestions}]")
            return Reviewing(improvedStory)
        }
    }

    class Done(
        val story: Story,
        val feedback: HumanFeedback,
    ) : StoryFlow {

        // We can see that this is a terminal state here
        // because of the AchievesGoal annotation
        @Action
        @AchievesGoal(description = "Story reviewed and accepted")
        fun publish(): ReviewedStory {
            return ReviewedStory(story, feedback)
        }
    }
}
