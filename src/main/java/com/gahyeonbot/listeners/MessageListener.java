package com.gahyeonbot.listeners;

import com.gahyeonbot.adapters.discord.DiscordIdentityMapper;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationRejectedException;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;
import com.gahyeonbot.services.ai.agent.AgentApprovalRequiredException;
import com.gahyeonbot.services.assistant.GuildAssistantChannelsService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class MessageListener extends ListenerAdapter {
    private final GuildAssistantChannelsService channelsService;
    private final ConversationUseCase conversation;
    private final DiscordIdentityMapper identityMapper;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public MessageListener(
            GuildAssistantChannelsService channelsService,
            ConversationUseCase conversation,
            DiscordIdentityMapper identityMapper) {
        this.channelsService = channelsService;
        this.conversation = conversation;
        this.identityMapper = identityMapper;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        var configured = channelsService.find(event.getGuild().getIdLong()).orElse(null);
        if (configured == null || configured.getTextChannelId() != event.getChannel().getIdLong()) return;

        String question = event.getMessage().getContentRaw().trim();
        if (question.isEmpty()) return;
        if (question.length() > 1000) {
            event.getChannel().sendMessage("질문은 1000자 이하로 보내 주세요.").queue();
            return;
        }
        workers.submit(() -> answer(event, question));
    }

    private void answer(MessageReceivedEvent event, String question) {
        try {
            event.getChannel().sendTyping().queue();
            var session = new ConversationSession(
                    new ConversationSessionId("discord:text:" + event.getAuthor().getId()),
                    identityMapper.toActorId(
                            event.getAuthor().getIdLong(), event.getAuthor().getName()),
                    ClientSource.DISCORD,
                    ConversationModality.TEXT,
                    Map.of("discord.guildId", event.getGuild().getId()));
            String response = conversation.converse(new ConversationRequest(
                    "message:" + event.getMessageId(),
                    session,
                    event.getAuthor().getName(),
                    question)).content();
            if (response == null || response.isBlank()) {
                event.getChannel().sendMessage("AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.").queue();
                return;
            }
            sendChunks(event, response);
        } catch (AgentApprovalRequiredException e) {
            event.getChannel().sendMessage("도구 실행 승인이 필요해요. `/에이전트`에서 확인해 주세요. run: `"
                    + e.getRunId() + "`").queue();
        } catch (ConversationRejectedException e) {
            event.getChannel().sendMessage("⚠️ " + e.getMessage()).queue();
        } catch (Exception e) {
            log.error("전용 채팅 채널 AI 응답 실패 guild={} user={}",
                    event.getGuild().getIdLong(), event.getAuthor().getIdLong(), e);
            event.getChannel().sendMessage("처리 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.").queue();
        }
    }

    private void sendChunks(MessageReceivedEvent event, String response) {
        for (int start = 0; start < response.length(); start += 1900) {
            event.getChannel().sendMessage(
                    response.substring(start, Math.min(start + 1900, response.length()))).queue();
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
