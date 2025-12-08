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

// Domain types for the WriteAndReview workflow
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
 * Simple state machine agent with linear flow: A -> B -> C -> Output
 */
@Agent(
    description = "Simple linear state machine",
    planner = PlannerType.STATE_MACHINE,
)
class SimpleLinearStateMachine : StateMachineWorkflow<UserInput, String> {

    sealed interface WorkflowState

    @State(initial = true)
    inner class Start : WorkflowState {
        @Action
        fun begin(input: UserInput): Middle {
            return Middle(input.content)
        }
    }

    @State
    inner class Middle(val data: String) : WorkflowState {
        @Action
        fun process(): End {
            return End(data.uppercase())
        }
    }

    @State // terminal inferred from @AchievesGoal
    inner class End(val result: String) : WorkflowState {
        @Action
        @AchievesGoal(description = "Processing complete")
        fun finish(): String {
            return "Result: $result"
        }
    }
}

/**
 * State machine with branching: A -> B -> (C or D) -> Output
 */
@Agent(
    description = "Branching state machine",
    planner = PlannerType.STATE_MACHINE,
)
class BranchingStateMachine : StateMachineWorkflow<UserInput, String> {

    sealed interface WorkflowState

    @State(initial = true)
    inner class Initial : WorkflowState {
        @Action
        fun start(input: UserInput): Processing {
            return Processing(input.content)
        }
    }

    @State
    inner class Processing(val data: String) : WorkflowState {
        @Action
        fun decide(): WorkflowState {
            return if (data.length > 5) {
                LongPath(data)
            } else {
                ShortPath(data)
            }
        }
    }

    @State // terminal inferred from @AchievesGoal
    inner class LongPath(val data: String) : WorkflowState {
        @Action
        @AchievesGoal(description = "Long path complete")
        fun finish(): String {
            return "Long: $data"
        }
    }

    @State // terminal inferred from @AchievesGoal
    inner class ShortPath(val data: String) : WorkflowState {
        @Action
        @AchievesGoal(description = "Short path complete")
        fun finish(): String {
            return "Short: $data"
        }
    }
}

/**
 * State machine with GOAP planning within a state.
 * The Reviewing state has multiple actions that GOAP should plan.
 */
@Agent(
    description = "State machine with GOAP within states",
    planner = PlannerType.STATE_MACHINE,
)
class GoapWithinStatesMachine : StateMachineWorkflow<UserInput, String> {

    sealed interface WorkflowState

    data class IntermediateData(val value: String)
    data class ProcessedData(val value: String)

    @State(initial = true)
    inner class Start : WorkflowState {
        @Action
        fun begin(input: UserInput): MultiStepState {
            return MultiStepState(input.content)
        }
    }

    @State
    inner class MultiStepState(val input: String) : WorkflowState {
        // These actions should be planned by GOAP within this state
        @Action
        fun step1(): IntermediateData {
            return IntermediateData("Step1: $input")
        }

        @Action
        fun step2(data: IntermediateData): ProcessedData {
            return ProcessedData("Step2: ${data.value}")
        }

        // This action transitions to the next state
        @Action
        fun complete(data: ProcessedData): Finish {
            return Finish(data.value)
        }
    }

    @State // terminal inferred from @AchievesGoal
    inner class Finish(val result: String) : WorkflowState {
        @Action
        @AchievesGoal(description = "Multi-step processing complete")
        fun done(): String {
            return "Final: $result"
        }
    }
}

/**
 * Invalid state machine - no initial state
 */
@Agent(
    description = "Invalid - no initial state",
    planner = PlannerType.STATE_MACHINE,
)
class NoInitialStateMachine : StateMachineWorkflow<UserInput, String> {

    @State
    inner class OnlyState {
        @Action
        @AchievesGoal(description = "Done")
        fun finish(input: UserInput): String {
            return input.content
        }
    }
}

/**
 * Invalid state machine - multiple initial states
 */
@Agent(
    description = "Invalid - multiple initial states",
    planner = PlannerType.STATE_MACHINE,
)
class MultipleInitialStateMachine : StateMachineWorkflow<UserInput, String> {

    @State(initial = true)
    inner class Start1 {
        @Action
        fun go(): End {
            return End("from 1")
        }
    }

    @State(initial = true)
    inner class Start2 {
        @Action
        fun go(): End {
            return End("from 2")
        }
    }

    @State // terminal inferred from @AchievesGoal
    inner class End(val result: String) {
        @Action
        @AchievesGoal(description = "Done")
        fun finish(): String {
            return result
        }
    }
}

/**
 * Invalid state machine - no terminal state (no @AchievesGoal and no terminal=true)
 */
@Agent(
    description = "Invalid - no terminal state",
    planner = PlannerType.STATE_MACHINE,
)
class NoTerminalStateMachine : StateMachineWorkflow<UserInput, String> {

    @State(initial = true)
    inner class Start {
        @Action
        fun go(): Middle {
            return Middle()
        }
    }

    @State
    inner class Middle {
        @Action
        fun loop(): Start {
            return Start()
        }
    }
}

// ============================================================================
// Non-inner class state machines
// These demonstrate that states don't need to be inner classes
// ============================================================================

/**
 * State classes defined as nested classes (not inner) - no access to enclosing instance
 */
@Agent(
    description = "State machine with nested (non-inner) state classes",
    planner = PlannerType.STATE_MACHINE,
)
class NestedClassStateMachine : StateMachineWorkflow<UserInput, String> {

    @State(initial = true)
    class Start {
        @Action
        fun begin(input: UserInput): Processing {
            return Processing(input.content)
        }
    }

    @State
    class Processing(val data: String) {
        @Action
        fun process(): Done {
            return Done(data.uppercase())
        }
    }

    @State
    class Done(val result: String) {
        @Action
        @AchievesGoal(description = "Processing complete")
        fun finish(): String {
            return "Result: $result"
        }
    }
}
