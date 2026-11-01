// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Body of {@code POST /ai/chat}.
 *
 * <p>{@code conversationId} is optional. The runtime issues the ids, so one it has never issued
 * starts a new conversation rather than being adopted — see {@code ConversationStore}.
 *
 * <p>{@code targetId} names the business object this turn is about. It is <b>generic on purpose</b>:
 * it used to be called {@code customerId}, and that one name was why a second module had nowhere of
 * its own to put a row id — it had to borrow a field named for CRM. Which object the id refers to is
 * the route's business rather than this body's; a route that needs to be sure reads the type it knows.
 *
 * <p><b>The id is text, because not every object is numbered.</b> It was a {@code Long} until a
 * Flowable process instance had to travel through it: those ids are UUIDs, and a body typed for
 * numbers refused the request at deserialization, before any route saw it. A number in the JSON still
 * binds — the binder hands {@code 7} over as {@code "7"} — and the turn service gives numeric ids
 * back to the routes as numbers, so an existing tool is handed exactly the value it always was. The
 * type is text here because text is the one shape both can be written in.
 *
 * <p>It is optional, and the console does not always send it: the console names the object it is
 * looking at inside the message ("当前客户「Acme」（ID 1）。…"), which is what a model would read. The
 * runtime resolves the reference either way — this field first, then the conversation's own
 * transcript — so a caller that has an id can state it, and one that does not is still understood
 * (see {@code ChatTurnService}).
 *
 * <p><b>{@code customerId} still binds.</b> The field was renamed for the reason above, and the old
 * name is kept as a JSON alias rather than dropped: the callers are not all in this repository — the
 * main repository's admin console posts the old name — and a rename that broke them would be a wire
 * change dressed up as a tidy-up.
 *
 * 聊天请求体 `POST /ai/chat`。
 *
 * <p>`conversationId` 可选。id 由运行时签发，故一个它从未发过的 id 会**开一条新会话**、而不是被采纳
 * —— 见 `ConversationStore`。
 *
 * <p>`targetId` 命名这一轮说的业务对象。它**刻意是通用的**：从前叫 `customerId`，而正是那一个名字让第二个模块
 * 没有自己的地方放行 id —— 只能借一个以 CRM 命名的字段。这个 id 指的是哪个对象是**路由**的事、不是本体的：
 * 需要确定的路由会读它认识的那个类型。
 *
 * <p><b>id 是文本，因为不是每个对象都有编号。</b>它在一条 Flowable 流程实例必须穿过它之前一直是 `Long`：
 * 那些 id 是 UUID，而一个按数字定型的 body 在**反序列化**阶段就拒了请求，任何路由都还没看到它。JSON 里的数字
 * **照样绑得上** —— 绑定器把 `7` 交成 `"7"` —— 而回合服务会把数字 id 以数字交还给路由，故一个既有工具拿到的
 * 正是它一直拿到的那个值。这里写成文本，是因为文本是两者都能写进去的那一种形状。
 *
 * <p>它可选，而控制台并不总是发它：控制台在消息里点名它正在看的对象（"当前客户「Acme」（ID 1）。…"），
 * 那是一个模型会读的东西。运行时**两条路**都能解析这个引用 —— 先看本字段、再看会话自己的记录 —— 于是手上
 * 有 id 的调用方可以写出来，而没有的也照样被理解（见 `ChatTurnService`）。
 *
 * <p>**`customerId` 照样绑。** 这个字段因上面的理由改过名，而旧名以 JSON 别名保留、没有删掉：调用方**不全在
 * 本仓** —— 主仓的管理界台今天就发旧名 —— 一次悄悄不再绑它的改名，是把一次 wire 改动装扮成一次整理。
 */
public record ChatRequest(String message, @JsonAlias("customerId") String targetId,
                          String conversationId) {

    /**
     * The old name, still readable — as the number it used to be.
     *
     * <p>It answers {@code null} for an id that is not numeric. That is the honest reading of a
     * deprecated accessor that never had a shape for one, and the slot itself still carries the text.
     *
     * @deprecated the slot is generic now — read {@link #targetId()}. Kept because callers written
     *             against it exist, and because removing it would turn a rename into a breakage.
     *
     * @deprecated 槽位现在是通用的 —— 读 {@link #targetId()}。留着是因为确实有照它写的调用方，也因为
     *             删掉它等于把一次改名变成一次破坏。
     */
    @Deprecated
    public Long customerId() {
        if (targetId == null) {
            return null;
        }
        try {
            return Long.valueOf(targetId);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
