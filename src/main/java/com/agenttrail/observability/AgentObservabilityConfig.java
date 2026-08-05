package com.agenttrail.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** OTel 采样策略：有父 span 时沿用父决定，否则按 10% 比例采样。 */
@Configuration(proxyBeanMethods = false)
public class AgentObservabilityConfig {

    @Bean
    public Sampler agentTraceSampler() {
        Sampler base = Sampler.parentBased(Sampler.traceIdRatioBased(0.1));
        return new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                    SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
                return base.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
            }

            @Override
            public String getDescription() {
                return "AgentTrailSampler{parentBased(ratio=0.1), error-100%-todo}";
            }
        };
    }
}
