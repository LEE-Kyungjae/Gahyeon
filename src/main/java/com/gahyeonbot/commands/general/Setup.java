package com.gahyeonbot.commands.general;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.config.GuildAssistantChannelsService;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class Setup extends AbstractCommand {
    private static final String CATEGORY_NAME = "가현봇";
    private static final String TEXT_CHANNEL_NAME = "가현봇-채팅";
    private static final String VOICE_CHANNEL_NAME = "가현봇-비서";

    private final GuildAssistantChannelsService channelsService;

    @Override public String getName() { return "setup"; }
    @Override public Map<DiscordLocale, String> getNameLocalizations() { return localizeKorean("설정"); }
    @Override public String getDescription() { return "Gahyeon 전용 채팅·음성 채널을 설정합니다."; }
    @Override public String getDetailedDescription() { return "/설정"; }
    @Override public List<OptionData> getOptions() { return List.of(); }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null || event.getMember() == null) {
            ResponseUtil.replyError(event, "서버 안에서만 사용할 수 있습니다.");
            return;
        }
        if (!event.getMember().hasPermission(Permission.MANAGE_CHANNEL)) {
            ResponseUtil.replyError(event, "채널 관리 권한이 있는 사용자만 설정할 수 있습니다.");
            return;
        }
        if (guild.getSelfMember() == null
                || !guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL)) {
            ResponseUtil.replyError(event, "가현봇에 채널 관리 권한을 먼저 부여해 주세요.");
            return;
        }

        event.deferReply(true).queue();
        try {
            var saved = channelsService.find(guild.getIdLong()).orElse(null);
            Category category = saved == null ? null : guild.getCategoryById(saved.getCategoryId());
            TextChannel text = saved == null ? null : guild.getTextChannelById(saved.getTextChannelId());
            VoiceChannel voice = saved == null ? null : guild.getVoiceChannelById(saved.getVoiceChannelId());

            if (category == null) {
                category = guild.getCategoriesByName(CATEGORY_NAME, true).stream().findFirst().orElse(null);
            }
            if (category == null) category = guild.createCategory(CATEGORY_NAME).complete();
            if (text == null) {
                text = category.getTextChannels().stream()
                        .filter(channel -> channel.getName().equalsIgnoreCase(TEXT_CHANNEL_NAME))
                        .findFirst().orElse(null);
            }
            if (text == null) text = category.createTextChannel(TEXT_CHANNEL_NAME).complete();
            if (voice == null) {
                voice = category.getVoiceChannels().stream()
                        .filter(channel -> channel.getName().equalsIgnoreCase(VOICE_CHANNEL_NAME))
                        .findFirst().orElse(null);
            }
            if (voice == null) voice = category.createVoiceChannel(VOICE_CHANNEL_NAME).complete();

            channelsService.save(
                    guild.getIdLong(), category.getIdLong(), text.getIdLong(), voice.getIdLong(),
                    event.getUser().getIdLong());
            event.getHook().editOriginal(
                    "설정 완료: " + text.getAsMention() + "에서 바로 채팅하고, **"
                            + voice.getName() + "**에 들어가면 음성 비서가 자동으로 참여합니다.").queue();
        } catch (Exception e) {
            logger.error("가현봇 전용 채널 설정 실패 guild={}", guild.getIdLong(), e);
            event.getHook().editOriginal("채널 설정에 실패했습니다. 가현봇의 채널 관리 권한을 확인해 주세요.").queue();
        }
    }
}
