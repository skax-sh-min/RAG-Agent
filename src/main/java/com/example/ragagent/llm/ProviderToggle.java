package com.example.ragagent.llm;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory operator override for enabling/disabling registered LLM providers at runtime (§A).
 *
 * <p>Mirrors {@link CircuitBreaker}: a per-provider-name state holder that {@code LlmRouter.findFirst()}
 * consults as an extra eligibility filter, and that the {@code /settings} page reads/writes. Toggling
 * takes effect on the next routing decision with no bean rebuild — the provider's {@code ChatModel} /
 * concurrency gate are untouched, this only hides it from selection.
 *
 * <p><b>Volatile by design:</b> the disabled set lives only in memory, so a restart re-enables every
 * provider (the {@code application.properties}/env config is authoritative again). This matches
 * {@code CircuitBreaker}'s ephemerality; an operator who needs a provider off permanently comments it
 * out / removes its env var instead.
 *
 * <p>Keyed by provider <i>name</i>: when two providers share a name (e.g. a load-balanced pair on
 * different API keys), disabling that name hides both instances — they are the same logical model.
 */
@Component
public class ProviderToggle {

    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    /** Enables ({@code enabled=true}) or disables ({@code enabled=false}) the named provider. Idempotent. */
    public void setEnabled(String providerName, boolean enabled) {
        if (enabled) {
            disabled.remove(providerName);
        } else {
            disabled.add(providerName);
        }
    }

    /** True when the named provider has been disabled by an operator (so routing must skip it). */
    public boolean isDisabled(String providerName) {
        return disabled.contains(providerName);
    }

    /** True when the named provider is currently selectable (not operator-disabled). */
    public boolean isEnabled(String providerName) {
        return !disabled.contains(providerName);
    }

    /** Snapshot of currently disabled provider names. */
    public Set<String> disabledNames() {
        return Set.copyOf(disabled);
    }
}
