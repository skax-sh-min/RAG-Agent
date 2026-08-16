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
        answer:     '답변 생각 중...',
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

    function stripImagePreviewFromSourceMarkdown(raw) {
        if (!raw) return '';
        return String(raw)
            .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
            .replace(/<img\b[^>]*>/gi, '')
            .replace(/\[이미지(?:\(변환불가\))?:[^\]]*\]/g, '');
    }

    function renderSourcePreviewHtml(raw) {
        const text = stripImagePreviewFromSourceMarkdown(raw);
        if (!text.trim()) return '<span class="text-muted small">미리보기 없음</span>';
        if (typeof marked === 'undefined') return `<div class="md-content small">${escHtml(text)}</div>`;
        const parsed = marked.parse(text);
        const sanitized = typeof DOMPurify !== 'undefined' ? DOMPurify.sanitize(parsed) : parsed;
        const wrap = document.createElement('div');
        wrap.innerHTML = sanitized;
        wrap.querySelectorAll('img').forEach(img => img.remove());
        return `<div class="md-content small">${wrap.innerHTML}</div>`;
    }

    let idSeq = 0;

    /**
     * Unique-within-this-page id for a streaming bubble's DOM element.
     *
     * Deliberately does NOT use crypto.randomUUID(): that API only exists in a secure context
     * (HTTPS or localhost). Served over plain HTTP from a LAN address — e.g. a colleague opening
     * http://10.x.x.x:8080 while the host itself works fine on localhost — it is undefined, and
     * this threw on the first line of submitStream(), before the user's own bubble was appended.
     * The rejection was never awaited or caught, so the send silently did nothing: no bubble, no
     * request, no server log. This id only has to be unique within one page, so a counter plus a
     * random suffix is sufficient and works everywhere.
     */
    function genId() {
        return 'b' + (idSeq++).toString(36) + Math.random().toString(36).slice(2, 10);
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
            `<div class="me-1">` +
            `<div class="bubble-user p-3">` +
                `<div>${escHtml(question)}</div>` +
                `<small class="text-white bubble-user-time">🕐 ${escHtml(timeStr)}</small>` +
            `</div>` +
            `</div>`;
        document.getElementById('chat-messages').appendChild(wrap);
    }

    function appendStreamingBubble(bubbleId) {
        const wrap = document.createElement('div');
        wrap.id = `bubble-${bubbleId}`;
        wrap.className = 'd-flex align-items-end mb-3';
        wrap.innerHTML = `
            <div class="bubble-assistant p-3 flex-grow-1">
                <div id="stream-stage-${bubbleId}" class="stream-stage small text-muted mb-1">
                    <span class="spinner-border spinner-border-sm me-1" role="status"></span>
                    <span id="stream-stage-text-${bubbleId}">질문 분석 중...</span>
                    <button type="button" id="stream-skip-images-${bubbleId}"
                            class="btn btn-sm btn-link p-0 ms-2 d-none" style="font-size:0.75rem; vertical-align:baseline;">건너뛰기</button>
                </div>
                <div id="stream-content-${bubbleId}" class="md-content stream-content stream-cursor"></div>
                <div id="stream-images-${bubbleId}"></div>
                <div id="stream-sources-${bubbleId}"></div>
                <div id="stream-meta-${bubbleId}" class="mt-2 d-flex align-items-center flex-wrap gap-2" style="font-size:0.72rem;"></div>
            </div>`;
        document.getElementById('chat-messages').appendChild(wrap);

        const skipBtn = document.getElementById(`stream-skip-images-${bubbleId}`);
        if (skipBtn) skipBtn.addEventListener('click', () => skipImageAnalysis(bubbleId));
    }

    /**
     * "건너뛰기" click during the "이미지 분석 중 (N/M)" stage — tells the server (still running
     * this same turn) to stop waiting on the remaining Lazy Vision calls, not to abort the turn
     * (that's the separate 중지/stop button — see setStreamingUiState). Fire-and-forget: the next
     * stage event (however the server proceeds) is what actually updates the badge, this call
     * just disables the button so a slow double-click can't double-fire it.
     */
    function skipImageAnalysis(bubbleId) {
        const skipBtn = document.getElementById(`stream-skip-images-${bubbleId}`);
        if (skipBtn) skipBtn.disabled = true;

        const threadIdInput = document.querySelector('#chat-form input[name="threadId"]');
        const threadId = threadIdInput ? threadIdInput.value : '';
        if (!threadId) return;

        fetch('/ui/chat/stream/skip-images', {
            method: 'POST',
            headers: typeof getCsrfHeaders === 'function' ? getCsrfHeaders() : {},
            body: new URLSearchParams({ threadId }),
        }).catch(() => {}); // best-effort — a failed skip request just means the wait continues
    }

    // ── SSE event handlers ────────────────────────────────────────────────────

    function onStage(bubbleId, data) {
        const el = document.getElementById(`stream-stage-text-${bubbleId}`);
        if (el) el.textContent = data.text || STAGE_LABELS[data.id] || data.id;
        // "건너뛰기" only makes sense while the server is actually waiting on image analysis —
        // every other stage (including the very next one once analysis finishes or is skipped)
        // hides it again, so it can never linger on an unrelated stage badge.
        const skipBtn = document.getElementById(`stream-skip-images-${bubbleId}`);
        if (skipBtn) skipBtn.classList.toggle('d-none', data.id !== 'image_analysis');
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
        removeVerifyingIndicator(bubbleId); // strip before reading raw text below
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
            // 사유가 있으면 접힌 상태에서도 보이게 요약줄에 붙인다 — 펼쳐야만 알 수 있으면
            // "왜 실패했는지"를 확인할 수 있게 한 목적이 반쯤 사라진다.
            summary.innerHTML =
                `<span class="badge bg-warning text-dark me-1">미검증</span>` +
                `<span aria-label="싫어요">👎</span> 검증 미통과 — 이전 답변 펼쳐보기` +
                (data.detail ? `<div class="text-warning mt-1">사유: ${escHtml(data.detail)}</div>` : '');
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
        // 아이콘+본문은 한 줄, 사유는 그 아래 줄 → flex 방향을 바꿔야 줄바꿈이 먹는다.
        notice.className = 'retry-notice small text-warning mb-2 d-flex flex-column';
        notice.innerHTML =
            `<div class="d-flex align-items-center gap-1">` +
                `<i class="bi bi-arrow-repeat"></i>` +
                `<span>${escHtml(data.text || '검증 미통과 — 검색 범위를 넓혀 재시도 중...')}</span>` +
            `</div>`;

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
            return `<a href="#" class="chat-image-thumb" data-image-ref="${escHtml(ref)}" data-turn-id="" data-bubble-id="${bubbleId}">
                <img src="${url}" alt="참조 이미지" loading="lazy"
                     style="max-height:120px; max-width:180px; object-fit:contain; border:1px solid var(--border-default, #dee2e6); border-radius:4px;" />
            </a>`;
        }).join('');
        container.innerHTML = `<div class="mt-2 d-flex flex-wrap gap-2">${thumbs}</div>`;
    }

    /* 검색 진단 수치 — null은 "측정 안 됨"이지 0이 아니므로 해당 항목만 생략한다.
       (벡터 축에 없던 청크는 유사도가 없고, 확장 실패 폴백 경로는 RRF 자체를 건너뛴다) */
    function renderSourceMetrics(s) {
        const parts = [];
        if (typeof s.similarity === 'number')      parts.push(`유사도 ${s.similarity.toFixed(2)}`);
        if (typeof s.retrieval_share === 'number') parts.push(`검색 ${Math.round(s.retrieval_share * 100)}%`);
        if (s.axis_ranks)                          parts.push(escHtml(s.axis_ranks));
        if (parts.length === 0) return '';
        return `<span class="source-metrics text-muted me-2" style="font-size:0.72rem;">${parts.join(' · ')}</span>`;
    }

    function onSources(bubbleId, sources) {
        const container = document.getElementById(`stream-sources-${bubbleId}`);
        if (!container || !sources || sources.length === 0) return;
        const previewEnabled = typeof window.isSourcePreviewEnabled === 'function'
            ? window.isSourcePreviewEnabled()
            : true;
        const metricsEnabled = typeof window.isRetrievalMetricsEnabled === 'function'
            && window.isRetrievalMetricsEnabled();
        const refs = sources.map(s => {
            const label = escHtml(s.label || '출처');
            const previewAttr = previewEnabled
                ? `data-bs-toggle="popover" data-bs-trigger="hover focus" data-bs-placement="top" data-preview-md="${escHtml(s.preview || '')}"`
                : '';
            const chunkIdAttr = s.chunk_id ? `data-chunk-id="${escHtml(s.chunk_id)}"` : '';
            const badge = `<a href="#" class="source-ref badge bg-secondary text-decoration-none me-1 mb-1" ${previewAttr} ${chunkIdAttr} title="${label}">${label}</a>`;
            return metricsEnabled ? badge + renderSourceMetrics(s) : badge;
        }).join('');
        container.innerHTML = `<div class="mt-2">${refs}</div>`;
        if (previewEnabled) {
            container.querySelectorAll('[data-bs-toggle="popover"]').forEach(bindSourcePopover);
        }
    }

    /* trigger를 hover/focus 자동 바인딩 대신 manual로 두고 show/hide를 직접 제어한다 —
       배지에서 마우스를 떼는 순간 Bootstrap 기본 동작(hide 지연 없음)이 팝오버 박스에
       닿기도 전에 먼저 닫아버려 미리보기 텍스트를 드래그로 선택/복사할 수 없었다. 짧은
       유예(250ms) 뒤에 닫되, 팝오버 박스 위에 마우스가 있는 동안은 유예를 계속 취소한다. */
    function bindSourcePopover(el) {
        const popover = new bootstrap.Popover(el, {
            html: true,
            sanitize: false,
            trigger: 'manual',
            content: () => renderSourcePreviewHtml(el.getAttribute('data-preview-md') || '')
        });
        let hideTimer = null;
        const cancelHide = () => { clearTimeout(hideTimer); hideTimer = null; };
        const scheduleHide = () => { cancelHide(); hideTimer = setTimeout(() => popover.hide(), 250); };
        el.addEventListener('mouseenter', () => { cancelHide(); popover.show(); });
        el.addEventListener('mouseleave', scheduleHide);
        el.addEventListener('focus', () => { cancelHide(); popover.show(); });
        el.addEventListener('blur', scheduleHide);
        el.addEventListener('shown.bs.popover', function () {
            const tipId = el.getAttribute('aria-describedby');
            const tip = tipId ? document.getElementById(tipId) : null;
            if (!tip || tip.dataset.hoverBridged) return;
            tip.dataset.hoverBridged = '1';
            tip.addEventListener('mouseenter', cancelHide);
            tip.addEventListener('mouseleave', scheduleHide);
        });
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

    /**
     * Streaming has finished but the turn isn't done — a blocking sufficiency+grounded LLM
     * check (several seconds to tens of seconds) runs before the next event. Append a small
     * indicator as the last child of the content element so the existing .stream-cursor
     * ::after pseudo-element (still on the parent) keeps blinking right after it, same as
     * during token streaming. The indicator is a real DOM node (not raw text appended to
     * contentEl directly) precisely so removeVerifyingIndicator() can strip it cleanly before
     * onRetry()/onDone()/onAborted() read/render the raw answer text.
     */
    function onVerifying(bubbleId) {
        const contentEl = document.getElementById(`stream-content-${bubbleId}`);
        if (!contentEl || document.getElementById(`stream-verifying-${bubbleId}`)) return;
        const indicator = document.createElement('span');
        indicator.id = `stream-verifying-${bubbleId}`;
        indicator.className = 'text-muted small ms-1';
        indicator.textContent = '(응답결과 검증 중)';
        contentEl.appendChild(indicator);
        scrollToBottom();
    }

    /** Strips the "verifying" indicator span before anything reads contentEl's raw text. */
    function removeVerifyingIndicator(bubbleId) {
        document.getElementById(`stream-verifying-${bubbleId}`)?.remove();
    }

    function onDone(bubbleId, data) {
        const contentEl = document.getElementById(`stream-content-${bubbleId}`);
        const stageEl   = document.getElementById(`stream-stage-${bubbleId}`);
        const metaEl    = document.getElementById(`stream-meta-${bubbleId}`);

        // 1. Remove streaming cursor + the "verifying" indicator (if the turn ended right after
        //    a verification pass, before markdown rendering picks up contentEl's raw text below).
        if (contentEl) contentEl.classList.remove('stream-cursor');
        removeVerifyingIndicator(bubbleId);

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
            // 재시도를 다 쓰고도 통과하지 못한 답변 — 배지에 사유를 실어 왜 미검증인지 알 수 있게 한다
            // (네이티브 title: 메타데이터 줄이라 상시 노출하면 길어진다. 사유는 아래 줄에도 한 번 더 나온다).
            else if (data.grounded === false) parts.push(
                `<span class="badge bg-warning text-dark me-1"${data.evalReason ? ` title="${escHtml(data.evalReason)}" style="cursor:help;"` : ''}>미검증</span>`);
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

            // 미검증으로 확정된 답변의 사유는 배지 툴팁에만 두지 않고 한 줄로도 보여준다 —
            // 마우스를 올려봐야 알 수 있으면 모바일에서는 확인할 방법이 없다.
            if (data.grounded === false && data.evalReason) {
                html += `<div class="small text-warning mt-1">`
                     +  `<i class="bi bi-exclamation-triangle me-1"></i>`
                     +  `검증 미통과 사유: ${escHtml(data.evalReason)}</div>`;
            }

            // 경로·주소·포트·환경변수처럼 실행 환경에 따라 달라지는 값 안내. 이런 값은 문서와 달라도
            // 검증 실패로 치지 않으므로(prompt.answer.eval 의 환경 의존 값 예외) 검증됨 배지가 붙은
            // 답변에도 실린다 — grounded 조건을 걸지 않는 이유다(message-assistant.html 과 동일).
            if (data.envNote) {
                html += `<div class="small text-info mt-1">`
                     +  `<i class="bi bi-info-circle me-1"></i>`
                     +  `환경에 따라 달라질 수 있는 값: ${escHtml(data.envNote)}</div>`;
            }

            metaEl.innerHTML = html;
        }

        // 5. Trigger thread list refresh
        if (data.refreshThreadList && typeof htmx !== 'undefined') {
            htmx.trigger(document.body, 'refreshThreadList');
        }

        scrollToBottom();

        if (data.turnId) {
            document.querySelectorAll(`#stream-images-${bubbleId} .chat-image-thumb`)
                .forEach(el => { el.dataset.turnId = String(data.turnId); });
        }
    }

    function onError(bubbleId, message) {
        const bubble = document.getElementById(`bubble-${bubbleId}`);
        if (bubble) {
            bubble.outerHTML =
                `<div class="d-flex align-items-start mb-3">` +
                `<div class="bubble-assistant p-3 flex-grow-1 text-danger">` +
                `<i class="bi bi-exclamation-triangle me-1"></i>${escHtml(message || '오류가 발생했습니다.')}` +
                `</div>` +
                `</div>`;
        }
    }

    /** User-initiated stop (AbortController). Keeps whatever partial answer already streamed in. */
    function onAborted(bubbleId) {
        clearRetryArtifacts(bubbleId);
        removeVerifyingIndicator(bubbleId);
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
            case 'stage':     onStage(bubbleId, data);          break;
            case 'sources':   onSources(bubbleId, data);        break;
            case 'images':    onImages(bubbleId, data);         break;
            case 'token':     onToken(bubbleId, data.text);     break;
            case 'verifying': onVerifying(bubbleId);            break;
            case 'retry':     onRetry(bubbleId, data);          break;
            case 'done':      onDone(bubbleId, data);           break;
            case 'error':     onError(bubbleId, data.message);  break;
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

            // Defensive sync: capture the currently checked S/M/L radio into the hidden field
            // right before FormData is built. This avoids stale hidden values when users
            // quickly switch mode and submit in one interaction.
            const selectedMode = document.querySelector('input[name="response-mode-radio"]:checked');
            const hiddenMode = document.getElementById('form-response-mode');
            if (selectedMode && hiddenMode) hiddenMode.value = selectedMode.value;

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
