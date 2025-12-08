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
package com.embabel.agent.api.annotation.support.statemachine

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.domain.io.UserInput

/**
 * State machine with a loop: A -> B -> (back to A or C) -> Output
 * This represents the WriteAndReview pattern.
 */
@Agent(
    description = "Write and review story with human feedback loop",
    planner = PlannerType.STATE_MACHINE,
)
class WriteAndReviewStateMachine : StateMachineWorkflow<UserInput, ReviewedStory> {

    sealed interface WorkflowState

    @State(initial = true)
    inner class Drafting : WorkflowState {

        @Action
        fun writeStory(input: UserInput): Reviewing {
            val story = Story("A story about: ${input.content}")
            return Reviewing(story)
        }
    }

    @State
    inner class Reviewing(val story: Story) : WorkflowState {

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
        fun decide(assessment: Assessment): WorkflowState {
            return if (assessment.accepted) {
                Done(assessment.story, assessment.feedback)
            } else {
                Revising(assessment.story, assessment)
            }
        }
    }

    @State
    inner class Revising(
        val story: Story,
        val assessment: Assessment,
    ) : WorkflowState {

        @Action
        fun improveStory(): Reviewing {
            val improvedStory = Story("${story.text} [Improved based on: ${assessment.suggestions}]")
            return Reviewing(improvedStory)
        }
    }

    @State // terminal inferred from @AchievesGoal
    inner class Done(
        val story: Story,
        val feedback: HumanFeedback,
    ) : WorkflowState {

        @Action
        @AchievesGoal(description = "Story reviewed and accepted")
        fun publish(): ReviewedStory {
            return ReviewedStory(story, feedback)
        }
    }
}
