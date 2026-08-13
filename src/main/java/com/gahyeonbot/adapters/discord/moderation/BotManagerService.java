package com.gahyeonbot.adapters.discord.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 봇 관리 서비스 클래스.
 * 봇의 상태 관리 및 제어 기능을 제공합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Service
public class BotManagerService {

    /**
     * 봇을 종료합니다.
     */
    public void shutdown() {
        // TODO: 봇 종료 로직 구현
        System.exit(0);
    }

    /**
     * 봇의 상태를 확인합니다.
     * 
     * @return 봇 상태 정보
     */
    public String getStatus() {
        // TODO: 봇 상태 확인 로직 구현
        return "정상 작동 중";
    }

    public List<Member> removeBotsFromChannel(SlashCommandInteractionEvent event, AudioChannel channel) {
        List<Member> botsToRemove = new ArrayList<>();
        for (Member member : channel.getMembers()) {
            if (member.getUser().isBot()) {
                botsToRemove.add(member);
                event.getGuild().kickVoiceMember(member).queue(
                        success -> event.getChannel().sendMessage(member.getEffectiveName() + " 봇을 내보냈습니다.").queue(),
                        error -> event.getChannel().sendMessage("오류: " + member.getEffectiveName() + " 봇을 내보낼 수 없습니다.").queue()
                );
            }
        }
        return botsToRemove;
    }
}
