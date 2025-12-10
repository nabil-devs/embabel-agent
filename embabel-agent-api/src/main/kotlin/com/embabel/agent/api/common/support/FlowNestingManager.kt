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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.annotation.Agentic
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.subflow.FlowReturning
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessContext
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

/**
 * Detects when an action returns a FlowReturning or @Agentic-annotated instance and runs it as a nested sub-agent.
 *
 * This enables composition where an action can return an instance of a class
 * implementing FlowReturning or annotated with @Agentic (or its meta-annotated variants like @Agent or @Subflow)
 * to enter a nested flow. The nested flow runs to completion, and its result becomes available on the blackboard.
 *
 * For GOAP planning, classes must implement [com.embabel.agent.api.common.subflow.FlowReturning] to specify their output type.
 * For Utility AI planning, classes can implement [com.embabel.agent.api.common.subflow.FlowReturning] or have
 * the @Agentic annotation (directly or via @Agent, @Subflow, @EmbabelComponent) since no goal-oriented planning is needed.
 */
internal class FlowNestingManager(
    private val agentMetadataReader: AgentMetadataReader = AgentMetadataReader(),
) {

    private val logger = LoggerFactory.getLogger(FlowNestingManager::class.java)

    /**
     * Check if the given object is a FlowReturning that can be run as a nested agent.
     * This requires the object to implement FlowReturning and have @Action methods.
     */
    fun isFlowReturning(obj: Any): Boolean {
        if (obj !is FlowReturning<*>) {
            return false
        }
        // Verify it has @Action methods that can be executed
        val metadata = agentMetadataReader.createAgentMetadata(obj)
        return metadata != null && metadata.actions.isNotEmpty()
    }

    /**
     * Check if the given object is an @Agentic-annotated class that can be run as a nested agent.
     * This includes classes annotated with @Agent, @Subflow, @EmbabelComponent, or directly with @Agentic.
     */
    fun isSubflow(obj: Any): Boolean {
        if (!hasAgenticAnnotation(obj.javaClass)) {
            return false
        }
        // Verify it has @Action methods that can be executed
        val metadata = agentMetadataReader.createAgentMetadata(obj)
        return metadata != null && metadata.actions.isNotEmpty()
    }

    /**
     * Check if a class has the @Agentic annotation (directly or as a meta-annotation on its annotations).
     * This includes @Agent, @Subflow, @EmbabelComponent, and any other annotation meta-annotated with @Agentic.
     */
    private fun hasAgenticAnnotation(clazz: Class<*>): Boolean {
        // Check for direct @Agentic annotation
        if (clazz.isAnnotationPresent(Agentic::class.java)) {
            return true
        }
        // Check for meta-annotation (e.g., @Agent, @Subflow, @EmbabelComponent which have @Agentic)
        return clazz.annotations.any { annotation ->
            annotation.annotationClass.java.isAnnotationPresent(Agentic::class.java)
        }
    }

    /**
     * Check if the given object can be run as a nested agent based on the planner type.
     * - For FlowReturning: always runnable (provides output type for GOAP)
     * - For @Subflow classes: only runnable with Utility AI (no output type needed)
     */
    fun isRunnableNestedAgent(
        obj: Any,
        plannerType: PlannerType,
    ): Boolean {
        return isFlowReturning(obj) ||
            (plannerType == PlannerType.UTILITY && isSubflow(obj))
    }

    /**
     * Run the FlowReturning as a nested sub-agent within the given process context.
     * Returns the result of the nested agent execution, or null if the workflow
     * could not be run as an agent.
     *
     * @param flowReturning The FlowReturning instance
     * @param processContext The parent process context
     * @return The result of the nested agent, or null
     */
    fun runFlowReturning(
        flowReturning: FlowReturning<*>,
        processContext: ProcessContext,
    ): Any? {
        return runNestedAgent(flowReturning, processContext, "workflow")
    }

    /**
     * Run a @Subflow-annotated class as a nested sub-agent within the given process context.
     * This is typically used with Utility AI where no output type is needed.
     * Returns the result of the nested agent execution, or null if the subflow
     * could not be run as an agent.
     *
     * @param subflow The @Subflow-annotated instance
     * @param processContext The parent process context
     * @return The result of the nested agent, or null
     */
    fun runSubflow(
        subflow: Any,
        processContext: ProcessContext,
    ): Any? {
        return runNestedAgent(subflow, processContext, "subflow")
    }

    /**
     * Internal method to run any object with @Action methods as a nested agent.
     */
    private fun runNestedAgent(
        instance: Any,
        processContext: ProcessContext,
        typeLabel: String,
    ): Any? {
        val instanceClass = instance::class.java
        logger.debug("Attempting to run {} as nested agent: {}", typeLabel, instanceClass.name)

        // Warn if this is an inner class (non-static nested class) as it holds a reference
        // to the enclosing instance, which can cause issues with workflow persistence
        if (isInnerClass(instanceClass)) {
            logger.warn(
                "{} class '{}' is an inner class (non-static). This may cause issues with " +
                    "workflow persistence. Consider making it a nested class (static) or a top-level class.",
                typeLabel.replaceFirstChar { it.uppercase() },
                instanceClass.name
            )
        }

        // Create agent metadata from the instance
        val agentMetadata = agentMetadataReader.createAgentMetadata(instance)
        if (agentMetadata == null) {
            logger.warn("Could not create agent metadata from {}: {}", typeLabel, instanceClass.name)
            return null
        }

        // Convert the scope to an agent
        val agent = agentMetadata.createAgent(
            name = agentMetadata.name,
            provider = instanceClass.`package`?.name ?: "",
            description = agentMetadata.description,
        )

        logger.info(
            "Running nested {} agent: {} with {} actions",
            typeLabel,
            agent.name,
            agent.actions.size
        )

        // Run the agent as a child process
        val childAgentProcess = processContext.platformServices.agentPlatform.createChildProcess(
            agent = agent,
            parentAgentProcess = processContext.agentProcess,
        )

        val childProcessResult = childAgentProcess.run()

        // Return the last result from the child process
        if (childProcessResult.status != AgentProcessStatusCode.COMPLETED) {
            logger.warn(
                "Child process did not complete: {} - status: {}",
                agent.name,
                childProcessResult.status
            )
            return null
        }

        return childProcessResult.lastResult()
    }

    /**
     * Process an action output - if it's a FlowReturning or @Subflow class, run it and return the nested result.
     * Otherwise, return the original output.
     *
     * FlowReturning instances are always processed.
     * @Subflow instances are only processed when the planner type is UTILITY,
     * as they don't provide an output type needed for GOAP planning.
     */
    fun processOutput(
        output: Any,
        processContext: ProcessContext,
    ): Any {
        val plannerType = processContext.processOptions.plannerType

        // Handle FlowReturning (always processable)
        if (output is FlowReturning<*> && isFlowReturning(output)) {
            logger.debug("Output is a FlowReturning, running as nested agent: {}", output::class.java.name)
            val nestedResult = runFlowReturning(output, processContext)

            // If the nested result is also runnable, recurse
            if (nestedResult != null && isRunnableNestedAgent(nestedResult, plannerType)) {
                return processOutput(nestedResult, processContext)
            }

            return nestedResult ?: output
        }

        // Handle @Subflow class (only with Utility AI)
        if (plannerType == PlannerType.UTILITY && isSubflow(output)) {
            logger.debug("Output is a @Subflow class (Utility AI), running as nested agent: {}", output::class.java.name)
            val nestedResult = runSubflow(output, processContext)

            // If the nested result is also runnable, recurse
            if (nestedResult != null && isRunnableNestedAgent(nestedResult, plannerType)) {
                return processOutput(nestedResult, processContext)
            }

            return nestedResult ?: output
        }

        return output
    }

    /**
     * Check if a class is an inner class (non-static nested class).
     * Inner classes hold a reference to their enclosing instance, which can
     * cause serialization and persistence issues.
     */
    private fun isInnerClass(clazz: Class<*>): Boolean {
        // A class is an inner class if it's a member class but not static
        // In Java reflection, isMemberClass() returns true for both static nested and inner classes
        // We need to check if it's NOT static (doesn't have Modifier.STATIC)
        return clazz.isMemberClass && !Modifier.isStatic(clazz.modifiers)
    }

    companion object {
        /**
         * Default instance for convenience.
         */
        val DEFAULT = FlowNestingManager()
    }
}
