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
package com.embabel.agent.api.annotation.support.nesting

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Subflow
import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.domain.io.UserInput

@Agent(description = "Two levels of nesting")
class TwoLevelNestingAgent {

    @Action
    fun start(userInput: UserInput): Level1 {
        val content = "LevelOne processing of: ${userInput.content}"
        return Level1(content)
    }

    @Subflow
    class Level1(val name: String) {

        @AchievesGoal(description = "Convert name to PersonWithReverseTool")
        @Action
        fun toL2(): Level2 {
            return Level2(PersonWithReverseTool(name))
        }
    }

    @Subflow
    class Level2(val person: PersonWithReverseTool) {

        // This should pop out to top level goal
        @Action
        @AchievesGoal(description = "Convert PersonWithReverseTool to Frog")
        fun toFrog(): Frog {
            return Frog(person.name)
        }
    }

    @Action
    @AchievesGoal(description = "Convert PersonWithReverseTool to Frog")
    fun end(frog: Frog): String {
        return "Received frog named: ${frog.name}"
    }
}
