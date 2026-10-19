// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The deployment facts a coverage statement needs and the runtime cannot derive from its own tables.
 *
 * <p>A statement is only honest if it knows what it is embedded in. {@code host} says which platform
 * owns the business write, and the two booleans say whether that host owns the write and runs an AI
 * path of its own — the facts behind `host-write-not-in-same-transaction` and
 * `host-ai-path-not-governed`. A standalone runtime answers `none` / `false` / `false`, and the two
 * host gaps then correctly do not apply. Embedding the runtime in a host is the deployment's fact to
 * declare, not something this runtime can see from the inside.
 *
 * <p>覆盖声明所需、而运行时无法从自己的表里推出的部署事实。
 *
 * <p>一份声明只有知道**自己嵌在什么里面**才是诚实的。{@code host} 说明哪个平台拥有那次业务写，
 * 两个布尔说该宿主是否拥有这次写、是否有自己的一条 AI 路径 —— 它们正是
 * `host-write-not-in-same-transaction` 与 `host-ai-path-not-governed` 背后的事实。独立运行时答
 * `none` / `false` / `false`，那两条宿主缺口于是**正确地不适用**。把运行时嵌进宿主，是**部署方**要声明
 * 的事实，不是运行时从内部看得见的。
 */
@ConfigurationProperties(prefix = "keelbase.coverage")
public class CoverageProperties {

    /** `none`, `yudao` or `ruoyi` — the platform the runtime is embedded in. / 运行时嵌入的平台。 */
    private String host = "none";

    /** The host, not the runtime, writes the business row. / 业务那一行由**宿主**写、不由运行时写。 */
    private boolean hostOwnsBusinessWrite;

    /** The host runs an AI path of its own that does not pass through this engine. / 宿主有自己的一条 AI 路径。 */
    private boolean hostOwnAiPath;

    /** Action surfaces exist that are not discrete named tools. / 存在不是离散具名工具的动作面。 */
    private boolean nonDiscreteActionSurfacesExposed;

    /** One cache serves more than one deployment. / 一个缓存服务于不止一个部署。 */
    private boolean sharedCacheAcrossDeployments;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public boolean isHostOwnsBusinessWrite() {
        return hostOwnsBusinessWrite;
    }

    public void setHostOwnsBusinessWrite(boolean hostOwnsBusinessWrite) {
        this.hostOwnsBusinessWrite = hostOwnsBusinessWrite;
    }

    public boolean isHostOwnAiPath() {
        return hostOwnAiPath;
    }

    public void setHostOwnAiPath(boolean hostOwnAiPath) {
        this.hostOwnAiPath = hostOwnAiPath;
    }

    public boolean isNonDiscreteActionSurfacesExposed() {
        return nonDiscreteActionSurfacesExposed;
    }

    public void setNonDiscreteActionSurfacesExposed(boolean nonDiscreteActionSurfacesExposed) {
        this.nonDiscreteActionSurfacesExposed = nonDiscreteActionSurfacesExposed;
    }

    public boolean isSharedCacheAcrossDeployments() {
        return sharedCacheAcrossDeployments;
    }

    public void setSharedCacheAcrossDeployments(boolean sharedCacheAcrossDeployments) {
        this.sharedCacheAcrossDeployments = sharedCacheAcrossDeployments;
    }
}
