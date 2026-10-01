/**
 * markdown-tex-symbols.js — LLM 답변의 LaTeX 기호 표기($\rightarrow$ 등)를 문자로 그린다.
 *
 * marked 는 수식을 모르고 이 앱에는 수식 렌더러(KaTeX 등)가 없어서, 모델이 화살표를 $\rightarrow$ 로 쓰면
 * 화면에 그 글자가 그대로 나왔다. 실제 수식이 아니라 기호 하나를 그렇게 쓰는 경우가 대부분이라, 렌더러를
 * 들이는 대신 알려진 기호 명령만 유니코드 문자로 바꾼다. marked 의 인라인 확장이라 코드 블록·인라인 코드
 * 안은 marked 가 먼저 가져가 건드리지 않고, marked 를 쓰는 렌더 지점 전부(채팅 스트리밍·완료·기록·재사용·
 * HTMX, 출처·청크 미리보기)에 한 번에 걸린다 — 이미 저장된 답변도 다시 열면 바뀌어 보인다. 저장된 원문은
 * 그대로다. layout/base.html 이 marked 바로 뒤에 싣는다(앞에 오면 marked 가 없어 아무 일도 하지 않는다).
 *
 * 바꾸는 것은 $...$ · $$...$$ · \(...\) 안의 명령이 **전부** 아래 표에 있을 때뿐이다. 모르는 명령(\frac 등)이나
 * 중괄호·\\ 처럼 구조를 가진 TeX 가 하나라도 남으면 원문 그대로 둔다 — 반쯤 바꾼 수식은 원문보다 읽기
 * 어렵다. $5, $HOME 처럼 명령이 없는 달러 기호도 그대로다. 답변 프롬프트도 이 표기를 쓰지 말라고 하지만
 * 작은 로컬 모델은 가끔 어기므로 이쪽이 안전망이다.
 */
(function () {
    'use strict';
    if (typeof marked === 'undefined' || typeof marked.use !== 'function') return;

    const SYMBOLS = {
        // 화살표
        to: '→', rightarrow: '→', longrightarrow: '⟶', leftarrow: '←', gets: '←', longleftarrow: '⟵',
        leftrightarrow: '↔', longleftrightarrow: '⟷', Rightarrow: '⇒', Longrightarrow: '⟹', implies: '⟹',
        Leftarrow: '⇐', Longleftarrow: '⟸', impliedby: '⟸', Leftrightarrow: '⇔', Longleftrightarrow: '⟺',
        iff: '⟺', uparrow: '↑', downarrow: '↓', updownarrow: '↕', Uparrow: '⇑', Downarrow: '⇓',
        nearrow: '↗', searrow: '↘', swarrow: '↙', nwarrow: '↖', mapsto: '↦', hookrightarrow: '↪',
        hookleftarrow: '↩', rightleftharpoons: '⇌', rightleftarrows: '⇄', leftrightarrows: '⇆',
        // 관계
        le: '≤', leq: '≤', ge: '≥', geq: '≥', ne: '≠', neq: '≠', approx: '≈', sim: '∼', simeq: '≃',
        equiv: '≡', cong: '≅', propto: '∝', ll: '≪', gg: '≫', in: '∈', notin: '∉', ni: '∋',
        subset: '⊂', subseteq: '⊆', supset: '⊃', supseteq: '⊇', cup: '∪', cap: '∩',
        emptyset: '∅', varnothing: '∅', perp: '⊥', parallel: '∥', angle: '∠',
        // 연산·기타
        times: '×', div: '÷', cdot: '·', pm: '±', mp: '∓', ast: '∗', star: '⋆', circ: '∘',
        bullet: '•', oplus: '⊕', otimes: '⊗', ldots: '…', dots: '…', cdots: '⋯', vdots: '⋮',
        infty: '∞', partial: '∂', nabla: '∇', forall: '∀', exists: '∃', neg: '¬', lnot: '¬',
        land: '∧', wedge: '∧', lor: '∨', vee: '∨', therefore: '∴', because: '∵', checkmark: '✓',
        prime: '′', degree: '°', quad: ' ', qquad: ' ',
        // 그리스 문자
        alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', varepsilon: 'ε', zeta: 'ζ',
        eta: 'η', theta: 'θ', iota: 'ι', kappa: 'κ', lambda: 'λ', mu: 'μ', nu: 'ν', xi: 'ξ',
        pi: 'π', rho: 'ρ', sigma: 'σ', tau: 'τ', upsilon: 'υ', phi: 'φ', varphi: 'φ', chi: 'χ',
        psi: 'ψ', omega: 'ω', Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π',
        Sigma: 'Σ', Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω',
    };

    // 글자를 감싸기만 하는 명령 — 안의 글자만 남긴다($\text{CoreBanking} \rightarrow \text{MCI}$).
    const WRAPPER = /\\(?:text|textrm|textbf|textit|mathrm|mathbf|mathit|operatorname)\{([^{}]*)\}/g;

    /** TeX 조각을 문자로 — 바꿀 수 없으면(모르는 명령·구조가 남음, 명령이 하나도 없음) null. */
    function toText(tex) {
        let converted = false;
        let unknown = false;
        const out = tex
            .replace(WRAPPER, (m, inner) => { converted = true; return inner; })
            .replace(/\^\\circ\b/g, () => { converted = true; return '°'; })   // 90^\circ
            .replace(/\\[,;:! ]/g, ' ')                                        // 간격 명령
            .replace(/\\([a-zA-Z]+)/g, (m, name) => {
                if (Object.prototype.hasOwnProperty.call(SYMBOLS, name)) { converted = true; return SYMBOLS[name]; }
                unknown = true;
                return m;
            });
        if (!converted || unknown || /[\\{}]/.test(out)) return null;
        return out.replace(/\s+/g, ' ').trim();
    }

    function escapeHtml(s) {
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // 한 줄 안에서만 — 줄을 넘는 $ 짝은 수식이 아니라 우연히 둘 있는 달러 기호일 가능성이 크다.
    const DELIMITED = [/^\$\$([^$\n]+?)\$\$/, /^\$([^$\n]+?)\$(?!\d)/, /^\\\(([^\n]+?)\\\)/];

    marked.use({
        extensions: [{
            name: 'texSymbols',
            level: 'inline',
            // 후보가 시작될 수 있는 자리 — 명령(\)이 든 $ 구간이나 \( 만 본다. 아무 $ 에서나 끊으면 가격 같은
            // 평범한 달러 기호마다 글자 토큰이 쪼개진다(결과는 같지만 쓸데없다).
            start(src) {
                const i = src.search(/\$\$?[^$\n]*\\|\\\(/);
                return i < 0 ? undefined : i;
            },
            tokenizer(src) {
                for (const re of DELIMITED) {
                    const m = re.exec(src);
                    if (!m) continue;
                    const text = toText(m[1]);
                    return text === null ? undefined : { type: 'texSymbols', raw: m[0], text };
                }
                return undefined;
            },
            renderer(token) {
                return escapeHtml(token.text);
            },
        }],
    });
})();
