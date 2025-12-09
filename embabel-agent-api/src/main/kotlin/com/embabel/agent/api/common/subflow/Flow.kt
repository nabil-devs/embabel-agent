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
package com.embabel.agent.api.common.subflow

/**
 * Marker interface for classes that contain @Action methods and can be
 * run as nested agents, but don't require a known output type.
 *
 * Use this interface when:
 * - Using Utility AI planner (which doesn't require goal-oriented planning)
 * - You want to return an action-containing class from an action
 * - You don't need to specify a specific output type for GOAP planning
 *
 * For GOAP planning where you need a known output type, use [FlowReturning] instead.
 *
 * Example:
 * ```kotlin
 * class ProcessingPhase(val data: String) : Flow {
 *     @Action
 *     fun processData(): Result {
 *         return Result(data.uppercase())
 *     }
 * }
 * ```
 *
 * @see FlowReturning for goal-oriented workflows with known output types
 */
interface Flow
