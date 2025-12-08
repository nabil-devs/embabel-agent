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
package com.embabel.plan.statemachine

import com.embabel.agent.core.Action
import com.embabel.agent.core.Condition
import com.embabel.agent.core.Goal
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indentLines
import com.embabel.plan.Plan
import com.embabel.plan.PlanningSystem
import com.embabel.plan.WorldState
import java.lang.reflect.Method
import java.time.Instant

/**
 * Metadata about a single state in a state machine workflow.
 *
 * @param stateClass The class representing this state (an inner class of the workflow agent)
 * @param isInitial Whether this is the initial state
 * @param isTerminal Whether this is a terminal state
 * @param actions Actions defined within this state
 * @param conditions Conditions defined within this state
 * @param transitions Possible transitions from this state (inferred from action return types)
 */
data class StateMetadata(
    val stateClass: Class<*>,
    val isInitial: Boolean,
    val isTerminal: Boolean,
    val actions: List<Action>,
    val conditions: Set<Condition>,
    val transitions: List<TransitionMetadata>,
) : HasInfoString {

    val name: String get() = stateClass.simpleName

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            |State: $name
            |  initial: $isInitial
            |  terminal: $isTerminal
            |  actions: ${actions.map { it.name }}
            |  transitions: ${transitions.map { "${it.actionMethod.name} -> ${it.targetStateClass?.simpleName ?: "OUTPUT"}" }}
        """.trimMargin().indentLines(indent)
    }
}

/**
 * Metadata about a transition between states.
 *
 * @param actionMethod The method that triggers this transition
 * @param targetStateClass The target state class, or null if this transitions to final output
 * @param isTerminal Whether this transition produces the final workflow output
 */
data class TransitionMetadata(
    val actionMethod: Method,
    val targetStateClass: Class<*>?,
    val isTerminal: Boolean,
)

/**
 * Planning system for state machine workflows.
 * Contains metadata about all states and their relationships.
 */
data class StateMachinePlanningSystem(
    val workflowClass: Class<*>,
    val inputType: Class<*>,
    val outputType: Class<*>,
    val states: List<StateMetadata>,
    val initialState: StateMetadata,
    val terminalStates: List<StateMetadata>,
    val allStateClasses: Set<Class<*>>,
) : PlanningSystem, HasInfoString {

    override val actions: Set<Action>
        get() = states.flatMap { it.actions }.toSet()

    override val goals: Set<Goal>
        get() = emptySet() // Goals are implicit - reach terminal state and produce output

    override fun knownConditions(): Set<String> {
        return states.map { "state:${it.name}" }.toSet() +
                states.flatMap { it.conditions }.map { it.name }.toSet()
    }

    /**
     * Get the state metadata for a given state class.
     */
    fun getStateMetadata(stateClass: Class<*>): StateMetadata? {
        return states.find { it.stateClass == stateClass }
    }

    /**
     * Check if a class is a state class in this workflow.
     */
    fun isStateClass(clazz: Class<*>): Boolean {
        return allStateClasses.contains(clazz)
    }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            |StateMachinePlanningSystem: ${workflowClass.simpleName}
            |  input: ${inputType.simpleName}
            |  output: ${outputType.simpleName}
            |  states:
            |${states.joinToString("\n") { it.infoString(verbose, indent + 2) }}
        """.trimMargin().indentLines(indent)
    }
}

/**
 * World state for state machine planning.
 * Tracks the current state instance and its blackboard.
 */
data class StateMachineWorldState(
    val currentStateInstance: Any?,
    val currentStateClass: Class<*>?,
    val stateData: Map<String, Any?>,
    override val timestamp: Instant = Instant.now(),
) : WorldState {

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            |StateMachineWorldState:
            |  currentState: ${currentStateClass?.simpleName ?: "none"}
            |  stateData: $stateData
        """.trimMargin().indentLines(indent)
    }

    companion object {
        fun initial(): StateMachineWorldState {
            return StateMachineWorldState(
                currentStateInstance = null,
                currentStateClass = null,
                stateData = emptyMap(),
            )
        }
    }
}

/**
 * A plan in a state machine context.
 * Represents the sequence of states to traverse to reach the goal.
 */
class StateMachinePlan(
    val stateTransitions: List<StateTransition>,
    goal: Goal,
) : Plan(
    actions = stateTransitions.flatMap { it.actionsInState },
    goal = goal,
) {

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            |StateMachinePlan:
            |  transitions: ${stateTransitions.map { "${it.fromState?.simpleName ?: "START"} -> ${it.toState?.simpleName ?: "END"}" }}
        """.trimMargin().indentLines(indent)
    }
}

/**
 * Represents a single state transition within a plan.
 */
data class StateTransition(
    val fromState: Class<*>?,
    val toState: Class<*>?,
    val actionsInState: List<Action>,
    val transitionAction: Action?,
)
