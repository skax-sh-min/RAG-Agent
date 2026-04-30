package com.example.ragagent.service;

import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.ThreadMetaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ThreadMetaService {

    private static final Logger log = LoggerFactory.getLogger(ThreadMetaService.class);
    private static final int SIDEBAR_LIMIT = 20;
    private static final int TITLE_MAX_CHARS = 20;

    private final ThreadMetaRepository repository;
    private final ChatClient chatClient;

    public ThreadMetaService(ThreadMetaRepository repository, ChatClient chatClient) {
        this.repository = repository;
        this.chatClient = chatClient;
    }

    public List<ThreadMeta> getAll() {
        return repository.findAllRecent(SIDEBAR_LIMIT);
    }

    public Optional<ThreadMeta> findById(String threadId) {
        return repository.findById(threadId);
    }

    public int countTurns(String threadId) {
        return repository.countTurns(threadId);
    }

    /** Inserts a placeholder row if the thread doesn't exist yet. */
    public ThreadMeta getOrCreate(String threadId, String version) {
        return repository.findById(threadId).orElseGet(() -> {
            String now = ThreadMetaRepository.now();
            ThreadMeta meta = new ThreadMeta(
                    threadId,
                    "[%s] 새 대화".formatted(version),
                    version,
                    now, now,
                    "COST_FIRST");
            repository.save(meta);
            return meta;
        });
    }

    public void updateTitle(String threadId, String title) {
        repository.updateTitle(threadId, title);
    }

    public void updateRoutingMode(String threadId, String routingMode) {
        repository.updateRoutingMode(threadId, routingMode);
    }

    public void delete(String threadId) {
        repository.delete(threadId);
    }

    /**
     * Generates a short Korean title via LLM on a virtual thread.
     * Saves "[{version}] {summary}" to thread_meta.title after completion.
     */
    public void generateTitleAsync(String threadId, String version, String question) {
        Thread.ofVirtual().start(() -> {
            try {
                String raw = chatClient.prompt()
                        .user("다음 질문을 20자 이내 한국어 명사구로 요약하세요 (설명 없이 명사구만 출력): " + question)
                        .call()
                        .content();
                String summary = raw == null ? "새 대화" : raw.strip();
                if (summary.length() > TITLE_MAX_CHARS) {
                    summary = summary.substring(0, TITLE_MAX_CHARS);
                }
                repository.updateTitle(threadId, "[%s] %s".formatted(version, summary));
            } catch (Exception e) {
                log.warn("Title generation failed for thread {}: {}", threadId, e.getMessage());
            }
        });
    }
}
