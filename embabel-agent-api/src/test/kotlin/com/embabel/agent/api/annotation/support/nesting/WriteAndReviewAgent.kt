package com.embabel.agent.api.annotation.support.nesting

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
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

    sealed interface WorkflowState

    // Entry point for the workflow
    @Action
    fun writeStory(input: UserInput): Reviewing {
        val story = Story("A story about: ${input.content}")
        // Return a state class
        return Reviewing(story)
    }

    class Reviewing(val story: Story) : WorkflowState {

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

    class Revising(
        val story: Story,
        val assessment: Assessment,
    ) : WorkflowState {

        @Action
        fun improveStory(): Reviewing {
            val improvedStory = Story("${story.text} [Improved based on: ${assessment.suggestions}]")
            return Reviewing(improvedStory)
        }
    }

    class Done(
        val story: Story,
        val feedback: HumanFeedback,
    ) : WorkflowState {

        // We can see that this is a terminal state here
        // because of the AchievesGoal annotation
        @Action
        @AchievesGoal(description = "Story reviewed and accepted")
        fun publish(): ReviewedStory {
            return ReviewedStory(story, feedback)
        }
    }
}
