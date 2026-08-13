package com.gahyeonbot.adapters.discord.moderation;

import com.gahyeonbot.commands.util.ResponseUtil;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.pagination.MessagePaginationAction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Discord 채널의 메시지를 삭제하는 서비스 클래스.
 * 전체 메시지 또는 특정 사용자의 메시지를 삭제할 수 있습니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Service
public class MessageCleanService {

    /**
     * 채널의 메시지를 삭제합니다.
     * 
     * @param channel 메시지를 삭제할 채널
     * @param event 슬래시 명령어 상호작용 이벤트
     * @param count 삭제할 메시지 수
     */
    public void deleteMessages(MessageChannel channel, SlashCommandInteractionEvent event, int count) {
        if (count > 100) {
            // 100개 이상인 경우 100개씩 나누어 삭제
            int remaining = count;
            while (remaining > 0) {
                int deleteCount = Math.min(remaining, 100);
                deleteMessageBatch(channel, event, deleteCount);
                remaining -= deleteCount;
            }
        } else {
            deleteMessageBatch(channel, event, count);
        }
    }

    /**
     * 특정 사용자의 메시지를 삭제합니다.
     * 
     * @param channel 메시지를 삭제할 채널
     * @param event 슬래시 명령어 상호작용 이벤트
     * @param count 삭제할 메시지 수
     */
    public void deleteUserMessages(MessageChannel channel, SlashCommandInteractionEvent event, int count) {
        // 최근 1000개 메시지에서 사용자의 메시지만 필터링하여 삭제
        channel.getIterableHistory().limit(1000).queue(messages -> {
            List<net.dv8tion.jda.api.entities.Message> userMessages = messages.stream()
                    .filter(message -> message.getAuthor().equals(event.getUser()))
                    .limit(count)
                    .toList();

            if (userMessages.isEmpty()) {
                event.getHook().editOriginal("삭제할 메시지가 없습니다.").queue();
                return;
            }

            channel.purgeMessages(userMessages);
            event.getHook().editOriginal(userMessages.size() + "개의 메시지를 삭제했습니다.").queue();
        });
    }

    /**
     * 메시지 배치를 삭제합니다.
     * 
     * @param channel 메시지를 삭제할 채널
     * @param event 슬래시 명령어 상호작용 이벤트
     * @param count 삭제할 메시지 수 (최대 100개)
     */
    private void deleteMessageBatch(MessageChannel channel, SlashCommandInteractionEvent event, int count) {
        channel.getIterableHistory().limit(count).queue(messages -> {
            if (messages.isEmpty()) {
                event.getHook().editOriginal("삭제할 메시지가 없습니다.").queue();
                return;
            }

            channel.purgeMessages(messages);
            event.getHook().editOriginal(messages.size() + "개의 메시지를 삭제했습니다.").queue();
        });
    }
}
