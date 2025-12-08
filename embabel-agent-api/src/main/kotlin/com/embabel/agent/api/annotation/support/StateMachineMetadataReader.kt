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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.annotation.StateMachineWorkflow
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.plan.statemachine.StateMetadata
import com.embabel.plan.statemachine.StateMachinePlanningSystem
import com.embabel.plan.statemachine.TransitionMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import com.embabel.agent.core.Action as CoreAction

/**
 * Reads state machine metadata from classes annotated with @State inner classes.
 * Used for agents with PlannerType.STATE_MACHINE.
 */
@Component
class StateMachineMetadataReader(
    private val nameGenerator: MethodDefinedOperationNameGenerator = MethodDefinedOperationNameGenerator(),
) {

    private val logger = LoggerFactory.getLogger(StateMachineMetadataReader::class.java)

    /**
     * Check if an instance implements StateMachineWorkflow.
     */
    fun isStateMachineWorkflow(instance: Any): Boolean {
        return instance is StateMachineWorkflow<*, *>
    }

    /**
     * Find all inner classes annotated with @State in the given class.
     */
    fun findStateClasses(workflowClass: Class<*>): List<Class<*>> {
        return workflowClass.declaredClasses
            .filter { it.isAnnotationPresent(State::class.java) }
    }

    /**
     * Read state machine metadata from an instance.
     * @return StateMachinePlanningSystem or null if validation fails
     */
    fun readStateMachineMetadata(instance: Any): StateMachinePlanningSystem? {
        val workflowClass = instance.javaClass
        val stateClasses = findStateClasses(workflowClass)

        if (stateClasses.isEmpty()) {
            logger.warn("No @State classes found in {}", workflowClass.name)
            return null
        }

        // Extract input/output types from StateMachineWorkflow interface
        val (inputType, outputType) = extractWorkflowTypes(workflowClass)

        // Build state metadata for each state class
        val stateMetadataList = stateClasses.map { stateClass ->
            buildStateMetadata(stateClass, stateClasses, outputType, instance)
        }

        // Validate: exactly one initial state
        val initialStates = stateMetadataList.filter { it.isInitial }
        if (initialStates.isEmpty()) {
            logger.warn("No initial state found in {}. Exactly one @State(initial = true) is required.", workflowClass.name)
            return null
        }
        if (initialStates.size > 1) {
            logger.warn(
                "Multiple initial states found in {}: {}. Exactly one is required.",
                workflowClass.name,
                initialStates.map { it.name }
            )
            return null
        }

        // Validate: at least one terminal state
        val terminalStates = stateMetadataList.filter { it.isTerminal }
        if (terminalStates.isEmpty()) {
            logger.warn("No terminal state found in {}. At least one @State(terminal = true) is required.", workflowClass.name)
            return null
        }

        return StateMachinePlanningSystem(
            workflowClass = workflowClass,
            inputType = inputType,
            outputType = outputType,
            states = stateMetadataList,
            initialState = initialStates.first(),
            terminalStates = terminalStates,
            allStateClasses = stateClasses.toSet(),
        )
    }

    private fun extractWorkflowTypes(workflowClass: Class<*>): Pair<Class<*>, Class<*>> {
        // Find the StateMachineWorkflow interface in the type hierarchy
        val workflowInterface = workflowClass.genericInterfaces
            .filterIsInstance<ParameterizedType>()
            .find { it.rawType == StateMachineWorkflow::class.java }

        if (workflowInterface != null) {
            val typeArgs = workflowInterface.actualTypeArguments
            val inputType = (typeArgs.getOrNull(0) as? Class<*>) ?: Any::class.java
            val outputType = (typeArgs.getOrNull(1) as? Class<*>) ?: Any::class.java
            return Pair(inputType, outputType)
        }

        // Fallback to Any if not found
        return Pair(Any::class.java, Any::class.java)
    }

    private fun buildStateMetadata(
        stateClass: Class<*>,
        allStateClasses: List<Class<*>>,
        workflowOutputType: Class<*>,
        workflowInstance: Any,
    ): StateMetadata {
        val stateAnnotation = stateClass.getAnnotation(State::class.java)
        val actionMethods = findActionMethods(stateClass)

        val actions = actionMethods.map { method ->
            createActionForStateMethod(method, stateClass, workflowInstance)
        }

        val transitions = actionMethods.map { method ->
            buildTransitionMetadata(method, allStateClasses, workflowOutputType)
        }

        // A state is terminal if:
        // 1. It's explicitly marked as terminal, OR
        // 2. Any of its actions has @AchievesGoal (achieving a goal completes the workflow)
        val hasGoalAchievingAction = actionMethods.any { method ->
            method.isAnnotationPresent(AchievesGoal::class.java)
        }
        val isTerminal = stateAnnotation.terminal || hasGoalAchievingAction

        return StateMetadata(
            stateClass = stateClass,
            isInitial = stateAnnotation.initial,
            isTerminal = isTerminal,
            actions = actions,
            conditions = emptySet(), // Conditions within states can be added later
            transitions = transitions,
        )
    }

    private fun findActionMethods(stateClass: Class<*>): List<Method> {
        return stateClass.declaredMethods
            .filter { it.isAnnotationPresent(Action::class.java) }
    }

    private fun createActionForStateMethod(
        method: Method,
        stateClass: Class<*>,
        workflowInstance: Any,
    ): CoreAction {
        val actionAnnotation = method.getAnnotation(Action::class.java)
        val stateName = stateClass.simpleName
        val actionName = nameGenerator.generateName(workflowInstance, "$stateName.${method.name}")

        val inputs = method.parameters
            .filter { !isContextParameter(it.type) }
            .map { param ->
                IoBinding(
                    name = param.name,
                    type = param.type.name,
                )
            }.toSet()

        val inputClasses = method.parameters.map { it.type }

        return MultiTransformationAction(
            name = actionName,
            description = actionAnnotation.description.ifBlank { "${stateName}.${method.name}" },
            cost = { actionAnnotation.cost },
            inputs = inputs,
            canRerun = actionAnnotation.canRerun,
            pre = actionAnnotation.pre.toList(),
            post = actionAnnotation.post.toList(),
            inputClasses = inputClasses,
            outputClass = method.returnType,
            outputVarName = actionAnnotation.outputBinding,
            toolGroups = (actionAnnotation.toolGroupRequirements.map { ToolGroupRequirement(it.role) } +
                    actionAnnotation.toolGroups.map { ToolGroupRequirement(it) }).toSet(),
        ) { context ->
            // Execution will be handled by StateMachinePlanner which manages state instances
            throw UnsupportedOperationException(
                "State machine actions should be executed via StateMachinePlanner, not directly"
            )
        }
    }

    private fun isContextParameter(type: Class<*>): Boolean {
        // These are injected parameters, not domain inputs
        val contextTypes = setOf(
            "com.embabel.agent.api.common.OperationContext",
            "com.embabel.agent.api.common.ProcessContext",
            "com.embabel.agent.api.common.Ai",
            "com.embabel.agent.core.Blackboard",
        )
        return contextTypes.contains(type.name)
    }

    private fun buildTransitionMetadata(
        method: Method,
        allStateClasses: List<Class<*>>,
        workflowOutputType: Class<*>,
    ): TransitionMetadata {
        val returnType = method.returnType

        // Check if return type is one of the state classes
        val isStateTransition = allStateClasses.any { it == returnType || it.isAssignableFrom(returnType) }

        // Check if return type is the workflow output type (terminal transition)
        val isTerminalTransition = workflowOutputType == returnType ||
                workflowOutputType.isAssignableFrom(returnType)

        // Check if return type is a sealed interface that could be multiple states
        // In this case, the actual target is determined at runtime
        val isSealedOrInterface = returnType.isInterface || returnType.isSealed

        val targetStateClass = when {
            isTerminalTransition && !isStateTransition -> null // Terminal - produces output
            isSealedOrInterface && isStateTransition -> null // Runtime determined (sealed State interface)
            isStateTransition -> returnType // Direct state transition
            else -> null // Intermediate type for GOAP within state
        }

        return TransitionMetadata(
            actionMethod = method,
            targetStateClass = targetStateClass,
            isTerminal = isTerminalTransition && !isStateTransition,
        )
    }
}

/**
 * Extension property to check if a class is a Kotlin sealed class/interface.
 */
private val Class<*>.isSealed: Boolean
    get() = runCatching { this.kotlin.isSealed }.getOrDefault(false)
