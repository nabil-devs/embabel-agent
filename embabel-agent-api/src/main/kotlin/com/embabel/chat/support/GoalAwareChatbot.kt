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
package com.embabel.chat.support

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.channel.OutputChannel
import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.identity.User
import com.embabel.agent.spi.ContextRepository
import com.embabel.chat.*
import com.embabel.chat.agent.DefaultChatAgentBuilder
import com.embabel.common.ai.model.LlmOptions

/*
 * A chatbot that uses an agent to respond to messages.
 */
class GoalAwareChatbot(
    private val contextRepository: ContextRepository,
    private val autonomy: Autonomy,
) : Chatbot {

    private val chatAgent: Agent = DefaultChatAgentBuilder(
        llm = LlmOptions(),
        autonomy = autonomy,
    ).build()

    override fun createSession(
        user: User?,
        outputChannel: OutputChannel,
        systemMessage: String?,
    ): ChatSession {
        val context = contextRepository.create()
        val conversation = InMemoryConversation(id = context.id)
        context.addObject(conversation)
        contextRepository.save(context)
        val session = SimpleChatSession(user = user, outputChannel = outputChannel, conversation = conversation)
        return session
    }

    override fun findSession(conversationId: String): ChatSession? {
        return contextRepository.findById(conversationId)?.let { context ->
            val conversation = context.last(Conversation::class.java)
                ?: error("Conversation not found in context ${context.id}")
            val user = context.last(User::class.java)
            SimpleChatSession(user = user, outputChannel = TODO(), conversation = conversation)
        }
    }

    internal inner class SimpleChatSession(
        override val outputChannel: OutputChannel,
        override val user: User?,
        override val conversation: Conversation,
    ) : ChatSession {

        override fun onUserMessage(
            userMessage: UserMessage,
        ) {
            conversation.addMessage(userMessage)
            val agentProcess = autonomy.agentPlatform.runAgentFrom(
                agent = chatAgent,
                processOptions = ProcessOptions(),
                bindings = emptyMap(),
            )
            val assistantMessage = agentProcess.lastResult() as? AssistantMessage
                ?: AssistantMessage("Internal error: Agent did not return an AssistantMessage")
            conversation.addMessage(assistantMessage)
//            messageListener.onMessage(assistantMessage, conversation)
            TODO()
        }

    }
}
