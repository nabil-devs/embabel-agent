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
package com.embabel.agent.api.common.workflow

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessContext
import org.slf4j.LoggerFactory

/**
 * Detects when an action returns a Workflow instance and runs it as a nested sub-agent.
 *
 * This enables composition where an action can return an instance of a class
 * implementing Workflow to enter a nested GOAP flow. The nested flow runs to
 * completion, and its result becomes available on the blackboard.
 */
class WorkflowRunner(
    private val agentMetadataReader: AgentMetadataReader = AgentMetadataReader(),
) {

    private val logger = LoggerFactory.getLogger(WorkflowRunner::class.java)

    /**
     * Check if the given object is a Workflow that can be run as a nested agent.
     * This requires the object to implement Workflow and have @Action methods.
     */
    fun isWorkflow(obj: Any): Boolean {
        if (obj !is Workflow<*>) {
            return false
        }
        // Verify it has @Action methods that can be executed
        val metadata = agentMetadataReader.createAgentMetadata(obj)
        return metadata != null && metadata.actions.isNotEmpty()
    }

    /**
     * Run the workflow as a nested sub-agent within the given process context.
     * Returns the result of the nested agent execution, or null if the workflow
     * could not be run as an agent.
     *
     * @param workflow The workflow instance
     * @param processContext The parent process context
     * @return The result of the nested agent, or null
     */
    fun runWorkflow(
        workflow: Workflow<*>,
        processContext: ProcessContext,
    ): Any? {
        val workflowClass = workflow::class.java
        logger.debug("Attempting to run workflow as nested agent: {}", workflowClass.name)

        // Warn if this is an inner class (non-static nested class) as it holds a reference
        // to the enclosing instance, which can cause issues with workflow persistence
        if (isInnerClass(workflowClass)) {
            logger.warn(
                "Workflow class '{}' is an inner class (non-static). This may cause issues with " +
                    "workflow persistence. Consider making it a nested class (static) or a top-level class.",
                workflowClass.name
            )
        }

        // Create agent metadata from the workflow instance
        val agentScope = agentMetadataReader.createAgentMetadata(workflow)
        if (agentScope == null) {
            logger.warn("Could not create agent metadata from workflow: {}", workflowClass.name)
            return null
        }

        // Convert the scope to an agent
        val agent = agentScope.createAgent(
            name = agentScope.name,
            provider = workflowClass.`package`?.name ?: "",
            description = agentScope.description,
        )

        logger.info(
            "Running nested workflow agent: {} with {} actions",
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
     * Process an action output - if it's a Workflow, run it and return the nested result.
     * Otherwise return the original output.
     */
    fun processOutput(
        output: Any,
        processContext: ProcessContext,
    ): Any {
        if (output !is Workflow<*>) {
            return output
        }

        if (!isWorkflow(output)) {
            return output
        }

        logger.debug("Output is a workflow, running as nested agent: {}", output::class.java.name)
        val nestedResult = runWorkflow(output, processContext)

        // If the nested result is also a workflow, recurse
        if (nestedResult != null && nestedResult is Workflow<*> && isWorkflow(nestedResult)) {
            return processOutput(nestedResult, processContext)
        }

        return nestedResult ?: output
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
        return clazz.isMemberClass && !java.lang.reflect.Modifier.isStatic(clazz.modifiers)
    }

    companion object {
        /**
         * Default instance for convenience.
         */
        val DEFAULT = WorkflowRunner()
    }
}
