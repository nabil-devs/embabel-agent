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
package com.embabel.agent.api.annotation

import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.IoBinding
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import com.embabel.common.core.types.ZeroToOne
import org.springframework.core.annotation.AliasFor
import org.springframework.stereotype.Component


/**
 * Indicates that this class exposes actions, goals and conditions that may be used
 * by agents, but is not an agent in itself.
 * This is a Spring stereotype annotation, so annotated classes will be picked up on the classpath and injected
 * @param scan Whether to find this agent in the classpath. If false, it will not be found by classpath scanning.
 * This is useful for testing
 * [com.embabel.agent.api.annotation.support.AgentMetadataReader] will still process it if asked directly.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
)
@Component
annotation class EmbabelComponent(
    val scan: Boolean = true,
)

/**
 * Indicates that this class is an agent.
 * It doesn't just contribute actions, goals and conditions:
 * it is an agent in itself.
 * This is a Spring stereotype annotation, so annotated classes will be picked up on the classpath and injected
 * Either @Agent or @AgentCapabilities should be used: not both
 * @param name Name of the agent. If not provided, the name will be the class simple name
 * @param provider provider of the agent. If not provided, will default to the package this annotation is used in
 * @param description Description of the agent. Required. This is used for documentation purposes and to choose an agent
 * @param version Version of the agent
 * @param planner The type of planning this agent uses. Defaults to GOAP (Goal Oriented Action Planning).
 * @param scan Whether to find this agent in the classpath. If false, it will not be found by the agent manager. Defaults to true
 * @param beanName The value may indicate a suggestion for a logical component name,
 * to be turned into a Spring bean in case of an autodetected component. Use only if there's the likelihood of
 * conflict with the default bean name.
 * @param opaque Whether to hide the agent's actions and conditions
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
)
@Component
annotation class Agent(
    val name: String = "",
    val provider: String = "",
    val description: String,
    val version: String = DEFAULT_VERSION,
    val planner: PlannerType = PlannerType.GOAP,
    val scan: Boolean = true,
    @get:AliasFor(annotation = Component::class, attribute = "value")
    val beanName: String = "",
    val opaque: Boolean = false,
)

/**
 * Annotates a method that evaluates a condition.
 * This will have access to the processContext and also
 * can use any other state.
 * @param name Name of the condition. If not provided, the name will be the method name
 * Useful if we want to avoid magic strings by sharing a constant
 * @param cost Cost of evaluating the condition, between 0 and 1.
 * 0 is cheap; 1 is the most expensive. The platform can use this
 * information for optimization.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Condition(
    val name: String = "",
    val cost: ZeroToOne = 0.0,
)

@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ToolGroup(
    val role: String,
)

/**
 * Annotation to indicate a method implementing an Action.
 * Methods can have any number of parameters, which represent
 * necessary input types.
 * Methods can return any type. The return type will become
 * an effect.
 * @param description Description of the action. Less important than for
 * goals as a planner chooses actions based on preconditions
 * and effects rather than by description. The description property is
 * used for documentation purposes, having the advantage over comments
 * that it can appear in logs. Description defaults to name
 * @param pre Preconditions for the action
 * @param outputBinding Output binding for the action.
 * Only required for a custom binding: a specific variable name for the returned value.
 * @param cost Cost of executing the action
 * @param value Value of performing the action
 * @param toolGroups Tool groups that this action requires. These are well known tools from the server.
 * @param toolGroupRequirements Tool groups required, with explicit metadata such as QoS requirements.
 * @Tool methods on the @Agentic class are automatically added.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Action(
    val description: String = "",
    val pre: Array<String> = [],
    val post: Array<String> = [],
    val canRerun: Boolean = false,
    val outputBinding: String = IoBinding.DEFAULT_BINDING,
    val cost: ZeroToOne = 0.0,
    val value: ZeroToOne = 0.0,
    val toolGroups: Array<String> = [],
    val toolGroupRequirements: Array<ToolGroup> = [],
)


/**
 * Annotation that can added to parameters of an @Action method
 * to indicate that the parameter name must match the input binding.
 * Otherwise, it can match the latest ("it") value.
 * Must be combined with the outputBinding method on Action for the action
 * producing the input
 * @param value The name of the input binding that this parameter should match; "" indicates using the parameter name.
 * @see Action
 * @see IoBinding
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequireNameMatch(
    val value: String = "",
)

/**
 * Marks an inner class as a state in a state machine workflow.
 * Used with [PlannerType.STATE_MACHINE] agents.
 *
 * State classes should:
 * - Be inner classes of the agent class
 * - Have constructor parameters for state data
 * - Contain @Action methods that either:
 *   - Return intermediate types (GOAP plans within state)
 *   - Return another @State class instance (triggers transition)
 *   - Return the workflow's output type (completes workflow)
 *
 * Example:
 * ```kotlin
 * @Agent(description = "Review workflow", planner = PlannerType.STATE_MACHINE)
 * class ReviewAgent : StateMachineWorkflow<UserInput, ReviewedStory> {
 *
 *     @State(initial = true)
 *     inner class Drafting {
 *         @Action
 *         fun draft(input: UserInput, ai: Ai): Reviewing {
 *             val story = ai.createObject("Write story", Story::class.java)
 *             return Reviewing(story)
 *         }
 *     }
 *
 *     @State
 *     inner class Reviewing(val story: Story) {
 *         @Action
 *         fun review(ai: Ai): State {
 *             // ... returns Done or Revising
 *         }
 *     }
 *
 *     @State // terminal inferred from @AchievesGoal
 *     inner class Done(val story: Story) {
 *         @Action
 *         @AchievesGoal(description = "Story complete")
 *         fun finalize(): ReviewedStory = ReviewedStory(story)
 *     }
 * }
 * ```
 *
 * @param initial Whether this is the initial state. Exactly one state must be marked as initial.
 * @param terminal Whether this is a terminal state. Can be omitted if the state has an action
 *        with @[AchievesGoal], which automatically makes it terminal.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class State(
    val initial: Boolean = false,
    val terminal: Boolean = false,
)

/**
 * Marker interface for state machine workflows.
 * Classes implementing this interface should use [PlannerType.STATE_MACHINE]
 * and define inner classes annotated with @[State].
 *
 * @param INPUT The input type that starts the workflow
 * @param OUTPUT The output type produced when the workflow completes
 */
interface StateMachineWorkflow<INPUT, OUTPUT>
