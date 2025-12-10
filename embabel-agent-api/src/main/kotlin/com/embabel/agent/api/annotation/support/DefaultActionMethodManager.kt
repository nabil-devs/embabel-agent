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

import com.embabel.agent.api.annotation.Agentic
import com.embabel.agent.api.annotation.AwaitableResponseException
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.subflow.FlowReturning
import com.embabel.agent.api.common.support.FlowNestingManager
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.core.KotlinDetector
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction

/**
 * Implementation that creates dummy instances of domain objects to discover tools,
 * before re-reading the tool callbacks from the actual domain object instances at invocation time.
 */
@Component
internal class DefaultActionMethodManager(
    val nameGenerator: MethodDefinedOperationNameGenerator = MethodDefinedOperationNameGenerator(),
    val argumentResolvers: List<ActionMethodArgumentResolver> = listOf(
        ProcessContextArgumentResolver(),
        OperationContextArgumentResolver(),
        AiArgumentResolver(),
        BlackboardArgumentResolver(),
    ),
) : ActionMethodManager {

    private val logger = LoggerFactory.getLogger(DefaultActionMethodManager::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun createAction(
        method: Method,
        instance: Any,
        toolCallbacksOnInstance: List<ToolCallback>,
    ): Action {
        requireNonAmbiguousParameters(method)
        val actionAnnotation = method.getAnnotation(com.embabel.agent.api.annotation.Action::class.java)
        val inputClasses = method.parameters
            .map { it.type }
        val inputs = resolveInputBindings(method)

        require(method.returnType != null) { "Action method ${method.name} must have a return type" }

        // Check if the return type is a Workflow or AgentScope - if so, resolve the actual output type
        val outputClass = resolveOutputClass(method, instance)

        return MultiTransformationAction(
            name = nameGenerator.generateName(instance, method.name),
            description = actionAnnotation.description.ifBlank { method.name },
            cost = { actionAnnotation.cost },
            inputs = inputs.toSet(),
            canRerun = actionAnnotation.canRerun,
            pre = actionAnnotation.pre.toList(),
            post = actionAnnotation.post.toList(),
            inputClasses = inputClasses,
            outputClass = outputClass,
            outputVarName = actionAnnotation.outputBinding,
            toolGroups = (actionAnnotation.toolGroupRequirements.map { ToolGroupRequirement(it.role) } + actionAnnotation.toolGroups.map {
                ToolGroupRequirement(
                    it
                )
            }).toSet(),
        ) { context ->
            invokeActionMethod(
                method = method,
                instance = instance,
                actionContext = context,
            )
        }
    }

    /**
     * Resolve the effective output class for an action method.
     * - If the return type implements FlowReturning<O>, extract O from the generic type.
     * - If the return type is AgentScope/Agent, invoke the method to get the instance and
     *   extract the output type from its single business goal.
     * - If the return type is @Agentic (e.g., @Subflow), extract the output type from its goals.
     * - Otherwise, return the method's return type directly.
     */
    private fun resolveOutputClass(method: Method, instance: Any): Class<*> {
        val returnType = method.returnType

        // Handle FlowReturning<O> - extract O from generic type
        if (FlowReturning::class.java.isAssignableFrom(returnType)) {
            return resolveFlowReturningOutputClass(returnType)
        }

        // Handle AgentScope/Agent - invoke method to get instance and extract output from goals
        if (AgentScope::class.java.isAssignableFrom(returnType)) {
            return resolveAgentScopeOutputClass(method, instance) ?: returnType
        }

        // Handle @Agentic classes (e.g., @Subflow) - extract output from goals
        if (isAgenticClass(returnType)) {
            return resolveAgenticClassOutputType(returnType) ?: returnType
        }

        return returnType
    }

    /**
     * Check if a class has @Agentic annotation (directly or via meta-annotation like @Subflow, @Agent).
     */
    private fun isAgenticClass(clazz: Class<*>): Boolean {
        // Check for direct @Agentic
        if (clazz.isAnnotationPresent(Agentic::class.java)) {
            return true
        }
        // Check for meta-annotation
        return clazz.annotations.any { annotation ->
            annotation.annotationClass.java.isAnnotationPresent(Agentic::class.java)
        }
    }

    /**
     * Extract the output type from an @Agentic class (e.g., @Subflow) by analyzing its goals.
     * If the class has exactly one business goal with an outputType, return that type.
     *
     * If the output type is itself an @Agentic class, recursively resolve to get the final output type.
     * This enables multi-level nesting where Level1 -> Level2 -> Frog resolves to Frog.
     */
    private fun resolveAgenticClassOutputType(clazz: Class<*>): Class<*>? {
        return resolveAgenticClassOutputTypeRecursive(clazz, mutableSetOf())
    }

    private fun resolveAgenticClassOutputTypeRecursive(clazz: Class<*>, visited: MutableSet<Class<*>>): Class<*>? {
        // Prevent infinite recursion
        if (clazz in visited) {
            logger.debug("Cycle detected in @Agentic class resolution: {}", clazz.simpleName)
            return null
        }
        visited.add(clazz)

        try {
            // Create a dummy instance to read metadata
            val dummyInstance = createDummyInstance(clazz)
            if (dummyInstance == null) {
                logger.debug("Could not create dummy instance for @Agentic class {}", clazz.simpleName)
                return null
            }
            val metadata = AgentMetadataReader().createAgentMetadata(dummyInstance)
            if (metadata == null) {
                logger.debug("Could not create metadata for @Agentic class {}", clazz.simpleName)
                return null
            }

            val outputType = FlowNestingManager.DEFAULT.getOutputType(metadata)
            if (outputType == null) {
                logger.debug(
                    "Could not determine output type from @Agentic class {}, using class directly",
                    clazz.simpleName
                )
                return null
            }

            // If the output type is itself @Agentic, recursively resolve
            if (isAgenticClass(outputType)) {
                logger.debug(
                    "@Agentic class {} has output type {} which is also @Agentic, resolving recursively",
                    clazz.simpleName,
                    outputType.simpleName
                )
                val recursiveResult = resolveAgenticClassOutputTypeRecursive(outputType, visited)
                if (recursiveResult != null) {
                    logger.debug(
                        "Resolved @Agentic chain {} -> {} -> {}",
                        clazz.simpleName,
                        outputType.simpleName,
                        recursiveResult.simpleName
                    )
                    return recursiveResult
                }
            }

            logger.debug(
                "Action returns @Agentic class {} with goal output type {}, using {} as action output type",
                clazz.simpleName,
                outputType.simpleName,
                outputType.simpleName
            )
            return outputType
        } catch (e: Exception) {
            logger.debug(
                "Could not analyze @Agentic class {} to determine output type: {}",
                clazz.simpleName,
                e.message
            )
            return null
        }
    }

    /**
     * Extract the output type from a FlowReturning class by inspecting its generic type parameter.
     */
    private fun resolveFlowReturningOutputClass(returnType: Class<*>): Class<*> {
        // Find the Workflow interface in the class hierarchy and extract its type argument
        for (genericInterface in returnType.genericInterfaces) {
            if (genericInterface is ParameterizedType &&
                FlowReturning::class.java.isAssignableFrom(genericInterface.rawType as Class<*>)
            ) {
                val typeArg = genericInterface.actualTypeArguments.firstOrNull()
                if (typeArg is Class<*>) {
                    logger.debug(
                        "Action returns Workflow<{}>, using {} as output type",
                        typeArg.simpleName,
                        typeArg.simpleName
                    )
                    return typeArg
                }
            }
        }

        // If we couldn't extract the type argument, just use the workflow class
        logger.debug("Could not extract output type from Workflow, using {} directly", returnType.simpleName)
        return returnType
    }

    /**
     * Extract the output type from an AgentScope-returning method by invoking it with dummy arguments.
     * The output type is determined by the single business goal's satisfiedBy type.
     */
    private fun resolveAgentScopeOutputClass(method: Method, instance: Any): Class<*>? {
        try {
            // Create dummy arguments for the method invocation
            val args = method.parameters.map { param ->
                createDummyInstance(param.type)
            }.toTypedArray()

            method.isAccessible = true
            val agentScope = method.invoke(instance, *args) as? AgentScope
            if (agentScope == null) {
                logger.debug("Method {} did not return an AgentScope", method.name)
                return null
            }

            val outputType = FlowNestingManager.DEFAULT.getOutputType(agentScope)
            if (outputType != null) {
                logger.debug(
                    "Action {} returns AgentScope with goal output type {}, using {} as action output type",
                    method.name,
                    outputType.simpleName,
                    outputType.simpleName
                )
            } else {
                logger.debug(
                    "Could not determine output type from AgentScope returned by {}, using AgentScope directly",
                    method.name
                )
            }
            return outputType
        } catch (e: Exception) {
            logger.debug(
                "Could not invoke method {} to determine AgentScope output type: {}",
                method.name,
                e.message
            )
            return null
        }
    }

    /**
     * Create a dummy instance of the given type for method invocation during metadata discovery.
     * This is used to invoke action methods that return AgentScope to discover their output types.
     */
    private fun createDummyInstance(type: Class<*>): Any? {
        return when {
            type == String::class.java -> ""
            type == Int::class.java || type == java.lang.Integer::class.java -> 0
            type == Long::class.java || type == java.lang.Long::class.java -> 0L
            type == Double::class.java || type == java.lang.Double::class.java -> 0.0
            type == Float::class.java || type == java.lang.Float::class.java -> 0.0f
            type == Boolean::class.java || type == java.lang.Boolean::class.java -> false
            type.isEnum -> type.enumConstants?.firstOrNull()
            else -> try {
                // Try to create an instance using the primary constructor with dummy values
                val kClass = type.kotlin
                val constructor = kClass.primaryConstructor ?: kClass.constructors.firstOrNull()
                if (constructor != null) {
                    val argMap = constructor.parameters
                        .filter { !it.isOptional }
                        .associateWith { param ->
                            val paramClass = param.type.classifier as? KClass<*>
                            createDummyInstance(paramClass?.java ?: Any::class.java)
                        }
                    constructor.callBy(argMap)
                } else {
                    type.getDeclaredConstructor().newInstance()
                }
            } catch (e: Exception) {
                logger.trace("Could not create dummy instance of {}: {}", type.name, e.message)
                null
            }
        }
    }

    private fun resolveInputBindings(
        javaMethod: Method,
    ): Set<IoBinding> {
        val result = mutableSetOf<IoBinding>()
        val kotlinFunction = if (KotlinDetector.isKotlinReflectPresent()) javaMethod.kotlinFunction else null
        for (i in javaMethod.parameters.indices) {
            val javaParameter = javaMethod.parameters[i]
            val kotlinParameter = kotlinFunction?.valueParameters?.getOrNull(i)
            for (argumentResolver in argumentResolvers) {
                if (argumentResolver.supportsParameter(javaParameter, kotlinParameter, null)) {
                    result += argumentResolver.resolveInputBinding(javaParameter, kotlinParameter)
                    break
                }
            }
        }
        return result
    }

    override fun <O> invokeActionMethod(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        logger.debug("Invoking action method {} with payload {}", method.name, actionContext.input)
        val result = if (KotlinDetector.isKotlinReflectPresent()) {
            val kFunction = method.kotlinFunction
            if (kFunction != null) invokeActionMethodKotlinReflect(method, kFunction, instance, actionContext)
            else invokeActionMethodJavaReflect(method, instance, actionContext)
        } else {
            invokeActionMethodJavaReflect(method, instance, actionContext)
        }
        logger.debug(
            "Result of invoking action method {} was {}: payload {}",
            method.name,
            result,
            actionContext.input
        )
        return result
    }

    private fun <O> invokeActionMethodKotlinReflect(
        method: Method,
        kFunction: KFunction<*>,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = arrayOfNulls<Any?>(method.parameters.size + 1)
        args[0] = instance
        for (i in method.parameters.indices) {
            val javaParameter = method.parameters[i]
            val kotlinParameter = kFunction.valueParameters.getOrNull(i)
            val classifier = kotlinParameter?.type?.classifier
            if (classifier is KClass<*>) {
                for (argumentResolver in argumentResolvers) {
                    if (argumentResolver.supportsParameter(javaParameter, kotlinParameter, actionContext)) {
                        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, actionContext)
                        if (arg == null) {
                            val isNullable = kotlinParameter.isOptional || kotlinParameter.type.isMarkedNullable
                            if (!isNullable) {
                                error("Action ${actionContext.action.name}: Internal error. No value found in blackboard for non-nullable parameter ${kotlinParameter.name}:${classifier.java.name}")
                            }
                        }
                        args[i + 1] = arg
                    }
                }
            }
        }

        val result = try {
            try {
                kFunction.isAccessible = true
                kFunction.call(*args)
            } catch (ite: InvocationTargetException) {
                ReflectionUtils.handleInvocationTargetException(ite)
            }
        } catch (awe: AwaitableResponseException) {
            handleAwaitableResponseException(instance.javaClass.name, kFunction.name, awe)
        } catch (t: Throwable) {
            handleThrowable(instance.javaClass.name, kFunction.name, t)
        }
        return result as O
    }

    private fun <O> invokeActionMethodJavaReflect(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = arrayOfNulls<Any?>(method.parameters.size)
        for (i in method.parameters.indices) {
            val parameter = method.parameters[i]
            for (argumentResolver in argumentResolvers) {
                if (argumentResolver.supportsParameter(parameter, null, actionContext)) {
                    val arg = argumentResolver.resolveArgument(parameter, null, actionContext)
                    args[i] = arg
                }
            }
        }

        val result = try {
            method.trySetAccessible()
            ReflectionUtils.invokeMethod(method, instance, *args)
        } catch (awe: AwaitableResponseException) {
            handleAwaitableResponseException(instance.javaClass.name, method.name, awe)
        } catch (t: Throwable) {
            handleThrowable(instance.javaClass.name, method.name, t)
        }
        return result as O
    }

    private fun handleAwaitableResponseException(
        instanceName: String,
        methodName: String,
        awe: AwaitableResponseException,
    ) {
        // This is not a failure, but will drive transition to a wait state
        logger.info(
            "Action method {}.{} entering wait state: {}",
            instanceName,
            methodName,
            awe.message,
        )
        throw awe
    }

    private fun handleThrowable(
        instanceName: String,
        methodName: String,
        t: Throwable,
    ) {
        logger.warn(
            "Error invoking action method {}.{}: {}",
            instanceName,
            methodName,
            t.message,
        )
        throw t
    }

}
