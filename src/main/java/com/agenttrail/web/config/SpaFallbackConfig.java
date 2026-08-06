package com.agenttrail.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Vue Router 用的是 history 模式（{@code createWebHistory()}），浏览器地址栏里 {@code /chat}、
 * {@code /admin/users} 这些路径都是纯前端路由，服务端本来就没有对应文件。直接刷新或者分享链接
 * 访问这类路径时，Spring 的静态资源处理器按路径去 {@code static/} 下找同名文件，找不到就落到
 * 默认的 Whitelabel 404——这不是 SPA 该有的行为，应该退回 {@code index.html} 让 Vue Router
 * 接管，而不是把"刷新页面"变成一个真的会 404 的操作。
 *
 * <p>只对静态资源处理器生效，不影响 {@code /agent/**}、{@code /api/**}：Spring MVC 先按
 * {@code @RequestMapping} 路由匹配，只有没匹配到任何 Controller 的 GET 请求才会落到这里；
 * {@link SpaIndexFallbackResourceResolver} 对这两个前缀直接放行到"没找到"，不会把真正的接口
 * 404（比如路径写错的 API）悄悄吞成一个 200 的 index.html。
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaIndexFallbackResourceResolver());
    }

    /** 抽成具名类是为了能脱离 Spring 容器单独做单元测试（见 {@code SpaIndexFallbackResourceResolverTest}）。 */
    static class SpaIndexFallbackResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) return requested;
            // 写错的、真的不存在的 /agent/** 或 /api/** 请求必须继续 404——只有"页面形状"的路径
            // （既没有匹配到任何 Controller，也没有对应的静态文件）才退回 index.html。
            if (resourcePath.startsWith("agent/") || resourcePath.startsWith("api/")) return null;
            return new ClassPathResource("/static/index.html");
        }
    }
}
