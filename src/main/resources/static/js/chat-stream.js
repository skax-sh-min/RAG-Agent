/**
 * chat-stream.js — SSE streaming client for /ui/chat/stream
 *
 * Intercepts the chat form submit, replaces the HTMX flow with a
 * fetch POST that reads a Server-Sent Events response.
 * The existing hx-post="/ui/chat" attribute is kept for the non-JS fallback
 * and for server-rendering previous turns.
 */
(function () {
    'use strict';

    // ── Active stream cancellation — only one stream can be in flight at a time
    // (the send button is repurposed as a stop button while streaming) ──────
    let currentAbort = null;

    // ── Auto-scroll anchoring ────────────────────────────────────────────────
    // While an answer streams we only stick to the bottom if the user is already
    // there. If they scroll up (e.g. to re-read earlier content), auto-scroll is
    // suppressed and a "jump to latest" button appears until they return.
    let stickToBottom = true;
    const NEAR_BOTTOM_PX = 80;

    // ── Stage label map ──────────────────────────────────────────────────────
    const STAGE_LABELS = {
        classifier: '질문 분류 중...',
        retrieval:  '관련 문서 검색 중...',
        answer:     '답변 생성 중...',
        critic:     '답변 검증 중...',
        upgrade:    '고추론 재분석 중...',
    };

    // ── Utility ──────────────────────────────────────────────────────────────

    function escHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function nowTimeStr() {
        return new Date().toLocaleTimeString('ko-KR', {hour: '2-digit', minute: '2-digit', hour12: false});
    }

    function genId() {
        return crypto.randomUUID().replace(/-/g, '').substring(0, 8);
    }

    function isNearBottom(el) {
        return el.scrollHeight - el.scrollTop - el.clientHeight <= NEAR_BOTTOM_PX;
    }

    /**
     * Scrolls the message list to the bottom, but only while "stuck" — i.e. the user
     * hasn't scrolled up. Pass force=true (e.g. when the user sends a new message or
     * clicks the jump button) to re-anchor and jump regardless.
     */
    function scrollToBottom(force) {
        const el = document.getElementById('chat-messages');
        if (!el) return;
        if (force === true) stickToBottom = true;
        if (stickToBottom) el.scrollTo({ top: el.scrollHeight, behavior: 'instant' });
        updateScrollButton();
    }

    function updateScrollButton() {
        const btn = document.getElementById('scroll-to-bottom-btn');
        if (btn) btn.classList.toggle('d-none', stickToBottom);
    }

    /** Toggles the send button between "send" and "stop" while a stream is active. */
    function setStreamingUiState(active) {
        const sendBtn = document.getElementById('send-btn');
        if (!sendBtn) return;
        sendBtn.classList.toggle('btn-primary', !active);
        sendBtn.classList.toggle('btn-danger', active);
        sendBtn.innerHTML = active ? '<i class="bi bi-stop-fill"></i>' : '<i class="bi bi-send-fill"></i>';
        sendBtn.setAttribute('aria-label', active
            ? (sendBtn.dataset.stopLabel || 'Stop')
            : (sendBtn.dataset.sendLabel || 'Send'));
    }

    // ── DOM builders ─────────────────────────────────────────────────────────

    function appendUserBubble(question) {
        const timeStr = nowTimeStr();
        const wrap = document.createElement('div');
        wrap.className = 'd-flex justify-content-end mb-3 align-items-end';
        wrap.innerHTML =
            `<div class="d-flex flex-column align-items-end me-1">` +
            `<div class="bubble-user p-3">${escHtml(question)}</div>` +
            `<small class="text-muted" style="font-size:0.68rem; margin-top:2px;">${escHtml(timeStr)}</small>` +
            `</div>` +
            `<i class="bi bi-person-circle fs-5 text-secondary ms-2 mt-1 flex-shrink-0"></i>`;
        document.getElementById('chat-messages').appendChild(wrap);
    }

    function appendStreamingBubble(bubbleId) {
        const wrap = document.createElement('div');
        wrap.id = `bubble-${bubbleId}`;
        wrap.className = 'd-flex align-items-start mb-3';
        wrap.innerHTML = `
            <i class="bi bi-robot fs-5 text-secondary me-2 mt-1 flex-shrink-0"></i>
            <div class="bubble-assistant p-3 flex-grow-1">
                <div id="stream-stage-${bubbleId}" class="stream-stage small text-muted mb-1">
                    <span class="spinner-border spinner-border-sm me-1" role="status"></span>
                    <span id="stream-stage-text-${bubbleId}">질문 분석 중...</span>
                </div>
                <div id="stream-content-${bubbleId}" class="md-content stream-content stream-cursor"></div>
                <div id="stream-images-${bubbleId}"></div>
                <div id="stream-sources-${bubbleId}"></div>
                <div id="stream-meta-${bubbleId}" class="mt-2 d-flex align-items-center flex-wrap gap-2" style="font-size:0.72rem;"></div>
            </div>`;
        document.getElementById('chat-messages').appendChild(wrap);
    }

    // ── SSE event handlers ────────────────────────────────────────────────────

    function onStage(bubbleId, data) {
        const el = document.getElementById(`stream-stage-text-${bubbleId}`);
        if (el) el.textContent = data.text || STAGE_LABELS[data.id] || data.id;
        // PROGRESSIVE upgrade: clear accumulated content so premium answer re-fills
        if (data.id === 'upgrade') {
            const contentEl = document.getElementById(`stream-content-${bubbleId}`);
            if (contentEl) contentEl.textContent = '';
        }
        // RETRIEVAL (re)entry: clear the prior search's images/sources first. A retry that
        // finds no images doesn't send an "images" event at all (see onImages), so without
        // this the previous search's now-unrelated thumbnails/badges would linger.
        if (data.id === 'retrieval') {
            const imagesEl = document.getElementById(`stream-images-${bubbleId}`);
            if (imagesEl) imagesEl.innerHTML = '';
            const sourcesEl = document.getElementById(`stream-sources-${bubbleId}`);
            if (sourcesEl) sourcesEl.innerHTML = '';
        }
        // NOTE: on a verification-failure retry the live content is preserved (not cleared) by
        // onRetry(), which moves it into a collapsed "미검증" block before the fresh attempt
        // re-streams — so no answer-stage clearing here.
    }

    /**
     * A verification-failure retry (answer insufficient / critic ungrounded). Preserve the
     * just-streamed unverified answer as a collapsed, 미검증-marked block, leave a retry notice,
     * then clear the live area so the next (wider-scope) attempt streams fresh. The preserved
     * block is transient — onDone()/onAborted() remove it once a final answer arrives.
     */
    function onRetry(bubbleId, data) {
        const contentEl = document.getElementById(`stream-content-${bubbleId}`);
        if (!contentEl) return;
        const rawText = contentEl.textContent || '';

        // Superseded-answers container, kept above the live content.
        let container = document.getElementById(`stream-superseded-${bubbleId}`);
        if (!container) {
            container = document.createElement('div');
            container.id = `stream-superseded-${bubbleId}`;
            contentEl.parentNode.insertBefore(container, contentEl);
        }
        if (rawText.trim()) {
            const details = document.createElement('details');
            details.className = 'superseded-answer border rounded mb-2';
            const summary = document.createElement('summary');
            summary.className = 'small text-muted p-2';
            summary.innerHTML =
                `<span class="badge bg-warning text-dark me-1">미검증</span>` +
                `<span aria-label="싫어요">👎</span> 검증 미통과 — 이전 답변 펼쳐보기`;
            const body = document.createElement('div');
            body.className = 'md-content p-2 pt-0';
            body.textContent = rawText;          // textContent → renderMarkdown sanitizes
            renderMarkdown(body);
            details.appendChild(summary);
            details.appendChild(body);
            container.appendChild(details);
        }

        // Persistent retry notice (removed on done/abort).
        let notice = document.getElementById(`stream-retry-notice-${bubbleId}`);
        if (!notice) {
            notice = document.createElement('div');
            notice.id = `stream-retry-notice-${bubbleId}`;
            notice.className = 'retry-notice small text-warning mb-2 d-flex align-items-center gap-1';
            contentEl.parentNode.insertBefore(notice, contentEl);
        }
        notice.innerHTML =
            `<i class="bi bi-arrow-repeat"></i>` +
            `<span>${escHtml(data.text || '검증 미통과 — 검색 범위를 넓혀 재시도 중...')}</span>`;

        // Clear the live area for the fresh attempt.
        contentEl.textContent = '';
        scrollToBottom();
    }

    /** Removes the transient retry UI (superseded answers + notice) once a final state is reached. */
    function clearRetryArtifacts(bubbleId) {
        document.getElementById(`stream-superseded-${bubbleId}`)?.remove();
        document.getElementById(`stream-retry-notice-${bubbleId}`)?.remove();
    }

    function onImages(bubbleId, imageRefs) {
        const container = document.getElementById(`stream-images-${bubbleId}`);
        if (!container || !imageRefs || imageRefs.length === 0) return;
        const thumbs = imageRefs.map(ref => {
            const url = escHtml('/api/v1/' + encodeURI(ref));
            return `<a href="${url}" target="_blank" rel="noopener">
                <img src="${url}" alt="참조 이미지" loading="lazy"
                     style="max-height:120px; max-width:180px; object-fit:contain; border:1px solid var(--border-default, #dee2e6); border-radius:4px;" />
            </a>`;
        }).join('');
        container.innerHTML = `<div class="mt-2 d-flex flex-wrap gap-2">${thumbs}</div>`;
    }

    function onSources(bubbleId, sources) {
        const container = document.getElementById(`stream-sources-${bubbleId}`);
        if (!container || !sources || sources.length === 0) return;
        const badges = sources.map(s =>
            `<a href="#" class="source-ref badge bg-secondary text-decoration-none me-1 mb-1"
                data-bs-toggle="popover"
                data-bs-trigger="hover focus"
                data-bs-placement="top"
                data-bs-content="${escHtml(s.preview || '')}"
                title="${escHtml(s.label || '')}"
             >${escHtml(s.label || '출처')}</a>`
        ).join('');
        container.innerHTML = `<div class="mt-2">${badges}</div>`;
        container.querySelectorAll('[data-bs-toggle="popover"]')
            .forEach(el => new bootstrap.Popover(el));
    }

    function renderMarkdown(el) {
        if (!el) return;
        const raw = el.textContent || '';
        if (typeof marked !== 'undefined') {
            const html = marked.parse(raw);
            el.innerHTML = typeof DOMPurify !== 'undefined' ? DOMPurify.sanitize(html) : html;
            if (typeof hljs !== 'undefined') {
                el.querySelectorAll('pre code').forEach(block => hljs.highlightElement(block));
            }
        }
    }

    function onToken(bubbleId, text) {
        const el = document.getElementById(`stream-content-${bubbleId}`);
        if (el) el.textContent += text;
        scrollToBottom();
    }

    function onDone(bubbleId, data) {
        const contentEl = document.getElementById(`stream-content-${bubbleId}`);
        const stageEl   = document.getElementById(`stream-stage-${bubbleId}`);
        const metaEl    = document.getElementById(`stream-meta-${bubbleId}`);

        // 1. Remove streaming cursor
        if (contentEl) contentEl.classList.remove('stream-cursor');

        // Capture raw (pre-render) answer length for the char-count metadata below —
        // must happen before markdown rendering replaces textContent with rendered HTML.
        const answerLen = (contentEl?.textContent || '').length;

        // 2. Render markdown
        renderMarkdown(contentEl);

        // 3. Hide stage spinner + drop any superseded (unverified) retry answers — a final
        //    answer arrived, so the "삭제 예정" attempts are removed (retry succeeded/exhausted).
        if (stageEl) stageEl.remove();
        clearRetryArtifacts(bubbleId);

        // 4. Feedback buttons (like/dislike) + metadata footer, same line — feedback first (left).
        //    Uses the same .feedback-btn/.feedback-controls markup the chat.html delegated
        //    click handler listens for on #chat-messages.
        if (metaEl) {
            let html = '';
            if (data.turnId != null) {
                const threadIdInput = document.querySelector('#chat-form input[name="threadId"]');
                const tId = threadIdInput ? threadIdInput.value : '';
                html += `<div class="feedback-controls d-flex gap-1" data-turn-id="${data.turnId}" data-thread-id="${escHtml(tId)}">
                    <button type="button" class="btn btn-sm btn-outline-secondary feedback-btn" data-feedback="LIKE" aria-label="좋아요" title="좋아요">👍</button>
                    <button type="button" class="btn btn-sm btn-outline-secondary feedback-btn" data-feedback="DISLIKE" aria-label="싫어요" title="싫어요 (다음 대화 컨텍스트에서 제외)">👎</button>
                </div>`;
            }

            const qt = data.questionType;
            const parts = [];
            if (qt)                       parts.push(`<span class="badge badge-${escHtml(qt)} me-1">${escHtml(qt)}</span>`);
            if (data.grounded === true)   parts.push(`<span class="badge bg-success me-1">검증됨</span>`);
            else if (data.grounded === false) parts.push(`<span class="badge bg-warning text-dark me-1">미검증</span>`);
            if (data.premiumUpgraded)     parts.push(`<span class="badge-upgraded ms-1">⬆ ${escHtml(data.premiumUpgraded)}</span>`);
            if (data.usedProvider)        parts.push(`🤖 ${escHtml(data.usedProvider)}`);
            if (data.elapsedMs != null)   parts.push(`⏱ ${(data.elapsedMs / 1000).toFixed(1)}s`);
            const inp = data.inputTokens  || 0;
            const out = data.outputTokens || 0;
            if (inp || out)               parts.push(`📥 ${inp} · 📤 ${out} · 합계 ${inp + out} tok`);
            if (data.llmCalls)            parts.push(`🔄 ${data.llmCalls}`);
            if (answerLen)                parts.push(`📝 ${answerLen}자`);
            parts.push(`🕐 ${nowTimeStr()}`);
            html += `<span class="text-muted">${parts.join(' · ')}</span>`;

            metaEl.innerHTML = html;
        }

        // 5. Trigger thread list refresh
        if (data.refreshThreadList && typeof htmx !== 'undefined') {
            htmx.trigger(document.body, 'refreshThreadList');
        }

        scrollToBottom();
    }

    function onError(bubbleId, message) {
        const bubble = document.getElementById(`bubble-${bubbleId}`);
        if (bubble) {
            bubble.outerHTML =
                `<div class="d-flex align-items-start mb-3">` +
                `<i class="bi bi-robot fs-5 text-secondary me-2 mt-1 flex-shrink-0"></i>` +
                `<div class="bubble-assistant p-3 flex-grow-1 text-danger">` +
                `<i class="bi bi-exclamation-triangle me-1"></i>${escHtml(message || '오류가 발생했습니다.')}` +
                `</div></div>`;
        }
    }

    /** User-initiated stop (AbortController). Keeps whatever partial answer already streamed in. */
    function onAborted(bubbleId) {
        clearRetryArtifacts(bubbleId);
        const stageEl = document.getElementById(`stream-stage-${bubbleId}`);
        if (stageEl) stageEl.remove();

        const contentEl = document.getElementById(`stream-content-${bubbleId}`);
        if (contentEl) {
            contentEl.classList.remove('stream-cursor');
            renderMarkdown(contentEl);
        }

        const metaEl = document.getElementById(`stream-meta-${bubbleId}`);
        if (metaEl) {
            metaEl.innerHTML = `<span class="text-muted"><i class="bi bi-stop-circle me-1"></i>사용자가 중단함 · ${escHtml(nowTimeStr())}</span>`;
        }
    }

    // ── SSE fetch + parse ────────────────────────────────────────────────────

    async function submitStream(formData, question) {
        const bubbleId = genId();

        appendUserBubble(question);
        appendStreamingBubble(bubbleId);
        scrollToBottom(true);   // user just sent — re-anchor to bottom

        const abortController = new AbortController();
        currentAbort = abortController;
        setStreamingUiState(true);

        try {
            const response = await fetch('/ui/chat/stream', {
                method: 'POST',
                body: formData,
                headers: typeof getCsrfHeaders === 'function' ? getCsrfHeaders() : {},
                signal: abortController.signal,
            });

            if (!response.ok) {
                onError(bubbleId, `서버 오류 (HTTP ${response.status})`);
                return;
            }

            const reader  = response.body.getReader();
            const decoder = new TextDecoder();
            let   buffer  = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });

                // SSE events are separated by \n\n
                const parts = buffer.split('\n\n');
                buffer = parts.pop(); // last element may be incomplete

                for (const chunk of parts) {
                    if (!chunk.trim()) continue;
                    let evName = '', evData = '';
                    for (const line of chunk.split('\n')) {
                        if (line.startsWith('event:')) evName = line.slice(6).trim();
                        else if (line.startsWith('data:')) evData = line.slice(5).trim();
                    }
                    if (evName && evData) dispatchSseEvent(bubbleId, evName, evData);
                }
            }

            // Flush any remaining buffer
            if (buffer.trim()) {
                let evName = '', evData = '';
                for (const line of buffer.split('\n')) {
                    if (line.startsWith('event:')) evName = line.slice(6).trim();
                    else if (line.startsWith('data:')) evData = line.slice(5).trim();
                }
                if (evName && evData) dispatchSseEvent(bubbleId, evName, evData);
            }

        } catch (e) {
            if (e.name === 'AbortError') {
                onAborted(bubbleId);
            } else {
                onError(bubbleId, e.message || '네트워크 오류');
            }
        } finally {
            currentAbort = null;
            setStreamingUiState(false);
        }
    }

    function dispatchSseEvent(bubbleId, name, rawData) {
        let data;
        try {
            data = JSON.parse(rawData);
        } catch (e) {
            console.warn('SSE JSON parse error:', e, rawData);
            return;
        }
        switch (name) {
            case 'stage':   onStage(bubbleId, data);          break;
            case 'sources': onSources(bubbleId, data);        break;
            case 'images':  onImages(bubbleId, data);         break;
            case 'token':   onToken(bubbleId, data.text);     break;
            case 'retry':   onRetry(bubbleId, data);          break;
            case 'done':    onDone(bubbleId, data);           break;
            case 'error':   onError(bubbleId, data.message);  break;
        }
    }

    // ── Form intercept ────────────────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        // Track scroll position: the user scrolling up suppresses auto-scroll and
        // reveals the jump-to-latest button; returning near the bottom re-anchors.
        const chatMessages = document.getElementById('chat-messages');
        if (chatMessages) {
            chatMessages.addEventListener('scroll', function () {
                stickToBottom = isNearBottom(chatMessages);
                updateScrollButton();
            }, { passive: true });
        }
        const scrollBtn = document.getElementById('scroll-to-bottom-btn');
        if (scrollBtn) {
            scrollBtn.addEventListener('click', function () { scrollToBottom(true); });
        }

        const form = document.getElementById('chat-form');
        if (!form) return;

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            e.stopImmediatePropagation(); // prevent HTMX from also handling it

            // Send button doubles as a stop button while a stream is active.
            if (currentAbort) {
                currentAbort.abort();
                return;
            }

            const questionEl = document.getElementById('question-input');
            const question   = questionEl ? questionEl.value.trim() : '';
            if (!question) return;

            // Capture FormData BEFORE clearing the textarea
            const formData = new FormData(form);

            if (questionEl) {
                questionEl.value = '';
                questionEl.style.height = 'auto';
                delete questionEl.dataset.summaryPrecomputed; // §6.10: allow next question to trigger precompute again
            }

            submitStream(formData, question);
        }, true); // capture phase — fires before HTMX listener
    });

})();
