// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import java.util.List;

/**
 * The replier local debug mode uses: the deterministic one, made explicit about what it cannot do.
 *
 * <p>{@link DeterministicReplier} already refuses to look like a model. This one adds the one fact a
 * developer debugging locally needs and a production fallback does not: when no rule matches, the
 * reason is the <em>mode</em>, not a missing provider that was never asked for. The sentence says so
 * in as many words — a free-form answer would need a model, and this mode has none — instead of
 * implying that an answer exists and is merely unavailable.
 *
 * <p>Everything the engine actually did is still described by {@link DeterministicReplier}; this
 * class only replaces the no-outcome branch. Reusing that description is deliberate: a second wording
 * for the same outcomes would drift, and the outcomes are the part a debugger is reading.
 *
 * <p>本地调试模式使用的回复器：确定性回复器，加上一句它做不到什么。
 *
 * <p>{@link DeterministicReplier} 本来就不会冒充模型。这一个补上开发者在本地调试时最需要、而生产默认回落
 * 不需要的那个事实：规则匹配不到时，原因是**这个模式**，不是「本该有、却没要到的 provider」。那句话会**明说**
 * ——自由作答需要模型，而本模式没有 —— 而不是暗示「答案存在、只是一时拿不到」。
 *
 * <p>引擎**实际做了什么**仍由 {@link DeterministicReplier} 描述；本类只替换**没有 outcome** 的那一支。
 * 复用那段描述是刻意的：同一批 outcome 写第二套措辞会漂移，而 outcome 正是调试者要读的那一半。
 */
public class LocalDebugReplier implements ChatReplier {

    private final DeterministicReplier delegate = new DeterministicReplier();

    @Override
    public ChatReply reply(String message, List<ChatTurn> history, ExecutionOutcome outcome) {
        if (outcome != null) {
            return delegate.reply(message, history, outcome);
        }
        return new ChatReply(
                "Offline debug mode: no deterministic rule matched this request. A free-form answer "
                        + "would need a model, and this mode has none, so nothing was proposed. Turn "
                        + "keelbase.local-debug.enabled off and configure a provider to ask freely.",
                DeterministicReplier.PROVIDER, DeterministicReplier.MODEL);
    }
}
