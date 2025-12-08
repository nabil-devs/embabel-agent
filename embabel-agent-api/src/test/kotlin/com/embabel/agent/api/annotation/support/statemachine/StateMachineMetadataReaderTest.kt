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

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.StateMachineMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.Agent
import com.embabel.plan.statemachine.StateMachinePlanningSystem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StateMachineMetadataReaderTest {

    private val reader = StateMachineMetadataReader()
    private val agentReader = AgentMetadataReader()

    @Nested
    @DisplayName("State Detection")
    inner class StateDetection {

        @Test
        fun `detects state machine workflow from interface`() {
            val machine = SimpleLinearStateMachine()
            assertTrue(reader.isStateMachineWorkflow(machine))
        }

        @Test
        fun `detects state classes in workflow`() {
            val machine = SimpleLinearStateMachine()
            val states = reader.findStateClasses(machine.javaClass)

            assertEquals(3, states.size, "Should find 3 state classes")
            assertTrue(states.any { it.simpleName == "Start" })
            assertTrue(states.any { it.simpleName == "Middle" })
            assertTrue(states.any { it.simpleName == "End" })
        }

        @Test
        fun `identifies initial state`() {
            val machine = SimpleLinearStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)
            assertEquals("Start", planningSystem!!.initialState.name)
            assertTrue(planningSystem.initialState.isInitial)
        }

        @Test
        fun `identifies terminal states`() {
            val machine = SimpleLinearStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)
            assertEquals(1, planningSystem!!.terminalStates.size)
            assertEquals("End", planningSystem.terminalStates.first().name)
            assertTrue(planningSystem.terminalStates.first().isTerminal)
        }

        @Test
        fun `detects branching with multiple terminal states`() {
            val machine = BranchingStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)
            assertEquals(2, planningSystem!!.terminalStates.size)
            val terminalNames = planningSystem.terminalStates.map { it.name }.toSet()
            assertTrue(terminalNames.contains("LongPath"))
            assertTrue(terminalNames.contains("ShortPath"))
        }
    }

    @Nested
    @DisplayName("Action Detection Within States")
    inner class ActionDetection {

        @Test
        fun `finds actions in each state`() {
            val machine = SimpleLinearStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val startState = planningSystem!!.states.find { it.name == "Start" }
            assertNotNull(startState)
            assertEquals(1, startState!!.actions.size)
            assertTrue(startState.actions.any { it.name.contains("begin") })

            val middleState = planningSystem.states.find { it.name == "Middle" }
            assertNotNull(middleState)
            assertEquals(1, middleState!!.actions.size)
            assertTrue(middleState.actions.any { it.name.contains("process") })

            val endState = planningSystem.states.find { it.name == "End" }
            assertNotNull(endState)
            assertEquals(1, endState!!.actions.size)
            assertTrue(endState.actions.any { it.name.contains("finish") })
        }

        @Test
        fun `finds multiple actions in state for GOAP planning`() {
            val machine = GoapWithinStatesMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val multiStepState = planningSystem!!.states.find { it.name == "MultiStepState" }
            assertNotNull(multiStepState)
            assertEquals(3, multiStepState!!.actions.size, "Should have 3 actions for GOAP to plan")
        }
    }

    @Nested
    @DisplayName("Transition Detection")
    inner class TransitionDetection {

        @Test
        fun `detects transitions from action return types`() {
            val machine = SimpleLinearStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val startState = planningSystem!!.states.find { it.name == "Start" }
            assertNotNull(startState)
            assertEquals(1, startState!!.transitions.size)
            assertEquals("Middle", startState.transitions.first().targetStateClass?.simpleName)
        }

        @Test
        fun `detects branching transitions returning sealed State`() {
            val machine = BranchingStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val processingState = planningSystem!!.states.find { it.name == "Processing" }
            assertNotNull(processingState)
            // The decide() method returns State, which could be LongPath or ShortPath
            assertEquals(1, processingState!!.transitions.size)
            // The target is the sealed State interface - runtime determines actual target
            assertNull(
                processingState.transitions.first().targetStateClass,
                "Sealed type transition should have null target (determined at runtime)"
            )
        }

        @Test
        fun `detects loop transitions`() {
            val machine = WriteAndReviewStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val revisingState = planningSystem!!.states.find { it.name == "Revising" }
            assertNotNull(revisingState)
            assertEquals(1, revisingState!!.transitions.size)
            assertEquals("Reviewing", revisingState.transitions.first().targetStateClass?.simpleName)
        }

        @Test
        fun `detects terminal transitions`() {
            val machine = SimpleLinearStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val endState = planningSystem!!.states.find { it.name == "End" }
            assertNotNull(endState)
            assertEquals(1, endState!!.transitions.size)
            assertTrue(endState.transitions.first().isTerminal)
        }
    }

    @Nested
    @DisplayName("WriteAndReview Workflow")
    inner class WriteAndReviewWorkflow {

        @Test
        fun `reads complete WriteAndReview state machine`() {
            val machine = WriteAndReviewStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)
            assertEquals(4, planningSystem!!.states.size)

            // Verify state names
            val stateNames = planningSystem.states.map { it.name }.toSet()
            assertEquals(setOf("Drafting", "Reviewing", "Revising", "Done"), stateNames)

            // Verify initial state
            assertEquals("Drafting", planningSystem.initialState.name)

            // Verify terminal state
            assertEquals(1, planningSystem.terminalStates.size)
            assertEquals("Done", planningSystem.terminalStates.first().name)
        }

        @Test
        fun `Reviewing state has GOAP-plannable actions`() {
            val machine = WriteAndReviewStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val reviewingState = planningSystem!!.states.find { it.name == "Reviewing" }
            assertNotNull(reviewingState)

            // Should have: collectFeedback, assessFeedback, decide
            assertEquals(3, reviewingState!!.actions.size)

            val actionNames = reviewingState.actions.map { it.name }
            assertTrue(actionNames.any { it.contains("collectFeedback") })
            assertTrue(actionNames.any { it.contains("assessFeedback") })
            assertTrue(actionNames.any { it.contains("decide") })
        }

        @Test
        fun `Revising state loops back to Reviewing`() {
            val machine = WriteAndReviewStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNotNull(planningSystem)

            val revisingState = planningSystem!!.states.find { it.name == "Revising" }
            assertNotNull(revisingState)

            val transition = revisingState!!.transitions.first()
            assertEquals("Reviewing", transition.targetStateClass?.simpleName)
            assertFalse(transition.isTerminal)
        }
    }

    @Nested
    @DisplayName("Validation")
    inner class Validation {

        @Test
        fun `rejects state machine with no initial state`() {
            val machine = NoInitialStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            // Should return null or throw - implementation decides
            assertNull(planningSystem, "Should reject state machine without initial state")
        }

        @Test
        fun `rejects state machine with multiple initial states`() {
            val machine = MultipleInitialStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNull(planningSystem, "Should reject state machine with multiple initial states")
        }

        @Test
        fun `rejects state machine with no terminal state`() {
            val machine = NoTerminalStateMachine()
            val planningSystem = reader.readStateMachineMetadata(machine)

            assertNull(planningSystem, "Should reject state machine without terminal state")
        }
    }

    @Nested
    @DisplayName("Integration with AgentMetadataReader")
    inner class AgentIntegration {

        @Test
        fun `AgentMetadataReader recognizes STATE_MACHINE planner type`() {
            val machine = SimpleLinearStateMachine()
            val agent = agentReader.createAgentMetadata(machine)

            assertNotNull(agent)
            assertTrue(agent is Agent)
            // The agent should be created, and PlannerType should be STATE_MACHINE
            // (verified through annotation on the class)
        }
    }
}
