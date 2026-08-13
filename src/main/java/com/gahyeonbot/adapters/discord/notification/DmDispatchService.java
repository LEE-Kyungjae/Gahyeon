package com.gahyeonbot.adapters.discord.notification;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import com.gahyeonbot.entity.DmDeliveryLog;
import com.gahyeonbot.repository.DmDeliveryLogRepository;
import com.gahyeonbot.services.notification.DmSubscriptionService;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DmDispatchService {

    private static final int DISCORD_MAX_MESSAGE_LENGTH = 2000;

    private final BotInitializerRunner botInitializerRunner;
    private final DmDeliveryLogRepository deliveryLogRepository;
    private final DmSubscriptionService subscriptionService;

    @Value("${notifications.dm.enabled:true}")
    private boolean dmEnabled;

    public DispatchResult dispatchGeneratedMessage(String runId, Long userId, String content, String dedupeKey) {
        if (userId == null || content == null || content.trim().isEmpty() || dedupeKey == null || dedupeKey.isBlank()) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("FAILED_INVALID_INPUT")
                    .message("userId/content/dedupeKey are required")
                    .build();
        }

        String trimmed = content.trim();
        return dispatchInternal(
                runId,
                userId,
                dedupeKey,
                sha256(content),
                "userId/content/dedupeKey are required",
                user -> user.openPrivateChannel()
                        .flatMap(channel -> channel.sendMessage(truncateContent(trimmed)))
                        .complete()
        );
    }

    public DispatchResult dispatchEmbed(String runId, Long userId, MessageEmbed embed, String dedupeKey) {
        if (userId == null || embed == null || dedupeKey == null || dedupeKey.isBlank()) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("FAILED_INVALID_INPUT")
                    .message("userId/embed/dedupeKey are required")
                    .build();
        }

        String contentHash = sha256(
                (embed.getTitle() != null ? embed.getTitle() : "")
                        + (embed.getDescription() != null ? embed.getDescription() : "")
        );
        return dispatchInternal(
                runId,
                userId,
                dedupeKey,
                contentHash,
                "userId/embed/dedupeKey are required",
                user -> user.openPrivateChannel()
                        .flatMap(channel -> channel.sendMessageEmbeds(embed))
                        .complete()
        );
    }

    private DispatchResult dispatchInternal(
            String runId,
            Long userId,
            String dedupeKey,
            String contentHash,
            String invalidInputMessage,
            Consumer<User> sendFn
    ) {
        if (!dmEnabled) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("FAILED_DISABLED")
                    .message("dm notifications disabled")
                    .build();
        }

        if (userId == null || dedupeKey == null || dedupeKey.isBlank() || contentHash == null || contentHash.isBlank() || sendFn == null) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("FAILED_INVALID_INPUT")
                    .message(invalidInputMessage)
                    .build();
        }

        if (!subscriptionService.isOptedIn(userId)) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("SKIPPED_NOT_OPTED_IN")
                    .message("user did not opt in")
                    .build();
        }

        ShardManager shardManager = botInitializerRunner.getShardManager();
        if (shardManager == null) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("FAILED_NOT_READY")
                    .message("discord shard manager is not ready")
                    .build();
        }

        String normalizedDedupeKey = dedupeKey.trim();
        if (deliveryLogRepository.existsByDedupeKey(normalizedDedupeKey)) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("SKIPPED_DUPLICATE")
                    .message("duplicate dedupeKey")
                    .build();
        }

        DmDeliveryLog logEntry = DmDeliveryLog.builder()
                .runId(safeRunId(runId))
                .dedupeKey(normalizedDedupeKey)
                .userId(userId)
                .contentHash(contentHash)
                .status("RECEIVED")
                .build();

        try {
            deliveryLogRepository.save(logEntry);
        } catch (DataIntegrityViolationException e) {
            return DispatchResult.builder()
                    .sent(false)
                    .status("SKIPPED_DUPLICATE")
                    .message("duplicate dedupeKey")
                    .build();
        }

        try {
            User user = resolveUser(shardManager, userId);
            if (user == null) {
                markFailed(logEntry, "FAILED_USER_NOT_FOUND", "user not found");
                return DispatchResult.builder()
                        .sent(false)
                        .status("FAILED_USER_NOT_FOUND")
                        .message("user not found")
                        .build();
            }

            sendFn.accept(user);

            logEntry.setStatus("SENT");
            logEntry.setErrorMessage(null);
            logEntry.setAttemptedAt(LocalDateTime.now());
            deliveryLogRepository.save(logEntry);

            return DispatchResult.builder()
                    .sent(true)
                    .status("SENT")
                    .message("ok")
                    .build();
        } catch (Exception e) {
            log.warn("DM 전송 실패 - userId: {}, reason: {}", userId, e.getMessage());
            markFailed(logEntry, "FAILED_SEND", e.getMessage());
            return DispatchResult.builder()
                    .sent(false)
                    .status("FAILED_SEND")
                    .message(e.getMessage())
                    .build();
        }
    }

    private User resolveUser(ShardManager shardManager, long userId) {
        User cached = shardManager.getUserById(userId);
        if (cached != null) {
            return cached;
        }
        try {
            return shardManager.retrieveUserById(userId).complete();
        } catch (Exception e) {
            return null;
        }
    }

    private void markFailed(DmDeliveryLog logEntry, String status, String reason) {
        logEntry.setStatus(status);
        logEntry.setErrorMessage(reason);
        logEntry.setAttemptedAt(LocalDateTime.now());
        deliveryLogRepository.save(logEntry);
    }

    private String safeRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return "internal-run";
        }
        return runId.trim();
    }

    private String truncateContent(String content) {
        if (content.length() <= DISCORD_MAX_MESSAGE_LENGTH) {
            return content;
        }
        return content.substring(0, DISCORD_MAX_MESSAGE_LENGTH - 3) + "...";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Builder
    @Getter
    public static class DispatchResult {
        private boolean sent;
        private String status;
        private String message;
    }
}
