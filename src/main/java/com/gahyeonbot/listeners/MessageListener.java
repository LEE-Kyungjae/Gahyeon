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
import com.gahyeonbot.adapters.discord.config.GuildAssistantChannelsService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class MessageListener extends ListenerAdapter {
    private static final String[] PROGRESS_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private final GuildAssistantChannelsService channelsService;
    private final ConversationUseCase conversation;
    private final DiscordIdentityMapper identityMapper;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService progressScheduler =
            Executors.newSingleThreadScheduledExecutor();

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
        ProgressMessage progress = null;
        try {
            event.getChannel().sendTyping().queue();
            Message statusMessage = event.getChannel()
                    .sendMessage(progressText(0, 0))
                    .complete();
            progress = startProgress(event, statusMessage);
            var session = new ConversationSession(
                    new ConversationSessionId("discord:text:" + event.getAuthor().getId()),
                    identityMapper.toActorId(
                            event.getAuthor().getIdLong(), event.getAuthor().getName()),
                    ClientSource.DISCORD,
                    ConversationModality.TEXT,
                    Map.of("agent.toolScopeId", event.getGuild().getId()));
            String response = conversation.converse(new ConversationRequest(
                    "message:" + event.getMessageId(),
                    session,
                    event.getAuthor().getName(),
                    question)).content();
            if (response == null || response.isBlank()) {
                progress.replace("AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
                return;
            }
            replaceWithResponse(event, progress, response);
        } catch (AgentApprovalRequiredException e) {
            replaceOrSend(event, progress,
                    "도구 실행 승인이 필요해요. `/에이전트`에서 확인해 주세요. run: `"
                            + e.getRunId() + "`");
        } catch (ConversationRejectedException e) {
            replaceOrSend(event, progress, "⚠️ " + e.getMessage());
        } catch (Exception e) {
            log.error("전용 채팅 채널 AI 응답 실패 guild={} user={}",
                    event.getGuild().getIdLong(), event.getAuthor().getIdLong(), e);
            replaceOrSend(event, progress, "처리 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        } finally {
            if (progress != null) progress.close();
        }
    }

    private ProgressMessage startProgress(MessageReceivedEvent event, Message message) {
        ProgressMessage progress = new ProgressMessage(message);
        ScheduledFuture<?> future = progressScheduler.scheduleAtFixedRate(
                () -> {
                    long elapsed = progress.advance();
                    if (elapsed < 0) return;
                    if (elapsed % 8 == 0) event.getChannel().sendTyping().queue();
                },
                4, 4, TimeUnit.SECONDS);
        progress.attach(future);
        return progress;
    }

    private void replaceWithResponse(
            MessageReceivedEvent event,
            ProgressMessage progress,
            String response) {
        int firstEnd = Math.min(1900, response.length());
        progress.replace(response.substring(0, firstEnd));
        for (int start = firstEnd; start < response.length(); start += 1900) {
            event.getChannel().sendMessage(
                    response.substring(start, Math.min(start + 1900, response.length()))).queue();
        }
    }

    private void replaceOrSend(
            MessageReceivedEvent event,
            ProgressMessage progress,
            String message) {
        if (progress == null) event.getChannel().sendMessage(message).queue();
        else progress.replace(message);
    }

    static String progressText(int frame, long elapsedSeconds) {
        String spinner = PROGRESS_FRAMES[Math.floorMod(frame, PROGRESS_FRAMES.length)];
        return spinner + " 답변을 준비하고 있어요 · " + elapsedSeconds + "초";
    }

    private static final class ProgressMessage implements AutoCloseable {
        private final Message message;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ScheduledFuture<?> future;
        private int frame;
        private long elapsedSeconds;

        private ProgressMessage(Message message) {
            this.message = message;
        }

        private synchronized void attach(ScheduledFuture<?> future) {
            this.future = future;
            if (closed.get()) future.cancel(false);
        }

        private synchronized long advance() {
            if (closed.get()) return -1;
            elapsedSeconds += 4;
            frame++;
            try {
                message.editMessage(progressText(frame, elapsedSeconds)).complete();
            } catch (RuntimeException ignored) {
                // A final replacement or channel deletion may race with this cosmetic update.
            }
            return elapsedSeconds;
        }

        private synchronized void replace(String content) {
            close();
            message.editMessage(content).complete();
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (future != null) future.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        progressScheduler.shutdownNow();
    }
}
