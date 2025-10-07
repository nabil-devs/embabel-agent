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
package com.embabel.chat.agent

import com.embabel.agent.api.common.workflow.control.SimpleAgentBuilder
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.last
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class DefaultChatAgentBuilderTest {

    private val chatAgent = SimpleAgentBuilder
        .returning(AssistantMessage::class.java)
        .running({ context ->
            val conversation = context.last<Conversation>()
                ?: throw IllegalStateException("No conversation found in context")
            val userMessage = conversation.lastMessageMustBeFromUser()
                ?: throw IllegalStateException("Last message must be from user")
            AssistantMessage(
                name = "Test Agent",
                content = "Response to: ${userMessage.content}",
            )
        })
        .buildAgent("chatty", "A test agent for chat responses")

    @Test
    @Disabled("is this even valid?")
    fun `emits one message`() {
        val agentPlatform = dummyAgentPlatform()
        val cab = DefaultChatAgentBuilder(
            mockk(),
            llm = LlmOptions(),
            persona = K9,
        )
        val chatAgent = cab.build()
        val m = UserMessage("Hello")
        val conversation = InMemoryConversation.of(messages = listOf(m))
        val agentProcess = agentPlatform.runAgentFrom(
            agent = chatAgent,
            processOptions = ProcessOptions(),
            bindings = mapOf(
                "conversation" to conversation,
            )
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
    }


}
