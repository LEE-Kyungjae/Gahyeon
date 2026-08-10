package com.gahyeonbot.adapters.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.session.ConversationSessionId;
import com.gahyeonbot.entity.GahyeonEventRecord;
import com.gahyeonbot.repository.GahyeonEventRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PersistentGahyeonEventStore implements GahyeonEventPublisher, GahyeonEventQuery {
    private static final int MAX_QUERY_LIMIT = 500;
    private final GahyeonEventRecordRepository repository;
    private final ObjectMapper objectMapper;

    public PersistentGahyeonEventStore(
            GahyeonEventRecordRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public GahyeonEvent publish(GahyeonEventDraft draft) {
        try {
            GahyeonEventRecord saved = repository.saveAndFlush(GahyeonEventRecord.builder()
                    .eventId(UUID.randomUUID().toString())
                    .schemaVersion(GahyeonEvent.CURRENT_SCHEMA_VERSION)
                    .eventType(draft.type())
                    .sessionId(draft.sessionId().value())
                    .correlationId(draft.correlationId())
                    .payloadJson(objectMapper.writeValueAsString(draft.payload()))
                    .occurredAt(Instant.now())
                    .build());
            return toEvent(saved);
        } catch (Exception exception) {
            throw new EventStoreException("이벤트 저장에 실패했습니다.", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<GahyeonEvent> after(long sequence, int limit) {
        if (sequence < 0) throw new IllegalArgumentException("sequence는 0 이상이어야 합니다.");
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        return repository.findByIdGreaterThanOrderByIdAsc(sequence, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toEvent)
                .toList();
    }

    private GahyeonEvent toEvent(GahyeonEventRecord record) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    record.getPayloadJson(), new TypeReference<>() {});
            return new GahyeonEvent(
                    record.getSchemaVersion(),
                    record.getEventId(),
                    record.getId(),
                    record.getEventType(),
                    new ConversationSessionId(record.getSessionId()),
                    record.getCorrelationId(),
                    record.getOccurredAt(),
                    payload);
        } catch (Exception exception) {
            throw new EventStoreException("저장된 이벤트를 읽지 못했습니다.", exception);
        }
    }

    public static class EventStoreException extends RuntimeException {
        public EventStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
