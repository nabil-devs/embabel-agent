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
package com.embabel.agent.api.common

/**
 * Specifies the type of planner that an agent uses.
 */
enum class PlannerType {

    /**
     * Goal Oriented Action Planning.
     * This is the default planner.
     * It uses goals, actions and conditions to plan actions.
     */
    GOAP,

    /**
     * Utility AI planning.
     * This planner uses utility functions to evaluate actions.
     */
    UTILITY,

    /**
     * State machine planner.
     * Uses @State inner classes to define states in a workflow.
     * GOAP plans within each state; transitions between states
     * are determined by action return types.
     * States are inner classes that hold state-specific data and @Action methods.
     * Returning a different state type from an action triggers a transition.
     */
    STATE_MACHINE,
}
