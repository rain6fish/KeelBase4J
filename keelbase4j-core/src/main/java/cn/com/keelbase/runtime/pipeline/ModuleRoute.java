// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.List;
import java.util.Map;

/**
 * A route a module contributes to its deployment's planner: the phrases it answers to, the tool it
 * proposes, and the arguments it can fill from the request's context.
 *
 * <p><b>Lifted, not invented.</b> Two hosts that embed this runtime wrote this interface
 * independently, and the two are the same one — same method names, same types. Their point is the same
 * on both: a module contributes its routes as beans, so adding a module does not mean editing the
 * deployment's planner.
 *
 * <p><b>A route decides what to call, never whether it may run.</b> A route that names a tool nobody
 * declared, or fills an argument wrong, produces a proposal the engine then refuses; nothing here can
 * widen anything.
 *
 * 一个模块贡献给它所在部署的规划器的一条路由：它应答的短语、它提议的工具、以及它**能从请求上下文里填出的参数**。
 *
 * <p><b>这是上提来的、不是发明的。</b>两个嵌入本运行时的宿主**各自独立**写出了这个接口，而两者**就是同一个**
 * —— 方法名一样、类型一样。它在那两边要的都是同一件事：模块以 bean 形式贡献自己的路由，于是加一个模块
 * **不必**改部署方的规划器。
 *
 * <p><b>路由决定**调什么**，从不决定**能不能跑**。</b>一条点了没人声明过的工具、或填错参数的路由，产出的
 * 是一个**会被引擎拒绝**的提议；这里没有任何东西能放宽什么。
 */
public interface ModuleRoute {

    /** The tool this route proposes. */
    String toolName();

    /** The phrases this route answers to. */
    List<String> triggers();

    /**
     * The arguments this route fills from the request's context. A read route needs none and returns an
     * empty map; a write route takes the values it was given, by the module's own field names. A route
     * never invents an argument it cannot fill — the tool refuses a call it cannot run, and inventing one
     * would be worse than refusing.
     *
     * 本路由从请求上下文里填出的参数。读路由不需要参数、返回空表；写路由按**模块自己的字段名**取它拿到的值。
     * 路由**从不编造**它填不出的参数——工具会拒绝一次跑不了的调用，而编造比拒绝更坏。
     */
    Map<String, Object> args(Map<String, Object> context);
}
