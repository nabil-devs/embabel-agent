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
import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.domain.io.UserInput

@Agent(description = "Agent demonstrating states transitioning")
class StatesAgent {

    @Action
    fun takeUserInput(userInput: UserInput): com.embabel.agent.core.Agent {
        return agent(name = "UserInputProcessor", description = "Transforms user input into a person") {
            transformation<UserInput, PersonWithReverseTool>(
                name = "createPerson",
                description = "Create a person from user input",
            ) {
                PersonWithReverseTool(userInput.content)
            }

            goal(
                name = "PersonWithReverseTool",
                description = "A person has been created from user input",
                satisfiedBy = PersonWithReverseTool::class,
            )
        }
    }

    @AchievesGoal(description = "Turn a person into a frog")
    @Action
    fun turnIntoFrog(person: PersonWithReverseTool): Frog {
        return Frog("Frog version of ${person.name}")
    }

}
